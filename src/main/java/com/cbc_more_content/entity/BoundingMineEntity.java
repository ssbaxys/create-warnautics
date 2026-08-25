package com.cbc_more_content.entity;

import com.cbc_more_content.damage.MineDamageSource;
import com.cbc_more_content.effects.MineExplosionHandler;
import com.cbc_more_content.mine.MineType;
import com.cbc_more_content.registry.ModEntityTypes;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The charge in the air, between the pop that threw it and the burst.
 * <p>
 * A separate entity rather than an animated block, because the whole point is that it
 * leaves the ground: as an entity it is interpolated between ticks like anything else, so
 * the rise reads as one smooth motion instead of a block flicking through positions.
 */
public class BoundingMineEntity extends Entity {
    /** Enough to clear cover and reach a standing torso. */
    private static final double POP_SPEED = 0.42D;
    private static final double GRAVITY = 0.045D;
    /** Ticks it hangs after the rise has stalled, before it lets go. */
    private static final int HANG_TICKS = 5;
    /** Nothing lives longer than this, however strangely it was thrown. */
    private static final int MAX_LIFETIME = 60;

    private int hang = -1;
    private boolean burst;

    public BoundingMineEntity(EntityType<? extends BoundingMineEntity> type, Level level) {
        super(type, level);
        this.noPhysics = false;
    }

    /** Throws a charge up out of {@code from}, already moving. */
    public static BoundingMineEntity pop(ServerLevel level, Vec3 from) {
        BoundingMineEntity mine = new BoundingMineEntity(ModEntityTypes.BOUNDING_MINE.get(), level);
        mine.setPos(from.x, from.y, from.z);
        // A touch of scatter, so two charges going off together do not rise as one object.
        mine.setDeltaMovement(
                (level.random.nextDouble() - 0.5D) * 0.04D,
                POP_SPEED,
                (level.random.nextDouble() - 0.5D) * 0.04D);
        return mine;
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
    }

    @Override
    public void tick() {
        this.baseTick();

        Vec3 motion = this.getDeltaMovement().subtract(0.0D, GRAVITY, 0.0D);
        this.setDeltaMovement(motion);
        this.move(MoverType.SELF, motion);

        if (this.level().isClientSide) {
            this.trail();
            return;
        }
        if (this.burst) {
            return;
        }

        // The top of the arc is what it was aimed for; a low ceiling that stops it early
        // counts just the same, or the charge would sit against it doing nothing.
        boolean stalled = motion.y <= 0.0D || this.verticalCollision;
        if (this.hang < 0 && stalled) {
            this.hang = 0;
        }
        if (this.hang >= 0 && ++this.hang >= HANG_TICKS) {
            this.detonate();
            return;
        }
        if (this.tickCount > MAX_LIFETIME) {
            this.detonate();
        }
    }

    /** A thin smoke thread from the propelling charge, so the rise is visible. */
    private void trail() {
        if (this.tickCount % 2 != 0) {
            return;
        }
        this.level().addParticle(ParticleTypes.SMOKE, true,
                this.getX(), this.getY(), this.getZ(),
                (this.random.nextDouble() - 0.5D) * 0.02D, -0.01D,
                (this.random.nextDouble() - 0.5D) * 0.02D);
    }

    private void detonate() {
        if (this.burst || !(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.burst = true;
        // Bursting where it hangs, not where it was buried, is the entire reason this
        // charge exists: the fragments arrive at chest height and reach over cover.
        MineExplosionHandler.detonateSmallShrapnel(
                server, null, MineDamageSource.create(server, MineType.BOUNDING),
                this.position(), MineType.BOUNDING.entityBlastPower);
        this.discard();
    }

    /** Cut down mid-flight: a shot charge still goes off, just early. */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (!this.level().isClientSide) {
            this.detonate();
        }
        return true;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(0.3f, 0.4f);
    }

    @Override
    public boolean isPickable() {
        return !this.burst;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** The pop that throws it, played wherever it happens to leave the ground from. */
    public void playPop() {
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.BLOCKS, 1.1f, 0.62f);
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.CHICKEN_EGG, SoundSource.BLOCKS, 0.9f, 0.75f);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.hang = tag.getInt("Hang");
        this.burst = tag.getBoolean("Burst");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Hang", this.hang);
        tag.putBoolean("Burst", this.burst);
    }
}
