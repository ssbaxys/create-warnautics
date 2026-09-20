package com.cbc_more_content.entity;

import com.cbc_more_content.compat.sable.SeaMineSableCompat;
import com.cbc_more_content.compat.sable.SeaMineSablePhysicsCompat;
import com.cbc_more_content.damage.MineDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.mine.MineType;
import com.cbc_more_content.registry.ModEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class SeaMineEntity extends Entity {
    private static final EntityDataAccessor<Integer> OXIDATION =
            SynchedEntityData.defineId(SeaMineEntity.class, EntityDataSerializers.INT);

    private static final int OXIDATION_TICKS_PER_STAGE = 30 * 60;

    private int ageInWater;
    private boolean triggered;
    public SeaMineSablePhysicsCompat.State sableState;
    private BlockPos anchor;
    private double anchorLength;

    public SeaMineEntity(EntityType<? extends SeaMineEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
    }

    public SeaMineEntity(Level level, Vec3 pos) {
        this(ModEntityTypes.SEA_MINE.get(), level);
        this.setPos(pos.x, pos.y, pos.z);
        this.xo = pos.x;
        this.yo = pos.y;
        this.zo = pos.z;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(OXIDATION, 0);
    }

    @Override
    public void tick() {
        this.baseTick();

        if (this.level().isClientSide) {
            if (this.isInWater() && this.getDeltaMovement().lengthSqr() > 1.0E-6D && this.random.nextInt(4) == 0) {
                this.level()
                        .addParticle(
                                ParticleTypes.BUBBLE,
                                this.getX() + (this.random.nextDouble() - 0.5D) * 0.6D,
                                this.getY() + (this.random.nextDouble() - 0.5D) * 0.6D,
                                this.getZ() + (this.random.nextDouble() - 0.5D) * 0.6D,
                                0.0D,
                                0.02D,
                                0.0D);
            }
            return;
        }

        if (this.triggered) {
            this.detonate();
            return;
        }

        if (this.level() instanceof ServerLevel server) {
            if (this.sableState == null) {
                this.sableState = SeaMineSablePhysicsCompat.create(server, this);
            }
            SeaMineSablePhysicsCompat.tick(server, this, this.sableState);
        }
        this.ageAndOxidize();
        this.sweepForContact();
        SeaMineSableCompat.sweepHulls((ServerLevel) this.level(), this);
    }

    private void ageAndOxidize() {
        if (!this.isInWater()) {
            return;
        }
        this.ageInWater++;
        int stage = Math.min(3, this.ageInWater / OXIDATION_TICKS_PER_STAGE);
        if (stage != this.getOxidation() && this.ageInWater % OXIDATION_TICKS_PER_STAGE == 0) {
            this.setOxidation(stage);
            this.playOxidizeSound();
        }
    }

    private void sweepForContact() {
        double reach = MineType.SEA_TRIGGER_REACH;
        AABB horns = this.getBoundingBox().inflate(reach);
        for (Player swimmer : this.level().getEntitiesOfClass(Player.class, horns, LivingEntity::isAlive)) {
            if (swimmer.isSpectator() || swimmer.getAbilities().flying) {
                continue;
            }
            this.trigger();
            return;
        }
    }

    public void trigger() {
        if (!this.level().isClientSide) {
            this.triggered = true;
        }
    }

    private void detonate() {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.playDetonationSound();
        SeaMineSablePhysicsCompat.remove(server, this.sableState);
        BombExplosionHandler.detonateSeaMine(
                server,
                null,
                MineDamageSource.create(server, MineType.SEA),
                this.position(),
                MineType.SEA.blockBlastPower,
                MineType.SEA.entityBlastPower);
        this.discard();
    }

    private void playOxidizeSound() {
        this.level()
                .playSound(
                        null,
                        this.getX(),
                        this.getY(),
                        this.getZ(),
                        SoundEvents.COPPER_BULB_TURN_OFF,
                        SoundSource.BLOCKS,
                        0.45f,
                        0.75f);
    }

    private void playDetonationSound() {
        this.level()
                .playSound(
                        null,
                        this.getX(),
                        this.getY(),
                        this.getZ(),
                        SoundEvents.GENERIC_EXPLODE.value(),
                        SoundSource.BLOCKS,
                        2.0f,
                        0.85f);
    }

    // —— mooring interaction ——

    /**
     * Mooring by hand. Simulated's rope strands can only clamp to blocks, so the mine
     * answers the rope tools itself:
     * <p>
     * <ul>
     * <li>A <strong>Rope Coupling</strong> that has tapped a Rope Connector (the
     * connection Simulated stores on the coupling) moors the mine to that connector —
     * the same two-click flow as coupling two connectors, with the mine as the far
     * end.</li>
     * <li>A coupling with no stored connection, or the Rope Connector's own item,
     * moors the mine straight to the ground below.</li>
     * <li>Sneak-clicking with either cuts the mooring.</li>
     * </ul>
     */
    @Override
    public net.minecraft.world.InteractionResult interact(Player player, net.minecraft.world.InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!isRopeTool(stack)) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        if (this.level().isClientSide) {
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        if (!(this.level() instanceof ServerLevel server)) {
            return net.minecraft.world.InteractionResult.CONSUME;
        }

        // Sneak with the rope tool in hand: cut the mooring.
        if (player.isShiftKeyDown() && this.isAnchored()) {
            SeaMineSablePhysicsCompat.unanchor(server, this);
            server.playSound(null, this.blockPosition(), SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 0.8f, 0.9f);
            say(player, "message.cbc_more_content.sea_mine.rope_cut");
            return net.minecraft.world.InteractionResult.CONSUME;
        }
        if (this.isAnchored()) {
            return net.minecraft.world.InteractionResult.CONSUME;
        }

        BlockPos connector = storedFirstConnection(stack);
        if (connector != null) {
            if (!isSimulatedConnector(this.level(), connector)) {
                say(player, "message.cbc_more_content.sea_mine.rope_first");
                return net.minecraft.world.InteractionResult.CONSUME;
            }
            if (connector.distToCenterSqr(this.position()) > CONNECTOR_RANGE * CONNECTOR_RANGE) {
                say(player, "message.cbc_more_content.sea_mine.rope_far");
                return net.minecraft.world.InteractionResult.CONSUME;
            }
            SeaMineSablePhysicsCompat.anchor(server, this, connector);
        } else {
            BlockPos floor = findAnchor(this.level(), this.blockPosition());
            if (floor == null) {
                return net.minecraft.world.InteractionResult.FAIL;
            }
            SeaMineSablePhysicsCompat.anchor(server, this, floor);
        }

        // Mirror RopeItem: the stored connection is spent, then the coupling is used up.
        spendRopeTool(stack, player);
        server.playSound(null, this.blockPosition(), SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.8f, 1.0f);
        say(player, "message.cbc_more_content.sea_mine.rope_linked");
        return net.minecraft.world.InteractionResult.CONSUME;
    }

    /** How far a rope connector may sit from the mine to be moorable. */
    private static final int CONNECTOR_RANGE = 64;

    private static boolean isRopeTool(ItemStack stack) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem())
                .toString();
        return id.equals("simulated:rope_coupling") || id.equals("simulated:rope_connector");
    }

    /**
     * The rope connector the coupling tapped, read out of Simulated's
     * {@code rope_first_connection} data component. Simulated is not on the compile
     * classpath, so the component type is found by registry name and the value — a
     * {@link BlockPos} in Simulated's own code — is read as {@code Object}.
     */
    @javax.annotation.Nullable
    private static BlockPos storedFirstConnection(ItemStack stack) {
        for (net.minecraft.core.component.DataComponentType<?> type :
                net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE) {
            var key = net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getResourceKey(type);
            if (key.isEmpty()
                    || !key.get().location().getNamespace().equals("simulated")
                    || !key.get().location().getPath().equals("rope_first_connection")) {
                continue;
            }
            Object value = stack.get(type);
            return value instanceof BlockPos pos ? pos : null;
        }
        return null;
    }

    /** Spends the rope tool: clears the stored connection, then consumes one item. */
    private static void spendRopeTool(ItemStack stack, Player player) {
        for (net.minecraft.core.component.DataComponentType<?> type :
                net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE) {
            var key = net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getResourceKey(type);
            if (key.isPresent()
                    && key.get().location().getNamespace().equals("simulated")
                    && key.get().location().getPath().equals("rope_first_connection")) {
                stack.remove(type);
                break;
            }
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
    }

    private static boolean isSimulatedConnector(net.minecraft.world.level.Level level, BlockPos pos) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock())
                .toString()
                .equals("simulated:rope_connector");
    }

    private static void say(Player player, String key) {
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(key), true);
    }

    public boolean isAnchored() {
        return this.anchor != null;
    }

    public BlockPos getAnchor() {
        return this.anchor;
    }

    public void setAnchor(BlockPos anchor) {
        this.anchor = anchor.immutable();
    }

    public double getAnchorLength() {
        return this.anchorLength;
    }

    public void setAnchorLength(double length) {
        this.anchorLength = Math.max(0.0D, length);
    }

    public int getOxidation() {
        return this.entityData.get(OXIDATION);
    }

    private void setOxidation(int stage) {
        this.entityData.set(OXIDATION, Math.max(0, Math.min(3, stage)));
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide) {
            return true;
        }
        this.trigger();
        return true;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(0.94f, 0.94f);
    }

    @Override
    public void push(Entity entity) {
        super.push(entity);
        if (!this.level().isClientSide && entity.isAlive() && !(entity instanceof SeaMineEntity)) {
            this.trigger();
        }
    }

    @Override
    public boolean canBeHitByProjectile() {
        return true;
    }

    // —— persistence ——

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.ageInWater = tag.getInt("WaterAge");
        this.setOxidation(tag.getInt("Oxidation"));
        this.anchor =
                tag.contains("Anchor") ? NbtUtils.readBlockPos(tag, "Anchor").orElse(null) : null;
        this.anchorLength = tag.getDouble("AnchorLength");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("WaterAge", this.ageInWater);
        tag.putInt("Oxidation", this.getOxidation());
        if (this.anchor != null) {
            tag.put("Anchor", NbtUtils.writeBlockPos(this.anchor));
            tag.putDouble("AnchorLength", this.anchorLength);
        }
    }

    // —— helpers ——

    @javax.annotation.Nullable
    public static BlockPos findAnchor(Level level, BlockPos from) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(from.getX(), from.getY(), from.getZ());
        for (int i = 0; i < 256; i++) {
            cursor.move(0, -1, 0);
            if (cursor.getY() < level.getMinBuildHeight()) {
                return null;
            }
            var state = level.getBlockState(cursor);
            if (state.isAir() || !state.getFluidState().isEmpty() || !state.isSolidRender(level, cursor)) {
                continue;
            }
            return cursor.immutable();
        }
        return null;
    }

    public static boolean canMooring(Level level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER);
    }

    @Override
    public ItemStack getPickedResult(net.minecraft.world.phys.HitResult target) {
        return new ItemStack(com.cbc_more_content.registry.ModItems.SEA_MINE.get());
    }
}
