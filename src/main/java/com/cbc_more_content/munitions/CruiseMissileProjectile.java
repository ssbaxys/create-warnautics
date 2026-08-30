package com.cbc_more_content.munitions;

import com.cbc_more_content.block.CruiseMissileBlockEntity.Guidance;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.registry.ModParticles;
import com.cbc_more_content.registry.ModSounds;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

public class CruiseMissileProjectile extends Entity {
    public static final int FUEL_TICKS = 190;

    private static final double CRUISE_SPEED = 1.4D;
    private static final double GRAVITY = 0.085D;
    private static final double DRAG = 0.95D;
    private static final int ARMING_TICKS = 4;

    private static final TicketType<Long> MISSILE_TICKET = TicketType.create("cruise_missile", Long::compareTo);
    private static final int CHUNK_TICKET_RADIUS = 2;
    private static final int CLIENT_TRACKING_RADIUS_BLOCKS = 4096;

    private static final EntityDataAccessor<Boolean> POWERED =
            SynchedEntityData.defineId(CruiseMissileProjectile.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> EJECTING =
            SynchedEntityData.defineId(CruiseMissileProjectile.class, EntityDataSerializers.BOOLEAN);

    public static final int EJECT_TICKS = 14;
    public static final double EJECT_SPEED = 0.62D;
    private static final double EJECT_GRAVITY = 0.03D;

    private static final double TURN_RATE = 0.055D;
    private static final double LOOKAHEAD = 17.0D;
    private static final double TERMINAL_RANGE = 32.0D;
    private static final double TERMINAL_TURN_RATE = 0.2D;
    private static final double FUSE_RANGE = 2.4D;
    private static final double TERMINAL_BOOST = 1.45D;
    private static final double JINK_DOT = 0.55D;
    private static final float JINK_CHANCE = 0.4f;
    private static final int SHAKEN_TICKS = 26;

    private static final float BLOCK_POWER = BombSize.LARGE.blockBlastPower * 1.3f;

    private static final float ENTITY_POWER = BombSize.LARGE.entityBlastPower * 1.6f;
    private static final double SCUFF_RADIUS = BLOCK_POWER * 2.1D;

    private int fuel = FUEL_TICKS;
    private boolean detonated;
    private boolean waterEntered;
    private double closestApproach = Double.MAX_VALUE;
    private boolean closing;

    private double lastRange = Double.MAX_VALUE;
    private int shaken;

    @Nullable
    private Vec3 lastAim;

    @Nullable
    private Vec3 lastAimDrift;

    private int ejecting;
    private final MissileTargetingState targeting = new MissileTargetingState();

    public CruiseMissileProjectile(EntityType<? extends CruiseMissileProjectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(POWERED, true);
        builder.define(EJECTING, false);
    }

    public boolean isPowered() {
        return this.entityData.get(POWERED);
    }

    public void setController(@Nullable BlockPos controller) {
        this.targeting.setController(controller);
    }

    public void setGuidance(Guidance guidance, @Nullable BlockPos target, int lockedSubLevel) {
        this.targeting.setGuidance(guidance, target, lockedSubLevel);
    }

    public boolean isEjecting() {
        return this.entityData.get(EJECTING);
    }

    public void ejectUpward() {
        this.ejecting = EJECT_TICKS;
        this.entityData.set(EJECTING, true);
        this.entityData.set(POWERED, false);
        this.setDeltaMovement(0.0D, EJECT_SPEED, 0.0D);
        this.setYRot(0.0f);
        this.setXRot(-90.0f);
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    public void launch(Vec3 heading) {
        Vec3 dir = heading.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : heading.normalize();
        this.setDeltaMovement(dir.scale(CRUISE_SPEED));
        this.setYRot((float) (Math.atan2(dir.z, dir.x) * 180.0D / Math.PI) - 90.0f);
        this.setXRot((float) (-Math.asin(dir.y) * 180.0D / Math.PI));
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            this.spawnExhaust();
            return;
        }
        if (this.detonated) {
            return;
        }

        this.refreshChunkTickets();
        this.refreshClientTracking();
        if (this.ejecting > 0) {
            this.coastOutOfRack();
            return;
        }

        if (!this.waterEntered && this.isInWater()) {
            this.waterEntered = true;
            this.entityData.set(POWERED, false);
            this.level()
                    .playSound(
                            null,
                            this.getX(),
                            this.getY(),
                            this.getZ(),
                            SoundEvents.FIRE_EXTINGUISH,
                            SoundSource.HOSTILE,
                            2.0f,
                            0.55f);
        }

        if (this.fuel > 0 && !this.waterEntered) {
            this.fuel--;
            if (this.fuel == 0) {
                this.entityData.set(POWERED, false);
                this.level()
                        .playSound(
                                null,
                                this.getX(),
                                this.getY(),
                                this.getZ(),
                                SoundEvents.FIRE_EXTINGUISH,
                                SoundSource.HOSTILE,
                                2.2f,
                                0.6f);
            }
        }

        Vec3 aim = this.aimPoint();
        this.watchForJink(aim);

        Vec3 motion = this.getDeltaMovement();
        if (this.isPowered() && !this.waterEntered) {
            motion = this.steer(motion.normalize(), aim).scale(this.speedFor(aim));
            if (this.tickCount % 4 == 0) {
                this.level()
                        .playSound(
                                null,
                                this.getX(),
                                this.getY(),
                                this.getZ(),
                                ModSounds.CRUISE_MISSILE_ENGINE.get(),
                                SoundSource.HOSTILE,
                                3.0f,
                                0.9f + this.random.nextFloat() * 0.1f);
            }
        } else {
            motion = motion.scale(DRAG).subtract(0.0D, GRAVITY, 0.0D);
        }
        this.setDeltaMovement(motion);

        Vec3 from = this.position();
        Vec3 to = from.add(motion);
        if (this.tickCount > ARMING_TICKS && this.checkImpact(from, to)) {
            return;
        }

        this.setPos(to);
        this.faceMotion(motion);
        this.fuseOnTarget(aim);
    }

    private void coastOutOfRack() {
        this.ejecting--;
        Vec3 motion = this.getDeltaMovement().subtract(0.0D, EJECT_GRAVITY, 0.0D);
        this.setDeltaMovement(motion);
        this.setPos(this.position().add(motion));
        this.faceMotion(motion);

        if (this.ejecting > 0) {
            return;
        }

        this.entityData.set(EJECTING, false);
        this.entityData.set(POWERED, true);
        Vec3 heading = motion.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 1.0D, 0.0D) : motion.normalize();
        this.setDeltaMovement(heading.scale(CRUISE_SPEED));
        this.level()
                .playSound(
                        null,
                        this.getX(),
                        this.getY(),
                        this.getZ(),
                        ModSounds.CRUISE_MISSILE_LAUNCH.get(),
                        SoundSource.HOSTILE,
                        5.0f,
                        0.72f);
    }

