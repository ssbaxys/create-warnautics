package com.cbc_more_content.entity;

import com.cbc_more_content.registry.ModEntityTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Cosmetic, bounded wreckage. The server resolves collisions; clients only interpolate. */
public class BlastDebrisEntity extends Entity {
    private static final EntityDataAccessor<BlockState> BLOCK_STATE =
            SynchedEntityData.defineId(BlastDebrisEntity.class, EntityDataSerializers.BLOCK_STATE);
    private static final EntityDataAccessor<Long> REST_TIME =
            SynchedEntityData.defineId(BlastDebrisEntity.class, EntityDataSerializers.LONG);
    public static final int HOLD_TICKS = 20;
    public static final int MELT_TICKS = 44;
    public static final int MAX_LIFETIME = 180;
    private int impacts;
    private int quietTicks;
    private int lerpSteps;
    private Vec3 lerpTarget = Vec3.ZERO;
    private float spinAge;
    private float previousSpinAge;
    private Shape shape;
    private int shapeId = Integer.MIN_VALUE;

    public BlastDebrisEntity(EntityType<? extends BlastDebrisEntity> type, Level level) {
        super(type, level);
    }

    public static BlastDebrisEntity create(ServerLevel level, BlockState state, Vec3 pos, Vec3 velocity) {
        return create((Level) level, state, pos, velocity);
    }

    /** Also used by the isolated Ponder world; no entity is inserted or networked here. */
    public static BlastDebrisEntity create(Level level, BlockState state, Vec3 pos, Vec3 velocity) {
        var debris = new BlastDebrisEntity(ModEntityTypes.BLAST_DEBRIS.get(), level);
        debris.entityData.set(BLOCK_STATE, state);
        debris.setPos(pos);
        debris.setDeltaMovement(
                velocity.lengthSqr() > 3.24 ? velocity.normalize().scale(1.8) : velocity);
        return debris;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(BLOCK_STATE, Blocks.STONE.defaultBlockState());
        builder.define(REST_TIME, -1L);
    }

    public BlockState blockState() {
        return entityData.get(BLOCK_STATE);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (BLOCK_STATE.equals(key)) {
            shape = null;
        }
    }

    public boolean isSettled() {
        return entityData.get(REST_TIME) >= 0;
    }

    public float restingAge(float partial) {
        return isSettled() ? Math.max(0, level().getGameTime() - entityData.get(REST_TIME) + partial) : 0;
    }

    public float meltProgress(float partial) {
        return Math.max(
                Mth.clamp((restingAge(partial) - HOLD_TICKS) / MELT_TICKS, 0, 1),
                Mth.clamp((tickCount + partial - (MAX_LIFETIME - 24f)) / 24f, 0, 1));
    }

    public float spinAge(float partial) {
        return Mth.lerp(partial, previousSpinAge, spinAge);
    }

    /** Cached once per piece, rather than creating RNGs and arrays on every rendered frame. */
    public Shape shape() {
        if (shape == null || shapeId != getId()) {
            shapeId = getId();
            var random = RandomSource.create(getId() * 104729L);
            int material = material(blockState());
            float size = .18f + random.nextFloat() * .30f;
            shape = new Shape(
                    material,
                    material == 1 ? size * 1.55f : size,
                    material == 1 ? size * .32f : material == 2 ? size * .20f : size * .72f,
                    material == 1 ? size * .4f : size * (.65f + random.nextFloat() * .35f),
                    (random.nextFloat() - .5f) * 32,
                    (random.nextFloat() - .5f) * 25,
                    (random.nextFloat() - .5f) * 28,
                    random.nextFloat() * 360,
                    random.nextLong());
        }
        return shape;
    }

    private static int material(BlockState state) {
        if (state.is(BlockTags.LOGS)
                || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_SLABS)) {
            return 1;
        }
        var sound = state.getSoundType();
        if (sound == SoundType.METAL
                || sound == SoundType.COPPER
                || sound == SoundType.CHAIN
                || sound == SoundType.NETHERITE_BLOCK) {
            return 2;
        }
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL) ? 3 : 0;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        lerpTarget = new Vec3(x, y, z);
        lerpSteps = Math.clamp(steps, 1, 3);
    }

    @Override
    public void tick() {
        baseTick();
        previousSpinAge = spinAge;
        if (!isSettled()) {
            spinAge += 1;
        }
        if (level().isClientSide) {
            if (lerpSteps > 0) {
                setPos(position().lerp(lerpTarget, 1.0 / lerpSteps--));
            }
            return;
        }
        if (tickCount >= MAX_LIFETIME || meltProgress(0) >= 1 || getY() < level().getMinBuildHeight() - 16) {
            discard();
            return;
        }
        if (isSettled()) {
            return;
        } // Sleeping fragments perform no collision queries.
        boolean water = isInWater();
        Vec3 incoming = getDeltaMovement().add(0, water ? -.012 : -.05, 0).scale(water ? .78 : .985);
        Vec3 before = position();
        setDeltaMovement(incoming);
        move(MoverType.SELF, incoming);
        Vec3 travelled = position().subtract(before);
        boolean xHit = Math.abs(travelled.x - incoming.x) > 1.0e-5;
        boolean yHit = Math.abs(travelled.y - incoming.y) > 1.0e-5;
        boolean zHit = Math.abs(travelled.z - incoming.z) > 1.0e-5;
        double restitution =
                switch (shape().material) {
                    case 1 -> .28;
                    case 2 -> .42;
                    case 3 -> .10;
                    default -> .34;
                };
        if (water) {
            restitution *= .4;
        }
        // move() zeroes blocked motion. Reflect the saved incoming velocity, not that zero.
        double vx = xHit ? -incoming.x * restitution : incoming.x;
        double vy = yHit ? -incoming.y * restitution : incoming.y;
        double vz = zHit ? -incoming.z * restitution : incoming.z;
        if (xHit || yHit || zHit) {
            impacts++;
            if (yHit && incoming.y < 0) {
                vx *= .67;
                vz *= .67;
                if (Math.abs(vy) < .055 || impacts > 5) {
                    vy = 0;
                }
            } else {
                vy *= .8;
            }
        }
        setDeltaMovement(vx, vy, vz);
        if (onGround() && vx * vx + vz * vz < .0025 && Math.abs(vy) < .055) {
            if (++quietTicks >= 3) {
                setDeltaMovement(Vec3.ZERO);
                entityData.set(REST_TIME, level().getGameTime());
            }
        } else {
            quietTicks = 0;
        }
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
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 96 * 96;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(
                BLOCK_STATE,
                NbtUtils.readBlockState(
                        level().holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                        tag.getCompound("BlockState")));
        entityData.set(
                REST_TIME,
                tag.contains("RestTime")
                        ? tag.getLong("RestTime")
                        : tag.getBoolean("Settled") ? level().getGameTime() : -1);
        impacts = tag.getInt("Impacts");
        tickCount = tag.getInt("Age");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("BlockState", NbtUtils.writeBlockState(blockState()));
        tag.putLong("RestTime", entityData.get(REST_TIME));
        tag.putInt("Impacts", impacts);
        tag.putInt("Age", tickCount);
    }

    public record Shape(
            int material,
            float width,
            float height,
            float depth,
            float spinX,
            float spinY,
            float spinZ,
            float heading,
            long seed) {}
}
