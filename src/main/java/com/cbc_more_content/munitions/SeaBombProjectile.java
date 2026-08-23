package com.cbc_more_content.munitions;

import com.cbc_more_content.registry.ModSounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;

/**
 * Sea bomb: air/ground impact detonates normally (power between small & medium).
 * Hitting water (world or Sable plot) arms the propeller — swims ~800 blocks with
 * buzz + splash, then sinks and detonates underwater with a heavy splash plume.
 * Solid blocks always stop it (clip collision) — no phasing through walls.
 */
public class SeaBombProjectile extends DropBombProjectile {
    public static final byte PHASE_AIR = 0;
    public static final byte PHASE_SWIM = 1;
    public static final byte PHASE_SINK = 2;

    private static final EntityDataAccessor<Byte> PHASE =
            SynchedEntityData.defineId(SeaBombProjectile.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> PROPELLER =
            SynchedEntityData.defineId(SeaBombProjectile.class, EntityDataSerializers.FLOAT);

    private static final double SWIM_RANGE = 800.0D;
    private static final double SWIM_SPEED = 1.15D;
    /** Underwater blast — nearly full power (was intentionally soft before). */
    private static final float UNDERWATER_BLOCK_POWER = 0.85f;
    private static final float UNDERWATER_ENTITY_POWER = 1.25f;

    private double swimDistance;
    private int sinkTicks;
    private int buzzCooldown;
    private Vec3 swimHeading = new Vec3(0.0D, 0.0D, 1.0D);

    public SeaBombProjectile(EntityType<? extends DropBombProjectile> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(PHASE, PHASE_AIR);
        builder.define(PROPELLER, 0.0f);
    }

    public byte phase() {
        return this.entityData.get(PHASE);
    }

    public float propellerAngle() {
        return this.entityData.get(PROPELLER);
    }

    /**
     * Smooth client-side spin — does not depend on entity-data packet cadence.
     */
    public float clientPropellerDegrees(float partialTick, float degPerTick) {
        return (this.tickCount + partialTick) * degPerTick;
    }

    private void setPhase(byte phase) {
        this.entityData.set(PHASE, phase);
    }

    private boolean isWaterFluid(FluidState fluid) {
        return !fluid.isEmpty() && fluid.is(FluidTags.WATER);
    }

    private boolean touchingWater() {
        return this.isInWater() || this.isWaterFluid(this.level().getFluidState(this.blockPosition()));
    }

    private boolean isSolidObstacle(BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return false;
        }
        // Pure water / bubble columns are not obstacles.
        if (this.isWaterFluid(state.getFluidState()) && state.getCollisionShape(this.level(), pos).isEmpty()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(this.level(), pos);
        return !shape.isEmpty();
    }

    /**
     * Move by {@code motion}; if a solid collider is in the way, detonate and return true.
     */
    private boolean moveOrDetonate(Vec3 motion, float blockPower, float entityPower) {
        Vec3 start = this.position();
        Vec3 end = start.add(motion);

        BlockHitResult hit = this.level().clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));

        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = hit.getBlockPos();
            BlockState hitState = this.level().getBlockState(hitPos);
            if (this.isSolidObstacle(hitPos, hitState)) {
                this.forceDetonate(hit.getLocation(), blockPower, entityPower);
                return true;
            }
        }

        // Safety: destination cell itself is solid (clip miss on thin edges / already overlapping).
        BlockPos dest = BlockPos.containing(end.x, end.y, end.z);
        BlockState destState = this.level().getBlockState(dest);
        if (this.isSolidObstacle(dest, destState)) {
            this.forceDetonate(end, blockPower, entityPower);
            return true;
        }

        this.xo = start.x;
        this.yo = start.y;
        this.zo = start.z;
        this.setPos(end);
        return false;
    }

    @Override
    protected void forceDetonate(Vec3 position, float blockPowerScale, float entityPowerScale) {
        boolean underwater = this.isWaterBlastSite(position)
                || this.phase() == PHASE_SWIM
                || this.phase() == PHASE_SINK;
        if (underwater && this.level() instanceof ServerLevel server && !this.level().isClientSide) {
            // Projectile-into-water style plume before the HE blast.
            spawnUnderwaterDetonationSplash(server, position);
            blockPowerScale = Math.max(blockPowerScale, UNDERWATER_BLOCK_POWER);
            entityPowerScale = Math.max(entityPowerScale, UNDERWATER_ENTITY_POWER);
        }
        super.forceDetonate(position, blockPowerScale, entityPowerScale);
    }

    private boolean isWaterBlastSite(Vec3 position) {
        BlockPos pos = BlockPos.containing(position);
        if (this.isWaterFluid(this.level().getFluidState(pos))) {
            return true;
        }
        return this.isWaterFluid(this.level().getFluidState(pos.above()))
                || this.isWaterFluid(this.level().getFluidState(pos.below()));
    }

    /**
     * Big water-impact plume — like a shell skipping into the surface, then the depth charge.
     */
    private static void spawnUnderwaterDetonationSplash(ServerLevel server, Vec3 at) {
        int surfaceY = BlockPos.containing(at).getY();
        for (int i = 0; i < 24; i++) {
            BlockPos sample = BlockPos.containing(at.x, surfaceY + 1, at.z);
            if (!server.getFluidState(sample).is(FluidTags.WATER)) {
                break;
            }
            surfaceY++;
        }
        Vec3 surface = new Vec3(at.x, surfaceY + 0.92D, at.z);

        // Surface geyser / spray
        emit(server, ParticleTypes.SPLASH, surface.x, surface.y, surface.z, 90, 1.4D, 0.9D, 1.4D, 0.45D);
        emit(server, ParticleTypes.FISHING, surface.x, surface.y, surface.z, 40, 1.1D, 0.4D, 1.1D, 0.18D);
        emit(server, ParticleTypes.CLOUD, at.x, at.y + 0.8D, at.z, 24, 0.9D, 0.35D, 0.9D, 0.08D);
        // Underwater bubble sphere
        emit(server, ParticleTypes.BUBBLE, at.x, at.y, at.z, 100, 1.6D, 1.2D, 1.6D, 0.35D);
        emit(server, ParticleTypes.BUBBLE_COLUMN_UP, at.x, at.y - 0.2D, at.z, 50, 1.0D, 0.8D, 1.0D, 0.2D);
        emit(server, ParticleTypes.BUBBLE_POP, at.x, at.y + 0.4D, at.z, 35, 1.2D, 0.7D, 1.2D, 0.05D);
        emit(server, ParticleTypes.CURRENT_DOWN, at.x, at.y + 0.6D, at.z, 20, 0.8D, 0.3D, 0.8D, 0.06D);
        emit(server, ParticleTypes.UNDERWATER, at.x, at.y, at.z, 40, 1.5D, 1.0D, 1.5D, 0.0D);

        server.playSound(null, at.x, at.y, at.z, ModSounds.SEA_BOMB_SPLASH.get(), SoundSource.BLOCKS, 1.6f, 0.7f);
        server.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 1.4f, 0.65f);
        server.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.BLOCKS, 1.1f, 0.55f);
        server.playSound(null, at.x, at.y, at.z, SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, SoundSource.BLOCKS, 1.0f, 0.5f);

        // A depth-charge pressure ring travels through the water first, then
        // reaches the surface as a widening broken splash ring. The delayed
        // ticks keep one deep detonation from flooding a single network packet.
        spawnWaterShockwaveRing(server, at, 1.8D, 20, false);
        spawnWaterShockwaveRing(server, surface, 2.4D, 24, true);
        int now = server.getServer().getTickCount();
        server.getServer().tell(new TickTask(now + 3,
                () -> spawnWaterShockwaveRing(server, at, 4.2D, 28, false)));
        server.getServer().tell(new TickTask(now + 7,
                () -> spawnWaterShockwaveRing(server, surface, 6.8D, 34, true)));
    }

    private static void spawnWaterShockwaveRing(
            ServerLevel server,
            Vec3 center,
            double radius,
            int sectors,
            boolean surface) {
        int count = Math.max(12, sectors);
        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2.0D * i) / count;
            double x = center.x + Math.cos(angle) * radius;
            double z = center.z + Math.sin(angle) * radius;
            double y = surface ? center.y + 0.05D : center.y;
            if (surface) {
                emit(server, ParticleTypes.SPLASH, x, y, z,
                        1, 0.0D, 0.10D, 0.0D, 0.24D);
                if ((i & 1) == 0) {
                    emit(server, ParticleTypes.FISHING, x, y + 0.15D, z,
                            1, 0.0D, 0.03D, 0.0D, 0.08D);
                }
            } else {
                emit(server, ParticleTypes.BUBBLE_COLUMN_UP, x, y, z,
                        1, 0.02D, 0.08D, 0.02D, 0.07D);
                if ((i & 3) == 0) {
                    emit(server, ParticleTypes.BUBBLE_POP, x, y, z,
                            1, 0.02D, 0.02D, 0.02D, 0.0D);
                }
            }
        }
    }

    @Override
    protected boolean onImpact(HitResult hitResult, ImpactResult impactResult, ProjectileContext projectileContext) {
        if (this.level().isClientSide || this.isRemoved()) {
            return false;
        }
        byte phase = this.phase();
        if (phase == PHASE_SWIM || phase == PHASE_SINK) {
            if (impactResult.kinematics() != ImpactResult.KinematicOutcome.BOUNCE) {
                this.forceDetonate(hitResult.getLocation(), UNDERWATER_BLOCK_POWER, UNDERWATER_ENTITY_POWER);
                return true;
            }
            return false;
        }
        return super.onImpact(hitResult, impactResult, projectileContext);
    }

    @Override
    protected boolean onImpactFluid(
            ProjectileContext projectileContext,
            BlockState blockState,
            FluidState fluidState,
            Vec3 impactPos,
            BlockHitResult fluidHitResult) {
        if (this.isWaterFluid(fluidState) && this.phase() == PHASE_AIR) {
            this.enterSwim(impactPos);
            return false;
        }
        return super.onImpactFluid(projectileContext, blockState, fluidState, impactPos, fluidHitResult);
    }

    @Override
    public void tick() {
        // Swim/sink skip AbstractCannonProjectile.tick(), so honour CBC's removeNextTick here.
        if (this.removeNextTick) {
            this.discard();
            return;
        }

        byte phase = this.phase();
        if (phase == PHASE_SWIM) {
            this.tickSwim();
            return;
        }
        if (phase == PHASE_SINK) {
            this.tickSink();
            return;
        }

        super.tick();
        if (!this.level().isClientSide && !this.isRemoved() && this.phase() == PHASE_AIR && this.touchingWater()) {
            this.enterSwim(this.position());
        }
    }

    private void enterSwim(Vec3 at) {
        if (this.phase() != PHASE_AIR) {
            return;
        }
        this.setPhase(PHASE_SWIM);
        this.swimDistance = 0.0D;
        this.setInGround(false);

        Vec3 vel = this.getDeltaMovement();
        Vec3 flat = new Vec3(vel.x, 0.0D, vel.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            Vec3 orient = this.getOrientation();
            flat = new Vec3(orient.x, 0.0D, orient.z);
        }
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(0.0D, 0.0D, 1.0D);
        }
        this.swimHeading = flat.normalize();
        this.setOrientation(this.swimHeading);
        this.setDeltaMovement(this.swimHeading.scale(SWIM_SPEED));
        // Nudge slightly into the water so we don't instantly re-clip the surface block.
        this.setPos(at.add(this.swimHeading.scale(0.05D)));

        if (this.level() instanceof ServerLevel server) {
            this.spawnEntrySplash(server, at);
        }
    }

    private void spawnEntrySplash(ServerLevel server, Vec3 at) {
        emit(server, ParticleTypes.SPLASH, at.x, at.y + 0.25D, at.z, 48, 0.65D, 0.35D, 0.65D, 0.22D);
        emit(server, ParticleTypes.FISHING, at.x, at.y + 0.15D, at.z, 20, 0.5D, 0.15D, 0.5D, 0.08D);
        emit(server, ParticleTypes.BUBBLE, at.x, at.y - 0.1D, at.z, 40, 0.45D, 0.4D, 0.45D, 0.14D);
        emit(server, ParticleTypes.BUBBLE_COLUMN_UP, at.x, at.y - 0.2D, at.z, 18, 0.3D, 0.25D, 0.3D, 0.05D);
        emit(server, ParticleTypes.CLOUD, at.x, at.y + 0.35D, at.z, 10, 0.4D, 0.1D, 0.4D, 0.02D);
        server.playSound(null, at.x, at.y, at.z, ModSounds.SEA_BOMB_SPLASH.get(), SoundSource.NEUTRAL, 1.25f, 0.85f);
        server.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 0.9f, 0.75f);
        server.playSound(null, at.x, at.y, at.z, SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_INSIDE, SoundSource.NEUTRAL, 0.7f, 1.1f);
    }

    private void tickSwim() {
        if (this.level().isClientSide) {
            this.clientPropellerFx();
            return;
        }

        if (!this.touchingWater()) {
            this.setPhase(PHASE_AIR);
            this.setDeltaMovement(this.swimHeading.scale(0.35D).add(0.0D, -0.15D, 0.0D));
            super.tick();
            return;
        }

        Vec3 motion = this.swimHeading.scale(SWIM_SPEED);
        this.setDeltaMovement(motion);
        this.setOrientation(this.swimHeading);

        if (this.moveOrDetonate(motion, UNDERWATER_BLOCK_POWER, UNDERWATER_ENTITY_POWER)) {
            return;
        }

        this.swimDistance += motion.horizontalDistance();
        this.entityData.set(PROPELLER, this.propellerAngle() + 48.0f);
        this.spawnSwimParticles((ServerLevel) this.level());
        this.playBuzz();

        if (this.swimDistance >= SWIM_RANGE) {
            this.enterSink();
        }
    }

    private void enterSink() {
        this.setPhase(PHASE_SINK);
        this.sinkTicks = 0;
        this.setDeltaMovement(this.swimHeading.scale(0.15D).add(0.0D, -0.08D, 0.0D));
        if (this.level() instanceof ServerLevel server) {
            Vec3 p = this.position();
            emit(server, ParticleTypes.BUBBLE_COLUMN_UP, p.x, p.y, p.z, 28, 0.35D, 0.35D, 0.35D, 0.06D);
            emit(server, ParticleTypes.BUBBLE, p.x, p.y, p.z, 36, 0.4D, 0.4D, 0.4D, 0.1D);
            emit(server, ParticleTypes.CURRENT_DOWN, p.x, p.y + 0.2D, p.z, 10, 0.25D, 0.15D, 0.25D, 0.02D);
            server.playSound(null, p.x, p.y, p.z, SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, SoundSource.NEUTRAL, 0.85f, 0.7f);
            server.playSound(null, p.x, p.y, p.z, ModSounds.SEA_BOMB_PROPELLER.get(), SoundSource.NEUTRAL, 0.4f, 0.55f);
        }
    }

    private void tickSink() {
        if (this.level().isClientSide) {
            this.clientPropellerFx();
            return;
        }

        this.sinkTicks++;
        Vec3 vel = this.getDeltaMovement();
        double vy = Math.max(-0.55D, vel.y - 0.035D);
        double drag = 0.96D;
        Vec3 motion = new Vec3(vel.x * drag, vy, vel.z * drag);
        this.setDeltaMovement(motion);

        if (this.moveOrDetonate(motion, UNDERWATER_BLOCK_POWER, UNDERWATER_ENTITY_POWER)) {
            return;
        }

        this.entityData.set(PROPELLER, this.propellerAngle() + 28.0f);

        if (this.level() instanceof ServerLevel server) {
            this.spawnSinkParticles(server);
        }

        boolean leftWater = !this.touchingWater();
        boolean timedOut = this.sinkTicks > 160;
        if (leftWater || timedOut) {
            if (leftWater) {
                this.forceDetonate(this.position(), 1.0f);
            } else {
                this.forceDetonate(this.position(), UNDERWATER_BLOCK_POWER, UNDERWATER_ENTITY_POWER);
            }
        }
    }

    /**
     * The torpedo parting the water: bubbles seeded ahead of the nose with sideways
     * velocity to spread into a widening V, a sheet along each flank, and cavitation
     * filling in behind the screw. The outward velocity is what reads as displaced
     * water rather than a trail hanging where the torpedo used to be.
     */
    private void spawnSwimParticles(ServerLevel server) {
        Vec3 p = this.position();
        Vec3 heading = this.swimHeading;
        Vec3 side = new Vec3(-heading.z, 0.0D, heading.x);
        Vec3 nose = p.add(heading.scale(0.55D));
        Vec3 tail = p.subtract(heading.scale(0.6D));
        Vec3 wake = p.subtract(heading.scale(1.15D));
        float ang = this.propellerAngle() * Mth.DEG_TO_RAD;
        RandomSource random = this.random;

        // Bow wave: water shouldered aside at the nose, thrown out and slightly back.
        for (int s = -1; s <= 1; s += 2) {
            double spread = 0.22D + random.nextDouble() * 0.12D;
            Vec3 from = nose.add(side.scale(s * spread));
            Vec3 push = side.scale(s * 0.16D).subtract(heading.scale(0.04D));
            emit(server, ParticleTypes.BUBBLE,
                    from.x, from.y, from.z, 0, push.x, push.y + 0.02D, push.z, 1.0D);
            if ((this.tickCount & 1) == 0) {
                emit(server, ParticleTypes.BUBBLE,
                        from.x, from.y - 0.12D, from.z, 0,
                        push.x * 1.4D, 0.01D, push.z * 1.4D, 1.0D);
            }
        }

        // Hull sheet: a thin curtain of bubbles sliding down each flank.
        if ((this.tickCount % 2) == 0) {
            for (int s = -1; s <= 1; s += 2) {
                Vec3 flank = p.add(side.scale(s * 0.34D));
                emit(server, ParticleTypes.BUBBLE,
                        flank.x, flank.y, flank.z, 2, 0.06D, 0.10D, 0.06D, 0.02D);
                emit(server, ParticleTypes.CURRENT_DOWN,
                        flank.x, flank.y + 0.05D, flank.z, 1, 0.05D, 0.06D, 0.05D, 0.01D);
            }
        }

        // Screw cavitation: a tight helix of bubbles torn off the blades.
        double blurR = 0.42D;
        for (int i = 0; i < 4; i++) {
            double a = ang + (Math.PI * 2.0D * i / 4.0D);
            Vec3 offset = side.scale(Math.cos(a) * blurR);
            double px = tail.x + offset.x;
            double py = tail.y + Math.sin(a) * blurR * 0.6D;
            double pz = tail.z + offset.z;
            emit(server, ParticleTypes.BUBBLE, px, py, pz, 1, 0.02D, 0.02D, 0.02D, 0.015D);
            if ((i & 1) == 0) {
                emit(server, ParticleTypes.BUBBLE_POP, px, py, pz, 1, 0.02D, 0.02D, 0.02D, 0.0D);
            }
        }

        // Collapse zone: the parted water folding back in behind the screw.
        emit(server, ParticleTypes.BUBBLE, wake.x, wake.y, wake.z, 7, 0.20D, 0.14D, 0.20D, 0.05D);
        emit(server, ParticleTypes.BUBBLE_COLUMN_UP,
                wake.x, wake.y - 0.05D, wake.z, 4, 0.14D, 0.10D, 0.14D, 0.03D);

        // Surface chop / spray when near the waterline.
        if ((this.tickCount % 2) == 0 && this.nearSurface()) {
            emit(server, ParticleTypes.SPLASH, p.x, p.y + 0.35D, p.z, 8, 0.38D, 0.08D, 0.38D, 0.07D);
            emit(server, ParticleTypes.FISHING, p.x, p.y + 0.25D, p.z, 3, 0.3D, 0.05D, 0.3D, 0.03D);
            // Surface V: foam thrown out to either side of the track.
            for (int s = -1; s <= 1; s += 2) {
                Vec3 crest = p.add(side.scale(s * 0.5D)).subtract(heading.scale(0.3D));
                emit(server, ParticleTypes.SPLASH,
                        crest.x, crest.y + 0.4D, crest.z, 0,
                        side.x * s * 0.12D, 0.06D, side.z * s * 0.12D, 1.0D);
            }
            if (random.nextInt(4) == 0) {
                emit(server, ParticleTypes.CLOUD, p.x, p.y + 0.4D, p.z, 2, 0.25D, 0.04D, 0.25D, 0.01D);
            }
        }

        // Occasional deeper turbulence puffs.
        if ((this.tickCount % 5) == 0) {
            emit(server, ParticleTypes.UNDERWATER, p.x, p.y, p.z, 8, 0.4D, 0.25D, 0.4D, 0.0D);
        }
    }

    private void spawnSinkParticles(ServerLevel server) {
        Vec3 p = this.position();
        if ((this.sinkTicks % 2) == 0) {
            emit(server, ParticleTypes.BUBBLE, p.x, p.y, p.z, 8, 0.2D, 0.2D, 0.2D, 0.05D);
            emit(server, ParticleTypes.BUBBLE_COLUMN_UP, p.x, p.y - 0.1D, p.z, 4, 0.15D, 0.15D, 0.15D, 0.03D);
        }
        if ((this.sinkTicks % 4) == 0) {
            emit(server, ParticleTypes.CURRENT_DOWN, p.x, p.y + 0.3D, p.z, 3, 0.18D, 0.1D, 0.18D, 0.02D);
            emit(server, ParticleTypes.BUBBLE_POP, p.x, p.y + 0.15D, p.z, 2, 0.1D, 0.1D, 0.1D, 0.0D);
        }
        if ((this.sinkTicks % 12) == 0) {
            server.playSound(null, p.x, p.y, p.z, SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundSource.NEUTRAL, 0.35f, 0.8f);
        }
    }

    /** True when there is air (or no water) within ~1 block above — surface wake. */
    private boolean nearSurface() {
        BlockPos above = BlockPos.containing(this.getX(), this.getY() + 1.0D, this.getZ());
        FluidState fluid = this.level().getFluidState(above);
        return fluid.isEmpty() || fluid.getAmount() < 8;
    }

    private void playBuzz() {
        if (--this.buzzCooldown > 0) {
            return;
        }
        this.buzzCooldown = 4;
        Vec3 p = this.position();
        float pitch = 0.8f + (this.level().random.nextFloat() * 0.35f);
        this.level().playSound(null, p.x, p.y, p.z, ModSounds.SEA_BOMB_PROPELLER.get(), SoundSource.NEUTRAL, 0.7f, pitch);
        if ((this.tickCount % 8) == 0) {
            this.level().playSound(null, p.x, p.y, p.z, SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundSource.NEUTRAL, 0.25f, 1.2f);
        }
        if ((this.tickCount % 10) == 0) {
            this.level().playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_SWIM, SoundSource.NEUTRAL, 0.2f, 0.6f + this.random.nextFloat() * 0.2f);
        }
    }

    private void clientPropellerFx() {
        if (this.phase() != PHASE_SWIM && this.phase() != PHASE_SINK) {
            return;
        }
        Vec3 p = this.position();
        Vec3 heading = this.getOrientation();
        if (heading.lengthSqr() < 1.0E-6D) {
            heading = this.getDeltaMovement();
        }
        if (heading.lengthSqr() < 1.0E-6D) {
            heading = new Vec3(0.0D, 0.0D, 1.0D);
        }
        heading = heading.normalize();
        Vec3 tail = p.subtract(heading.scale(0.55D));

        float ang = this.clientPropellerDegrees(0.0f, this.phase() == PHASE_SWIM ? 72.0f : 28.0f) * Mth.DEG_TO_RAD;
        int blades = this.phase() == PHASE_SWIM ? 4 : 2;
        for (int i = 0; i < blades; i++) {
            double a = ang + (Math.PI * 2.0D * i / blades);
            double r = 0.38D;
            this.level().addParticle(ParticleTypes.BUBBLE,
                    tail.x + Math.cos(a) * r,
                    tail.y + Math.sin(a * 2.0D) * 0.06D,
                    tail.z + Math.sin(a) * r,
                    -heading.x * 0.05D, 0.02D, -heading.z * 0.05D);
        }

        // Continuous wake on client for smoothness between server packets.
        if (this.phase() == PHASE_SWIM) {
            Vec3 side = new Vec3(-heading.z, 0.0D, heading.x);
            Vec3 wake = p.subtract(heading.scale(0.9D));
            this.level().addParticle(ParticleTypes.BUBBLE, wake.x, wake.y, wake.z,
                    -heading.x * 0.08D, 0.01D, -heading.z * 0.08D);
            this.level().addParticle(ParticleTypes.BUBBLE_COLUMN_UP,
                    wake.x + (this.random.nextDouble() - 0.5D) * 0.2D,
                    wake.y,
                    wake.z + (this.random.nextDouble() - 0.5D) * 0.2D,
                    0.0D, 0.04D, 0.0D);

            // Bow spray, thrown outward so the parted water is visible every frame
            // rather than only on the ticks the server happens to send.
            Vec3 nose = p.add(heading.scale(0.5D));
            for (int s = -1; s <= 1; s += 2) {
                Vec3 from = nose.add(side.scale(s * 0.26D));
                this.level().addParticle(ParticleTypes.BUBBLE,
                        from.x, from.y, from.z,
                        side.x * s * 0.14D, 0.02D, side.z * s * 0.14D);
            }

            if ((this.tickCount & 1) == 0) {
                this.level().addParticle(ParticleTypes.SPLASH, p.x, p.y + 0.25D, p.z,
                        (this.random.nextDouble() - 0.5D) * 0.15D, 0.08D, (this.random.nextDouble() - 0.5D) * 0.15D);
            }
            if ((this.tickCount % 3) == 0) {
                this.level().addParticle(ParticleTypes.BUBBLE_POP,
                        tail.x, tail.y, tail.z, 0.0D, 0.02D, 0.0D);
            }
        } else {
            this.level().addParticle(ParticleTypes.BUBBLE, p.x, p.y, p.z,
                    (this.random.nextDouble() - 0.5D) * 0.05D, 0.06D, (this.random.nextDouble() - 0.5D) * 0.05D);
            if ((this.tickCount % 2) == 0) {
                this.level().addParticle(ParticleTypes.CURRENT_DOWN, p.x, p.y + 0.2D, p.z, 0.0D, -0.05D, 0.0D);
            }
        }
    }

    /**
     * Sends particles with the long-distance flag set. The no-player overload of
     * {@code ServerLevel#sendParticles} hardcodes {@code longDistance = false} and so
     * drops every packet past 32 blocks — invisible for a torpedo that runs 800.
     */
    private static <T extends net.minecraft.core.particles.ParticleOptions> void emit(
            ServerLevel server, T type, double x, double y, double z,
            int count, double dx, double dy, double dz, double speed) {
        for (net.minecraft.server.level.ServerPlayer player : server.players()) {
            if (player.distanceToSqr(x, y, z) <= 192.0D * 192.0D) {
                server.sendParticles(player, type, true, x, y, z, count, dx, dy, dz, speed);
            }
        }
    }
}
