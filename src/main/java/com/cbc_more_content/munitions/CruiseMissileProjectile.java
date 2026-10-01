package com.cbc_more_content.munitions;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.CruiseMissileBlockEntity.Guidance;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.compat.RadarCompat;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.radar.InterceptSettings;
import com.cbc_more_content.radar.InterceptSettingsStore;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

public class CruiseMissileProjectile extends Entity {
    public static final int FUEL_TICKS = 190;

    private static final double CRUISE_SPEED = 1.4D;
    private static final double GRAVITY = 0.085D;
    private static final double DRAG = 0.95D;
    private static final int ARMING_TICKS = 4;

    private static final TicketType<Long> MISSILE_TICKET = TicketType.create("cruise_missile", Long::compareTo, 20);
    private static final int CHUNK_TICKET_RADIUS = 2;

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
    private static final double JINK_DOT = 0.55D;
    private static final float JINK_CHANCE = 0.4f;
    private static final int SHAKEN_TICKS = 26;

    private static final float BLOCK_POWER = BombSize.MOAB.blockBlastPower * 0.85f;

    private static final float ENTITY_POWER = BombSize.MOAB.entityBlastPower * 0.85f;

    private int fuel = FUEL_TICKS;
    private double fuelFraction;
    private int poweredTicks;
    private MissileFlightProfile flightProfile = MissileFlightProfile.DIRECT;
    private boolean detonated;
    private boolean waterEntered;
    private boolean trackingHull;
    private int shaken;

    @Nullable
    private Vec3 lastAim;

    @Nullable
    private Vec3 lastAimDrift;

    private int ejecting;
    private Vec3 carrierVelocity = Vec3.ZERO;

    @Nullable
    private java.util.UUID salvoId;

    private int salvoIndex;
    private int salvoCount = 1;
    private Vec3 salvoOrigin = Vec3.ZERO;
    private final java.util.Set<ChunkPos> ticketChunks = new java.util.HashSet<>();

    @Nullable
    private ChunkPos ticketCenter;

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

    public void setFlightProfile(MissileFlightProfile profile) {
        this.flightProfile = profile;
    }

    public MissileFlightProfile flightProfile() {
        return this.flightProfile;
    }

    public void setSalvo(java.util.UUID id, int index, int count) {
        this.setSalvo(id, index, count, this.position());
    }

    public void setSalvo(java.util.UUID id, int index, int count, Vec3 origin) {
        this.salvoId = id;
        this.salvoIndex = Math.clamp(index, 0, 15);
        this.salvoCount = Math.clamp(count, 1, 16);
        this.salvoOrigin = origin;
    }

    public boolean isEjecting() {
        return this.entityData.get(EJECTING);
    }

    public void ejectUpward() {
        this.eject(new Vec3(0, 1, 0), Vec3.ZERO);
    }

