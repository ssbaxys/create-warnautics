package com.cbc_more_content.entity;

import com.cbc_more_content.registry.ModEntityTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A chunk thrown clear of a blast, carrying whatever block it broke off.
 * <p>
 * Not a falling block — it never places anything back down. It tumbles, lands, sits for a
 * moment, then shrinks into the ground and is gone, the way a piece of ice does rather
 * than the way a dropped item does.
 */
public class BlastDebrisEntity extends Entity {
    private static final EntityDataAccessor<BlockState> BLOCK_STATE =
            SynchedEntityData.defineId(BlastDebrisEntity.class, EntityDataSerializers.BLOCK_STATE);
    private static final EntityDataAccessor<Boolean> SETTLED =
            SynchedEntityData.defineId(BlastDebrisEntity.class, EntityDataSerializers.BOOLEAN);

    private static final double GRAVITY = 0.05D;
    private static final double DRAG = 0.985D;
    /** One bounce off whatever it lands on, then it stays put. */
    private static final double BOUNCE = 0.35D;
    /** Ticks resting before it starts shrinking away, so a bounce is not mistaken for landing. */
    private static final int SETTLE_TICKS = 5;
    /** How long the shrink takes once it starts. */
    public static final int MELT_TICKS = 34;

    private static final int MAX_LIFETIME = 400;

    private boolean bounced;
    private int settledTicks;
    private int meltTicks;

    public BlastDebrisEntity(EntityType<? extends BlastDebrisEntity> type, Level level) {
        super(type, level);
    }

    public static BlastDebrisEntity create(ServerLevel level, BlockState state, Vec3 pos, Vec3 velocity) {
        BlastDebrisEntity debris = new BlastDebrisEntity(ModEntityTypes.BLAST_DEBRIS.get(), level);
        debris.setPos(pos.x, pos.y, pos.z);
        debris.setDeltaMovement(velocity);
        debris.entityData.set(BLOCK_STATE, state);
        return debris;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(BLOCK_STATE, Blocks.STONE.defaultBlockState());
        builder.define(SETTLED, false);
    }

    public BlockState blockState() {
        return this.entityData.get(BLOCK_STATE);
    }

    /** Once true the piece has stopped tumbling and is sitting where it landed. */
    public boolean isSettled() {
        return this.entityData.get(SETTLED);
    }

    /** 0 while intact, 1 the tick it finally disappears. */
    public float meltProgress(float partialTick) {
        if (!this.isSettled()) {
            return 0.0f;
        }
        float ticks = Math.max(0, this.settledTicks - SETTLE_TICKS) + partialTick;
        return Mth.clamp(ticks / MELT_TICKS, 0.0f, 1.0f);
    }

    /**
     * The cut of the block this piece shows: a box smaller than the full cube, picked
     * from the entity's own id so every viewer draws the same shape without a single
     * extra byte over the wire.
     */
    public float[] cutBox() {
        RandomSource random = RandomSource.create(this.getId() * 104_729L);
        float sx = 0.28f + random.nextFloat() * 0.34f;
        float sy = 0.28f + random.nextFloat() * 0.34f;
        float sz = 0.28f + random.nextFloat() * 0.34f;
        float ox = random.nextFloat() * (1.0f - sx);
        float oy = random.nextFloat() * (1.0f - sy);
        float oz = random.nextFloat() * (1.0f - sz);
        return new float[] {ox, oy, oz, ox + sx, oy + sy, oz + sz};
    }

    /** A stable tumble, so a piece looks the same on every client watching it fall. */
    public float[] spinDegreesPerTick() {
        RandomSource random = RandomSource.create(this.getId() * 104_729L + 1);
        return new float[] {
            6.0f + random.nextFloat() * 14.0f, 6.0f + random.nextFloat() * 14.0f, 6.0f + random.nextFloat() * 14.0f,
        };
    }

    @Override
    public void tick() {
        this.baseTick();

        if (this.tickCount > MAX_LIFETIME) {
            this.discard();
            return;
        }

        if (this.isSettled()) {
            if (this.level().isClientSide) {
                return;
            }
            if (++this.settledTicks - SETTLE_TICKS >= MELT_TICKS) {
                this.discard();
            }
            return;
        }

        Vec3 motion = this.getDeltaMovement().subtract(0.0D, GRAVITY, 0.0D).scale(DRAG);
        this.setDeltaMovement(motion);
        this.move(MoverType.SELF, this.getDeltaMovement());

        if (this.level().isClientSide) {
            return;
        }

        if (this.verticalCollision && this.getDeltaMovement().y < -0.01D) {
            if (!this.bounced) {
                this.bounced = true;
                Vec3 rebound = this.getDeltaMovement();
                this.setDeltaMovement(rebound.x * 0.6D, -rebound.y * BOUNCE, rebound.z * 0.6D);
            } else {
                this.settle();
            }
        } else if (this.onGround()) {
            this.settle();
        }
    }

    private void settle() {
        this.setDeltaMovement(Vec3.ZERO);
        this.entityData.set(SETTLED, true);
        this.settledTicks = 0;
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(
                    ParticleTypes.POOF, this.getX(), this.getY() + 0.1D, this.getZ(), 3, 0.08D, 0.02D, 0.08D, 0.01D);
        }
    }

    @Override
    public EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
        return EntityDimensions.scalable(0.4f, 0.4f);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        return distanceSqr < 64.0D * 64.0D;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        BlockState state = NbtUtils.readBlockState(
                this.level().holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                tag.getCompound("BlockState"));
        this.entityData.set(BLOCK_STATE, state);
        this.entityData.set(SETTLED, tag.getBoolean("Settled"));
        this.settledTicks = tag.getInt("SettledTicks");
        this.bounced = tag.getBoolean("Bounced");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("BlockState", NbtUtils.writeBlockState(this.blockState()));
        tag.putBoolean("Settled", this.isSettled());
        tag.putInt("SettledTicks", this.settledTicks);
        tag.putBoolean("Bounced", this.bounced);
    }

    @Override
    public boolean fireImmune() {
        // A charred stone chip surviving the blast that made it should not then burn
        // away in whatever fire the blast started.
        return true;
    }
}