    private double speedFor(@Nullable Vec3 aim) {
        if (aim == null) {
            return CRUISE_SPEED;
        }
        return this.position().distanceToSqr(aim) <= TERMINAL_RANGE * TERMINAL_RANGE
                ? CRUISE_SPEED * TERMINAL_BOOST
                : CRUISE_SPEED;
    }

    private void watchForJink(@Nullable Vec3 aim) {
        if (this.shaken > 0 && --this.shaken == 0) {
            this.resetApproach();
        }
        if (aim == null) {
            this.lastAim = null;
            this.lastAimDrift = null;
            return;
        }
        if (this.lastAim != null) {
            Vec3 drift = aim.subtract(this.lastAim);
            if (this.lastAimDrift != null
                    && drift.lengthSqr() > 0.0025D
                    && this.lastAimDrift.lengthSqr() > 0.0025D
                    && this.shaken == 0
                    && drift.normalize().dot(this.lastAimDrift.normalize()) < JINK_DOT
                    && this.position().distanceToSqr(aim) <= TERMINAL_RANGE * TERMINAL_RANGE
                    && this.random.nextFloat() < JINK_CHANCE) {
                this.shaken = SHAKEN_TICKS;
                this.resetApproach();
            }
            this.lastAimDrift = drift;
        }
        this.lastAim = aim;
    }