    public void eject(Vec3 heading, Vec3 carrierVelocity) {
        this.carrierVelocity = carrierVelocity;
        this.ejecting = EJECT_TICKS;
        this.entityData.set(EJECTING, true);
        this.entityData.set(POWERED, false);
        this.setDeltaMovement(heading.normalize().scale(EJECT_SPEED).add(carrierVelocity));
        this.faceMotion(heading);
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    public void launch(Vec3 heading) {
        this.launch(heading, Vec3.ZERO);
    }

    public void launch(Vec3 heading, Vec3 carrierVelocity) {
        this.carrierVelocity = carrierVelocity;
        Vec3 dir = heading.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : heading.normalize();
        this.setDeltaMovement(dir.scale(CRUISE_SPEED).add(carrierVelocity));
        this.setYRot((float) (Math.atan2(dir.z, dir.x) * 180.0D / Math.PI) - 90.0f);
        this.setXRot((float) (-Math.asin(dir.y) * 180.0D / Math.PI));
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        if (this.detonated) {
            return;
        }

        this.refreshChunkTickets();
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

        Vec3 aim = this.aimPoint();
        if (this.fuel > 0 && !this.waterEntered) {
            this.poweredTicks++;
            double range =
                    aim == null ? Double.POSITIVE_INFINITY : this.position().distanceTo(aim);
            double plannedSpeed = this.flightProfile.speed(
                    this.poweredTicks, range, this.getUUID().getLeastSignificantBits());
            this.fuelFraction += this.flightProfile.fuelPerTick(plannedSpeed);
            int spent = (int) this.fuelFraction;
            this.fuelFraction -= spent;
            this.fuel = Math.max(0, this.fuel - spent);
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

        this.watchForJink(aim);

        Vec3 motion = this.getDeltaMovement();
        if (this.isPowered() && !this.waterEntered) {
            motion = this.steer(motion.subtract(this.carrierVelocity).normalize(), aim)
                    .scale(this.speedFor(aim))
                    .add(this.carrierVelocity);
            // Keep the ship's momentum through rack separation, then let the motor take
            // over. A permanent carrier offset distorted every selected speed profile.
            this.carrierVelocity = this.carrierVelocity.scale(0.92D);
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
        this.fuseOnTarget(from, aim);
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
        Vec3 relative = motion.subtract(this.carrierVelocity);
        Vec3 heading = relative.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 1.0D, 0.0D) : relative.normalize();
        this.setDeltaMovement(heading.scale(CRUISE_SPEED).add(this.carrierVelocity));
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
        double range = aim == null ? Double.POSITIVE_INFINITY : this.position().distanceTo(aim);
        return this.flightProfile.speed(this.poweredTicks, range, this.getUUID().getLeastSignificantBits());
    }

    private void watchForJink(@Nullable Vec3 aim) {
        if (this.shaken > 0) {
            --this.shaken;
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
            }
            this.lastAimDrift = drift;
        }
        this.lastAim = aim;
    }

    private Vec3 steer(Vec3 heading, @Nullable Vec3 aim) {
        if (aim == null) {
            return turnToward(heading, this.separate(heading), .12);
        }
        Vec3 toTarget = aim.subtract(this.position());
        double distance = toTarget.length();
        if (distance < 1.0E-4D) {
            return heading;
        }
        Vec3 shaped = this.flightProfile.shapedAim(
                this.position(), aim, this.poweredTicks, this.getUUID().getLeastSignificantBits());
        if (this.salvoId != null && this.salvoCount > 1) {
            Vec3 forward = aim.subtract(this.salvoOrigin).normalize();
            Vec3 side = forward.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() < .01) {
                side = new Vec3(1, 0, 0);
            }
            side = side.normalize();
            Vec3 up = side.cross(forward).normalize();
            double angle = this.salvoIndex * (Math.PI * 2 / this.salvoCount);
            double radius = Math.max(3.5, this.salvoCount * .8) * Mth.clamp((distance - 5) / 24, 0, 1);
            if (this.flightProfile != MissileFlightProfile.EVASIVE && distance > TERMINAL_RANGE) {
                // Hold a corridor beside the shared route, instead of a distant offset endpoint.
                double along = Mth.clamp(
                        this.position().subtract(this.salvoOrigin).dot(forward) + 30,
                        0,
                        aim.distanceTo(this.salvoOrigin));
                Vec3 profileOffset = shaped.subtract(aim).scale(Math.min(1, 30 / distance));
                shaped = this.salvoOrigin.add(forward.scale(along)).add(profileOffset);
            }
            shaped = shaped.add(side.scale(Math.cos(angle) * radius)).add(up.scale(Math.sin(angle) * radius));
        }
        Vec3 wanted = shaped.subtract(this.position()).normalize();
        if (distance > TERMINAL_RANGE) {
            Vec3 separated = this.separate(this.avoid(heading, wanted));
            return turnToward(heading, separated, separated.dot(wanted) < .995 ? .12 : TURN_RATE);
        }
        return turnToward(
                heading,
                distance > 5 ? this.separate(wanted) : wanted,
                this.shaken > 0 ? TURN_RATE : TERMINAL_TURN_RATE);
    }

