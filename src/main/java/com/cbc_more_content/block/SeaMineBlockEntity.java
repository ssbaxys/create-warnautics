package com.cbc_more_content.block;

import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.damage.MineDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.mine.MineType;
import com.cbc_more_content.registry.ModBlockEntities;
import com.cbc_more_content.registry.ModSounds;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** A floating Sable block. Saved positions are plot positions; distance queries use world space. */
public class SeaMineBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {
    public static final int ARMING_TICKS = 100;
    public static final int OXIDATION_TICKS_PER_STAGE = 30 * 60 * 20;
    private static final int CONTACT_RESET_TICKS = 10;
    private int ageInWater;
    private int armingTicks = ARMING_TICKS;
    private boolean triggered;
    private boolean detonated;
    private long lastContactTick = Long.MIN_VALUE;

    public SeaMineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SEA_MINE.get(), pos, state);
    }

    public boolean isArmed() {
        return this.armingTicks == 0 && !this.detonated;
    }

    @Override
    public void sable$physicsTick(ServerSubLevel host, RigidBodyHandle handle, double timeStep) {
        if (this.isRemoved() || this.detonated || timeStep <= 0) {
            return;
        }
        ServerLevel world = host.getLevel();
        Vec3 center = host.logicalPose().transformPosition(this.worldPosition.getCenter());
        if (this.isArmed() && com.cbc_more_content.compat.sable.SeaMineHullContact.touchesHull(world, host, center)) {
            com.cbc_more_content.compat.sable.SableCollisionDetonationQueue.queueSeaMine(world, this.worldPosition);
        }
        double radius = 7.0D / 16.0D;
        BlockPos bottom = BlockPos.containing(center.x, center.y - radius, center.z);
        BlockPos top = BlockPos.containing(center.x, center.y + radius, center.z);
        if (!world.hasChunkAt(bottom) || !world.getFluidState(bottom).is(FluidTags.WATER)) {
            return;
        }
        double surface = bottom.getY() + world.getFluidState(bottom).getHeight(world, bottom);
        double submerged = world.getFluidState(top).is(FluidTags.WATER)
                ? 1.0D
                : Math.clamp((surface - (center.y - radius)) / (radius * 2.0D), 0.0D, 1.0D);
        // Archimedes lift works at rest and at any depth. Native volume is disabled for this block to avoid double
        // lift.
        Vector3d gravity = DimensionPhysicsData.getGravity(world, new Vector3d(center.x, center.y, center.z));
        Vector3d impulse = gravity.mul(-0.65D * submerged * timeStep);
        Vector3d velocity = handle.getLinearVelocity(new Vector3d());
        Vector3d arm = new Vector3d(center.x, center.y, center.z)
                .sub(host.logicalPose().position());
        velocity.add(handle.getAngularVelocity(new Vector3d()).cross(arm));
        impulse.fma(-0.35D * submerged * (1.0D - Math.exp(-4.0D * timeStep)), velocity);
        host.logicalPose().transformNormalInverse(impulse);
        handle.applyImpulseAtPoint(
                new Vector3d(
                        this.worldPosition.getX() + 0.5D,
                        this.worldPosition.getY() + 0.5D,
                        this.worldPosition.getZ() + 0.5D),
                impulse);
    }

    public static Vec3 worldPosition(ServerLevel level, BlockPos pos) {
        ServerSubLevel host = SableDropCompat.containingSubLevel(level, pos);
        return host == null ? pos.getCenter() : host.logicalPose().transformPosition(pos.getCenter());
    }

    public static void tick(Level level, BlockPos pos, BlockState state, SeaMineBlockEntity mine) {
        if (!(level instanceof ServerLevel server) || mine.detonated || mine.isRemoved()) {
            return;
        }
        if (mine.triggered) {
            mine.detonate(server);
            return;
        }
        Vec3 center = worldPosition(server, pos);
        // A floating mine can have its centre in air while the lower casing remains wet.
        mine.ageInWater = Math.max(mine.ageInWater, state.getValue(SeaMineBlock.OXIDATION) * OXIDATION_TICKS_PER_STAGE);
        if (touchesWater(server, center) && mine.ageInWater < 3 * OXIDATION_TICKS_PER_STAGE) {
            mine.ageInWater++;
            int stage = mine.ageInWater / OXIDATION_TICKS_PER_STAGE;
            if (state.getValue(SeaMineBlock.OXIDATION) != stage) {
                server.setBlock(pos, state.setValue(SeaMineBlock.OXIDATION, stage), Block.UPDATE_CLIENTS);
                mine.setChanged();
            }
            if (mine.ageInWater % 20 == 0) {
                mine.setChanged();
            }
        }
        if (mine.armingTicks > 0) {
            mine.armingTicks--;
            mine.setChanged();
            if (mine.armingTicks == 0) {
                server.playSound(
                        null,
                        center.x,
                        center.y,
                        center.z,
                        ModSounds.SEA_MINE_ARMED.get(),
                        SoundSource.BLOCKS,
                        1.2F,
                        1.0F);
            }
        } else {
            // Fish and dropped items do not trip mines. Query the world, never the distant storage plot.
            AABB bounds = AABB.ofSize(center, 1.0D, 1.0D, 1.0D).inflate(MineType.SEA_TRIGGER_REACH);
            if (!server.getEntities((Entity) null, bounds, SeaMineBlockEntity::tripsMine)
                    .isEmpty()) {
                mine.trigger();
            }
        }
    }

    public static boolean touchesWater(ServerLevel level, Vec3 center) {
        double radius = 7.0 / 16.0;
        for (BlockPos pos : BlockPos.betweenClosed(
                BlockPos.containing(center.add(-radius, -radius, -radius)),
                BlockPos.containing(center.add(radius, radius, radius)))) {
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            var fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.WATER)
                    && center.y - radius < pos.getY() + fluid.getHeight(level, pos)
                    && center.y + radius > pos.getY()) {
                return true;
            }
        }
        return false;
    }

    public int corrosionAge() {
        return this.ageInWater;
    }

    /** Operator test hook. Production ageing is exclusively driven by wet server ticks. */
    public void setCorrosionAge(int ticks) {
        this.ageInWater = Math.clamp(ticks, 0, 3 * OXIDATION_TICKS_PER_STAGE);
        if (this.level instanceof ServerLevel server) {
            server.setBlock(
                    this.worldPosition,
                    this.getBlockState().setValue(SeaMineBlock.OXIDATION, this.ageInWater / OXIDATION_TICKS_PER_STAGE),
                    Block.UPDATE_CLIENTS);
            this.setChanged();
        }
    }

    private static boolean tripsMine(Entity entity) {
        if (!entity.isAlive()) {
            return false;
        }
        if (entity instanceof Player player) {
            return !player.isSpectator() && !player.isCreative() && !player.getAbilities().flying;
        }
        return entity instanceof Boat;
    }

    public void trigger() {
        if (!this.isArmed() || this.triggered || !(this.level instanceof ServerLevel server)) {
            return;
        }
        long now = server.getGameTime();
        boolean newContact = this.lastContactTick == Long.MIN_VALUE
                || now < this.lastContactTick
                || now - this.lastContactTick > CONTACT_RESET_TICKS;
        this.lastContactTick = now;
        this.setChanged();
        if (!newContact) {
            return;
        }
        double chance = contactMisfireChance(this.getBlockState().getValue(SeaMineBlock.OXIDATION));
        if (chance > 0 && server.random.nextDouble() < chance) {
            Vec3 center = worldPosition(server, this.worldPosition);
            server.playSound(
                    null,
                    center.x,
                    center.y,
                    center.z,
                    SoundEvents.NETHERITE_BLOCK_HIT,
                    SoundSource.BLOCKS,
                    0.9F,
                    0.65F);
            return;
        }
        this.triggered = true;
    }

    /** One roll per contact episode, rather than one roll per physics sub-step. */
    public void contact(ServerLevel level) {
        this.trigger();
        if (this.triggered) {
            this.detonate(level);
        }
    }

    public static double contactMisfireChance(int oxidation) {
        return switch (oxidation) {
            case 1 -> 0.15D;
            case 2 -> 0.35D;
            case 3 -> 0.65D;
            default -> 0.0D;
        };
    }

    public void detonate(ServerLevel level) {
        if (!this.isArmed()
                || this.isRemoved()
                || !(level.getBlockState(this.worldPosition).getBlock() instanceof SeaMineBlock)) {
            return;
        }
        // Resolve before removing the last block: Sable may immediately remove its empty host.
        Vec3 center = worldPosition(level, this.worldPosition);
        this.detonated = true;
        level.removeBlock(this.worldPosition, false);
        BombExplosionHandler.detonateSeaMine(
                level,
                null,
                MineDamageSource.create(level, MineType.SEA),
                center,
                MineType.SEA.blockBlastPower,
                MineType.SEA.entityBlastPower);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level instanceof ServerLevel server
                && this.getBlockState().getValue(SeaMineBlock.WATERLOGGED)
                && !SableDropCompat.isInsideSubLevel(server, this.worldPosition)) {
            server.scheduleTick(this.worldPosition, this.getBlockState().getBlock(), 1);
        }
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.ageInWater = Math.clamp(
                tag.contains("WaterAge")
                        ? tag.getInt("WaterAge")
                        : this.getBlockState().getValue(SeaMineBlock.OXIDATION) * OXIDATION_TICKS_PER_STAGE,
                0,
                3 * OXIDATION_TICKS_PER_STAGE);
        this.armingTicks =
                tag.contains("ArmingTicks") ? Math.clamp(tag.getInt("ArmingTicks"), 0, ARMING_TICKS) : ARMING_TICKS;
        this.triggered = tag.getBoolean("Triggered");
        this.lastContactTick = tag.contains("LastContactTick") ? tag.getLong("LastContactTick") : Long.MIN_VALUE;
        // Legacy mooring tags are deliberately ignored: mines now float freely.
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("WaterAge", this.ageInWater);
        tag.putInt("ArmingTicks", this.armingTicks);
        tag.putBoolean("Triggered", this.triggered);
        tag.putLong("LastContactTick", this.lastContactTick);
    }
}