    private void resetApproach() {
        this.closestApproach = Double.MAX_VALUE;
        this.lastRange = Double.MAX_VALUE;
        this.closing = false;
    }

    private Vec3 steer(Vec3 heading, @Nullable Vec3 aim) {
        if (aim == null) {
            return heading;
        }
        Vec3 toTarget = aim.subtract(this.position());
        double distance = toTarget.length();
        if (distance < 1.0E-4D) {
            return heading;
        }
        Vec3 wanted = toTarget.scale(1.0D / distance);
        if (distance > TERMINAL_RANGE) {
            return turnToward(heading, this.avoid(heading, wanted), TURN_RATE);
        }
        return turnToward(heading, wanted, this.shaken > 0 ? TURN_RATE : TERMINAL_TURN_RATE);
    }

    private void fuseOnTarget(@Nullable Vec3 aim) {
        if (this.detonated || aim == null || this.tickCount <= ARMING_TICKS) {
            return;
        }
        double distance = this.position().distanceTo(aim);
        if (distance > TERMINAL_RANGE) {
            this.resetApproach();
            return;
        }
        if (distance < this.lastRange - 0.01D) {
            this.closing = true;
        }
        this.lastRange = distance;
        if (this.shaken > 0) {
            return;
        }
        if (distance <= FUSE_RANGE || (this.closing && distance > this.closestApproach + 0.05D)) {
            this.detonate(this.position());
            return;
        }
        this.closestApproach = Math.min(this.closestApproach, distance);
    }

    @Nullable
    private Vec3 aimPoint() {
        if (this.targeting.guidance() == Guidance.INTERCEPT) {
            return this.radarAim();
        }
        if (this.targeting.guidance() == Guidance.LOCK
                && this.targeting.lockedSubLevel() >= 0
                && ModList.get().isLoaded("sable")
                && this.level() instanceof ServerLevel server) {
            Vec3 tracked = SableDropCompat.subLevelCentre(server, this.targeting.lockedSubLevel());
            if (tracked != null) {
                return tracked;
            }
        }
        return this.targeting.target() == null || this.targeting.guidance() == Guidance.NONE
                ? null
                : Vec3.atCenterOf(this.targeting.target());
    }

    @Nullable
    private Vec3 radarAim() {
        if (this.targeting.controller() == null || !com.cbc_more_content.compat.RadarCompat.loaded()) {
            return null;
        }
        if (this.targeting.contact() != null) {
            var held = com.cbc_more_content.compat.RadarCompat.contactById(
                    this.level(), this.targeting.controller(), this.targeting.contact());
            if (held != null) {
                return held.position();
            }
            this.targeting.setContact(null);
        }
        var settings = this.level() instanceof ServerLevel server
                ? com.cbc_more_content.radar.InterceptSettingsStore.get(server)
                        .forController(this.targeting.controller())
                : com.cbc_more_content.radar.InterceptSettings.DEFAULT;
        var fresh = com.cbc_more_content.compat.RadarCompat.bestContact(
                this.level(), this.targeting.controller(), this.position(), settings);
        if (fresh == null) {
            return null;
        }
        this.targeting.setContact(fresh.id());
        return fresh.position();
    }

    private Vec3 avoid(Vec3 heading, Vec3 wanted) {
        Vec3 from = this.position();
        BlockHitResult hit = this.level()
                .clip(new ClipContext(
                        from,
                        from.add(heading.scale(LOOKAHEAD)),
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        this));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return wanted;
        }