    private Vec3 separate(Vec3 wanted) {
        Vec3 force = Vec3.ZERO;
        int count = 0;
        for (var other : this.level()
                .getEntitiesOfClass(
                        CruiseMissileProjectile.class,
                        this.getBoundingBox().inflate(8),
                        e -> e != this && e.isAlive())) {
            // Predict closest approach, rather than swerving only after contact is inevitable.
            Vec3 offset = this.position().subtract(other.position());
            Vec3 relative = this.getDeltaMovement().subtract(other.getDeltaMovement());
            double time =
                    relative.lengthSqr() < .001 ? 0 : Mth.clamp(-offset.dot(relative) / relative.lengthSqr(), 0, 4);
            Vec3 future = offset.add(relative.scale(time));
            double distance = Math.min(offset.length(), future.length());
            if (distance >= 6.5) {
                continue;
            }
            Vec3 away = future.lengthSqr() > .04 ? future : offset;
            if (away.lengthSqr() < .001) {
                away = new Vec3(this.getUUID().compareTo(other.getUUID()) < 0 ? 1 : -1, 0, 0);
            }
            force = force.add(away.normalize().scale((6.5 - distance) / 6.5));
            if (++count == 16) {
                break;
            }
        }
        return wanted.add(force.scale(1.5)).normalize();
    }

    private void fuseOnTarget(Vec3 from, @Nullable Vec3 aim) {
        // A locked physical hull uses the swept collision fuse. Its bounding-box
        // centre is a guidance point, not a reason to explode in empty air outside a small craft.
        if (this.detonated || aim == null || this.tickCount <= ARMING_TICKS || this.trackingHull || this.shaken > 0) {
            return;
        }
        // Coordinates and radar points still have a proximity fuse. Test the whole
        // travelled segment so a fast missile cannot step over it or fuse on a wide miss.
        Vec3 span = this.position().subtract(from);
        double lengthSqr = span.lengthSqr();
        double part = lengthSqr < 1.0E-8D ? 0 : Mth.clamp(aim.subtract(from).dot(span) / lengthSqr, 0, 1);
        Vec3 nearest = from.add(span.scale(part));
        if (nearest.distanceToSqr(aim) <= FUSE_RANGE * FUSE_RANGE) {
            this.detonate(nearest);
        }
    }

    @Nullable
    private Vec3 aimPoint() {
        this.trackingHull = false;
        if (this.targeting.guidance() == Guidance.INTERCEPT) {
            return this.radarAim();
        }
        if (this.targeting.guidance() == Guidance.LOCK
                && this.targeting.lockedSubLevel() >= 0
                && ModList.get().isLoaded("sable")
                && this.level() instanceof ServerLevel server) {
            Vec3 tracked = SableDropCompat.subLevelCentre(server, this.targeting.lockedSubLevel());
            if (tracked != null) {
                this.trackingHull = true;
                return tracked;
            }
        }
        return this.targeting.target() == null || this.targeting.guidance() == Guidance.NONE
                ? null
                : Vec3.atCenterOf(this.targeting.target());
    }