        double closeness = 1.0D - Math.sqrt(hit.getLocation().distanceToSqr(from)) / LOOKAHEAD;
        Vec3 lift = new Vec3(
                hit.getDirection().getStepX(),
                Math.max(0.35D, hit.getDirection().getStepY()),
                hit.getDirection().getStepZ());
        return wanted.add(lift.scale(Mth.clamp(closeness, 0.0D, 1.0D) * 1.4D)).normalize();
    }

    private static Vec3 turnToward(Vec3 from, Vec3 to, double maxRadians) {
        double angle = Math.acos(Mth.clamp(from.dot(to), -1.0D, 1.0D));
        if (angle <= maxRadians || angle < 1.0E-4D) {
            return to;
        }
        double t = maxRadians / angle;
        double sin = Math.sin(angle);
        return from.scale(Math.sin((1.0D - t) * angle) / sin)
                .add(to.scale(Math.sin(t * angle) / sin))
                .normalize();
    }

    private boolean checkImpact(Vec3 from, Vec3 to) {
        BlockHitResult block =
                this.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        Vec3 stop = block.getType() == HitResult.Type.BLOCK ? block.getLocation() : null;

        if (ModList.get().isLoaded("sable") && this.level() instanceof ServerLevel server) {
            Vec3 hull = SableDropCompat.clipSubLevels(server, from, to);
            if (hull != null && (stop == null || from.distanceToSqr(hull) < from.distanceToSqr(stop))) {
                stop = hull;
            }
        }

        AABB sweep = this.getBoundingBox().expandTowards(to.subtract(from)).inflate(0.5D);
        for (Entity entity : this.level().getEntities(this, sweep, this::canHit)) {
            EntityHitResult hit = new EntityHitResult(entity);
            Vec3 at = hit.getLocation();
            if (stop == null || from.distanceToSqr(at) < from.distanceToSqr(stop)) {
                stop = at;
            }
        }

        if (stop == null) {
            return false;
        }
        this.detonate(stop);
        return true;
    }

    private boolean canHit(Entity entity) {
        return entity.isAlive() && entity.isPickable() && !entity.isSpectator();
    }

    private void refreshClientTracking() {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.entityData.set(EJECTING, this.isEjecting());
        this.setCustomNameVisible(false);
    }

    private void refreshChunkTickets() {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        ChunkPos center = new ChunkPos(this.blockPosition());
        for (int x = -CHUNK_TICKET_RADIUS; x <= CHUNK_TICKET_RADIUS; x++) {
            for (int z = -CHUNK_TICKET_RADIUS; z <= CHUNK_TICKET_RADIUS; z++) {
                server.getChunkSource()
                        .addRegionTicket(
                                MISSILE_TICKET,
                                new ChunkPos(center.x + x, center.z + z),
                                2,
                                this.getUUID().getLeastSignificantBits());
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (this.level() instanceof ServerLevel server) {
            ChunkPos center = new ChunkPos(this.blockPosition());
            for (int x = -CHUNK_TICKET_RADIUS; x <= CHUNK_TICKET_RADIUS; x++) {
                for (int z = -CHUNK_TICKET_RADIUS; z <= CHUNK_TICKET_RADIUS; z++) {
                    server.getChunkSource()
                            .removeRegionTicket(
                                    MISSILE_TICKET,
                                    new ChunkPos(center.x + x, center.z + z),
                                    2,
                                    this.getUUID().getLeastSignificantBits());
                }
            }
        }
        super.remove(reason);
    }

    private void detonate(Vec3 at) {
        if (this.detonated || !(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.detonated = true;
        try {
            BombExplosionHandler.detonate(
                    server, this, BombDamageSource.create(server), at, BLOCK_POWER, ENTITY_POWER, BombSize.LARGE);
            com.cbc_more_content.effects.BlastScorch.scuff(server, at, SCUFF_RADIUS, 1.0f);
        } catch (Throwable t) {
            com.cbc_more_content.CBCMoreContent.LOGGER.error("Cruise missile detonation failed at {}", at, t);
        } finally {
            this.discard();
        }
    }

    private void faceMotion(Vec3 motion) {
        if (motion.lengthSqr() < 1.0E-6D) {
            return;
        }
        Vec3 dir = motion.normalize();
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
        this.setYRot((float) (Math.atan2(dir.z, dir.x) * 180.0D / Math.PI) - 90.0f);
        this.setXRot((float) (-Math.asin(dir.y) * 180.0D / Math.PI));
    }

    private void spawnExhaust() {
        float yaw = (this.getYRot() + 90.0f) * Mth.DEG_TO_RAD;
        float pitch = -this.getXRot() * Mth.DEG_TO_RAD;
        double cos = Mth.cos(pitch);
        Vec3 heading = new Vec3(Mth.cos(yaw) * cos, Mth.sin(pitch), Mth.sin(yaw) * cos);
        if (heading.lengthSqr() < 1.0E-6D) {
            return;
        }
        Vec3 back = heading.normalize().scale(-1.6D);
        Vec3 nozzle = this.position().add(back);

        if (this.isEjecting()) {
            for (int i = 0; i < 10; i++) {
                double ox = (this.random.nextDouble() - 0.5D) * 0.9D;
                double oy = (this.random.nextDouble() - 0.5D) * 0.5D;
                double oz = (this.random.nextDouble() - 0.5D) * 0.9D;
                this.level()
                        .addParticle(
                                ModParticles.MISSILE_GAS.get(),
                                true,
                                nozzle.x + ox,
                                nozzle.y + oy,
                                nozzle.z + oz,
                                ox * 0.35D,
                                -0.12D + oy * 0.2D,
                                oz * 0.35D);
            }
            return;
        }

        boolean powered = this.isPowered();

        int puffs = powered ? 5 : 1;
        for (int i = 0; i < puffs; i++) {
            double jitter = 0.12D;
            double ox = (this.random.nextDouble() - 0.5D) * jitter;
            double oy = (this.random.nextDouble() - 0.5D) * jitter;
            double oz = (this.random.nextDouble() - 0.5D) * jitter;
            if (powered) {
                this.level()
                        .addParticle(
                                ModParticles.MISSILE_EXHAUST.get(),
                                true,
                                nozzle.x + ox,
                                nozzle.y + oy,
                                nozzle.z + oz,
                                back.x * 0.10D + ox * 0.4D,
                                back.y * 0.10D + oy * 0.4D,
                                back.z * 0.10D + oz * 0.4D);
            } else {
                // A dead motor fades into the same cold-gas trail as ejection.
                this.level()
                        .addParticle(
                                ModParticles.MISSILE_GAS.get(),
                                true,
                                nozzle.x + ox * 2.0D,
                                nozzle.y + oy * 2.0D,
                                nozzle.z + oz * 2.0D,
                                back.x * 0.02D,
                                0.01D,
                                back.z * 0.02D);
            }
        }
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (!this.level().isClientSide && !this.detonated) {
            this.detonate(this.position());
        }
        return true;
    }

    @Override
    public boolean isPickable() {
        return !this.detonated;
    }
    
    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        this.setPos(x, y, z);
        this.setRot(yaw, pitch);
    }

    @Override
    public void lerpMotion(double x, double y, double z) {
        this.setDeltaMovement(x, y, z);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.fuel = tag.getInt("Fuel");
        this.entityData.set(POWERED, tag.getBoolean("Powered"));
        this.waterEntered = tag.getBoolean("WaterEntered");
        this.targeting.readFrom(tag);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Fuel", this.fuel);
        tag.putBoolean("Powered", this.isPowered());
        tag.putBoolean("WaterEntered", this.waterEntered);
        this.targeting.writeTo(tag);
    }
}