    @Nullable
    private Vec3 radarAim() {
        if (this.targeting.controller() == null || !RadarCompat.loaded()) {
            return null;
        }
        if (this.targeting.contact() != null) {
            var held = RadarCompat.contactById(this.level(), this.targeting.controller(), this.targeting.contact());
            if (held != null) {
                return held.position();
            }
            this.targeting.setContact(null);
        }
        var settings = this.level() instanceof ServerLevel server
                ? InterceptSettingsStore.get(server).forController(this.targeting.controller())
                : InterceptSettings.DEFAULT;
        var fresh = RadarCompat.bestContact(this.level(), this.targeting.controller(), this.position(), settings);
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
        return MissileCollision.turn(from, to, maxRadians);
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

        AABB sweep = new AABB(from, to).inflate(3);
        for (Entity entity : this.level().getEntities(this, sweep, this::canHit)) {
            Vec3 at = MissileCollision.contact(this, entity, from, to);
            if (at != null && (stop == null || from.distanceToSqr(at) < from.distanceToSqr(stop))) {
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

    private void refreshChunkTickets() {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        ChunkPos center = new ChunkPos(this.blockPosition());
        if (center.equals(this.ticketCenter) && this.tickCount % 10 != 0) {
            return;
        }
        var previous = this.ticketChunks.iterator();
        while (previous.hasNext()) {
            ChunkPos old = previous.next();
            if (Math.abs(old.x - center.x) > CHUNK_TICKET_RADIUS || Math.abs(old.z - center.z) > CHUNK_TICKET_RADIUS) {
                server.getChunkSource()
                        .removeRegionTicket(
                                MISSILE_TICKET, old, 2, this.getUUID().getLeastSignificantBits());
                previous.remove();
            }
        }
        this.ticketCenter = center;
        for (int x = -CHUNK_TICKET_RADIUS; x <= CHUNK_TICKET_RADIUS; x++) {
            for (int z = -CHUNK_TICKET_RADIUS; z <= CHUNK_TICKET_RADIUS; z++) {
                ChunkPos active = new ChunkPos(center.x + x, center.z + z);
                this.ticketChunks.add(active);
                server.getChunkSource()
                        .addRegionTicket(
                                MISSILE_TICKET, active, 2, this.getUUID().getLeastSignificantBits());
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (this.level() instanceof ServerLevel server) {
            for (ChunkPos chunk : this.ticketChunks) {
                server.getChunkSource()
                        .removeRegionTicket(
                                MISSILE_TICKET, chunk, 2, this.getUUID().getLeastSignificantBits());
            }
            this.ticketChunks.clear();
            this.ticketCenter = null;
        }
        super.remove(reason);
    }

    private void detonate(Vec3 at) {
        if (this.detonated || !(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.detonated = true;
        try {
            detonateWarhead(server, this, at);
        } catch (Throwable t) {
            CBCMoreContent.LOGGER.error("Cruise missile detonation failed at {}", at, t);
        } finally {
            this.discard();
        }
    }

    /** The same warhead is used when the placed airframe is destroyed by an explosion. */
    public static void detonateWarhead(ServerLevel level, @Nullable Entity source, Vec3 at) {
        BombExplosionHandler.detonate(
                level, source, BombDamageSource.create(level), at, BLOCK_POWER, ENTITY_POWER, BombSize.MOAB);
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

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (amount <= 0 || this.detonated || this.isInvulnerableTo(source)) {
            return false;
        }
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
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        // Server tracking reaches 32 chunks. Vanilla's size-based renderer cutoff was
        // much shorter, making the airframe disappear while its chunks were still in view.
        return distanceSqr < 512.0D * 512.0D;
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
        this.fuelFraction = tag.getDouble("FuelFraction");
        this.poweredTicks = tag.getInt("PoweredTicks");
        this.flightProfile = MissileFlightProfile.byId(tag.getInt("FlightProfile"));
        this.entityData.set(POWERED, tag.getBoolean("Powered"));
        this.waterEntered = tag.getBoolean("WaterEntered");
        this.ejecting = Math.clamp(tag.getInt("Ejecting"), 0, EJECT_TICKS);
        this.entityData.set(EJECTING, this.ejecting > 0);
        this.carrierVelocity =
                new Vec3(tag.getDouble("CarrierX"), tag.getDouble("CarrierY"), tag.getDouble("CarrierZ"));
        this.targeting.readFrom(tag);
        this.salvoId = tag.hasUUID("Salvo") ? tag.getUUID("Salvo") : null;
        this.salvoIndex = Math.clamp(tag.getInt("SalvoIndex"), 0, 15);
        this.salvoCount = Math.clamp(tag.getInt("SalvoCount"), 1, 16);
        this.salvoOrigin = new Vec3(tag.getDouble("SalvoX"), tag.getDouble("SalvoY"), tag.getDouble("SalvoZ"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Fuel", this.fuel);
        tag.putDouble("FuelFraction", this.fuelFraction);
        tag.putInt("PoweredTicks", this.poweredTicks);
        tag.putInt("FlightProfile", this.flightProfile.id());
        tag.putBoolean("Powered", this.isPowered());
        tag.putBoolean("WaterEntered", this.waterEntered);
        tag.putInt("Ejecting", this.ejecting);
        tag.putDouble("CarrierX", this.carrierVelocity.x);
        tag.putDouble("CarrierY", this.carrierVelocity.y);
        tag.putDouble("CarrierZ", this.carrierVelocity.z);
        this.targeting.writeTo(tag);
        if (this.salvoId != null) {
            tag.putUUID("Salvo", this.salvoId);
        }
        tag.putInt("SalvoIndex", this.salvoIndex);
        tag.putInt("SalvoCount", this.salvoCount);
        tag.putDouble("SalvoX", this.salvoOrigin.x);
        tag.putDouble("SalvoY", this.salvoOrigin.y);
        tag.putDouble("SalvoZ", this.salvoOrigin.z);
    }
}
