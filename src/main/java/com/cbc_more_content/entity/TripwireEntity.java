package com.cbc_more_content.entity;

import javax.annotation.Nullable;

import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.event.TripwireSignal;
import com.cbc_more_content.registry.ModEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * A tripwire strung between two blocks, at whatever angle they happen to sit at.
 * <p>
 * It parts when something walks <em>through</em> it, not when something merely touches
 * it: the wire tracks which side of its own plane each nearby body is on, and a change of
 * side is the trigger. Brush it and step back the way you came and nothing happens, and
 * no amount of sprinting can skip it, because the side simply reads the other way round
 * on the next tick.
 * <p>
 * That replaced a scheme that accumulated tension along a guessed pull direction. It kept
 * failing in a different way each time it was tuned — never catching, never letting go,
 * clinging for blocks — because every one of those states had to be inferred. There is
 * nothing here to infer.
 */
public class TripwireEntity extends Entity {
    /** Longest run between two posts. */
    public static final int MAX_SPAN = 8;
    /** Height up the post the wire is tied off at. */
    private static final double TIE_HEIGHT = 0.85D;
    /** How far off the line the wire still reaches someone. */
    private static final double REACH = 0.6D;
    /** Shouldered this far sideways without crossing and it parts anyway. */
    private static final double TRIP_PULL = 1.15D;
    /** Ticks of slack given back once nothing is pulling any more. */
    private static final int RELAX_TICKS = 6;
    /** How often the hull check runs; a plot is not going to sneak past between ticks. */
    private static final int HULL_INTERVAL = 4;

    /** How much a snagged walker is held up, so the wire is felt before it parts. */
    private static final double CLING = 0.82D;

    private static final EntityDataAccessor<BlockPos> ANCHOR_A =
            SynchedEntityData.defineId(TripwireEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<BlockPos> ANCHOR_B =
            SynchedEntityData.defineId(TripwireEntity.class, EntityDataSerializers.BLOCK_POS);
    /** Where along the run the wire is snagged, 0 at A and 1 at B. */
    private static final EntityDataAccessor<Float> CAUGHT_AT =
            SynchedEntityData.defineId(TripwireEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> PULL_X =
            SynchedEntityData.defineId(TripwireEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> PULL_Y =
            SynchedEntityData.defineId(TripwireEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> PULL_Z =
            SynchedEntityData.defineId(TripwireEntity.class, EntityDataSerializers.FLOAT);

    private int slack;
    /**
     * Which side of the wire each nearby body was on last tick. A sign change is the
     * trigger, which is why nothing here has to remember tension, direction or how
     * fast anyone was going.
     */
    private final java.util.Map<Integer, Double> sides = new java.util.HashMap<>();

    public TripwireEntity(EntityType<? extends TripwireEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public TripwireEntity(Level level, BlockPos a, BlockPos b) {
        this(ModEntityTypes.TRIPWIRE.get(), level);
        this.entityData.set(ANCHOR_A, a);
        this.entityData.set(ANCHOR_B, b);
        Vec3 mid = tie(a).add(tie(b)).scale(0.5D);
        this.setPos(mid.x, mid.y, mid.z);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ANCHOR_A, BlockPos.ZERO);
        builder.define(ANCHOR_B, BlockPos.ZERO);
        builder.define(CAUGHT_AT, 0.5f);
        builder.define(PULL_X, 0.0f);
        builder.define(PULL_Y, 0.0f);
        builder.define(PULL_Z, 0.0f);
    }

    // —— geometry ——

    /** Where the wire is tied off on a post. */
    public static Vec3 tie(BlockPos post) {
        return new Vec3(post.getX() + 0.5D, post.getY() + TIE_HEIGHT, post.getZ() + 0.5D);
    }

    public Vec3 endA() {
        return tie(this.entityData.get(ANCHOR_A));
    }

    public Vec3 endB() {
        return tie(this.entityData.get(ANCHOR_B));
    }

    /** How far the wire has been dragged out of line, and in which direction. */
    public Vec3 pull() {
        return new Vec3(this.entityData.get(PULL_X),
                this.entityData.get(PULL_Y),
                this.entityData.get(PULL_Z));
    }

    /** Where along the run the drag is applied. */
    public float caughtAt() {
        return this.entityData.get(CAUGHT_AT);
    }

    @Override
    protected AABB makeBoundingBox() {
        Vec3 a = this.endA();
        Vec3 b = this.endB();
        if (a.equals(b)) {
            return super.makeBoundingBox();
        }
        return new AABB(a, b).inflate(0.5D);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        return distanceSqr < 96.0D * 96.0D;
    }

    /** Thin, black and meant to be missed; picking it out of the air is not the point. */
    @Override
    public boolean isPickable() {
        return false;
    }

    // —— running ——

    @Override
    public void tick() {
        super.tick();
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        if (!isPost(server, this.entityData.get(ANCHOR_A))
                || !isPost(server, this.entityData.get(ANCHOR_B))) {
            this.snap();
            return;
        }

        if (this.tickCount % HULL_INTERVAL == 0 && this.hullAcrossTheLine(server)) {
            this.part(server);
            return;
        }

        Vec3 a = this.endA();
        Vec3 b = this.endB();
        Vec3 face = planeNormal(a, b);
        if (face == null) {
            return;
        }

        boolean anyoneOnIt = false;
        for (Entity entity : server.getEntities(this, this.getBoundingBox().inflate(2.0D))) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isSpectator()) {
                continue;
            }
            if (living instanceof Player player && player.getAbilities().flying) {
                continue;
            }
            Snag snag = measure(a, b, bodyPoint(living));
            if (snag == null || snag.offset.length() > REACH) {
                this.sides.remove(living.getId());
                continue;
            }

            // Which side of the wire's own plane they are standing on, signed.
            double side = snag.offset.dot(face);
            Double before = this.sides.put(living.getId(), side);
            anyoneOnIt = true;

            // Walked through it. Crossing the plane inside the run is the whole trigger:
            // no accumulating tension to lose track of, no direction to guess at, and a
            // sprint cannot skip it because the side simply reads the other way round.
            if (before != null && Math.signum(before) != Math.signum(side)
                    && Math.abs(before) + Math.abs(side) > 1.0E-4D) {
                this.part(server);
                return;
            }
            // Or shouldered it far enough sideways without ever crossing.
            if (Math.abs(side) >= TRIP_PULL) {
                this.part(server);
                return;
            }

            this.dragOn(living, Math.abs(side));
            this.setPull(snag.offset, snag.along);
        }

        if (!anyoneOnIt) {
            this.sides.clear();
            this.relax();
        }
    }

    /** Where on a body the wire catches: shin height for anything walking. */
    private static Vec3 bodyPoint(LivingEntity living) {
        return living.position().add(0.0D, living.getBbHeight() * 0.4D, 0.0D);
    }

    /**
     * Unit normal of the wire's own plane — across the run, and level, so leaning over
     * the wire is not mistaken for stepping through it.
     */
    @Nullable
    private static Vec3 planeNormal(Vec3 a, Vec3 b) {
        Vec3 span = b.subtract(a);
        Vec3 flat = new Vec3(span.x, 0.0D, span.z);
        if (flat.lengthSqr() < 1.0E-6D) {
            // Strung straight up a wall; any level direction across it will do.
            return new Vec3(1.0D, 0.0D, 0.0D);
        }
        return new Vec3(-flat.z, 0.0D, flat.x).normalize();
    }

    /** The wire pulls harder the further it is bent, so it is felt before it parts. */
    private void dragOn(LivingEntity living, double bend) {
        double load = Mth.clamp(bend / TRIP_PULL, 0.0D, 1.0D);
        double drag = 1.0D - (1.0D - CLING) * load;
        Vec3 held = living.getDeltaMovement();
        living.setDeltaMovement(held.x * drag, held.y, held.z * drag);
        living.hurtMarked = true;
    }

    /** Nothing pulling: the wire eases back into line rather than snapping straight. */
    private void relax() {
        Vec3 pull = this.pull();
        if (pull.lengthSqr() < 1.0E-5D) {
            return;
        }
        this.slack++;
        double factor = Math.max(0.0D, 1.0D - this.slack / (double) RELAX_TICKS);
        this.setPull(pull.scale(factor), this.caughtAt());
    }

    private void setPull(Vec3 offset, float along) {
        this.entityData.set(PULL_X, (float) offset.x);
        this.entityData.set(PULL_Y, (float) offset.y);
        this.entityData.set(PULL_Z, (float) offset.z);
        this.entityData.set(CAUGHT_AT, along);
    }

    /**
     * Where a point sits relative to the run: how far along, the nearest point on the
     * line, and how far off it. Null when the point is past either post — the wire is
     * tied off there and cannot be dragged round the corner.
     */
    @Nullable
    private static Snag measure(Vec3 a, Vec3 b, Vec3 point) {
        Vec3 span = b.subtract(a);
        double lengthSqr = span.lengthSqr();
        if (lengthSqr < 1.0E-6D) {
            return null;
        }
        double along = point.subtract(a).dot(span) / lengthSqr;
        if (along < 0.05D || along > 0.95D) {
            return null;
        }
        Vec3 on = a.add(span.scale(along));
        return new Snag((float) along, on, point.subtract(on));
    }

    /** A hull crossing the line does not get a chance to think better of it. */
    private boolean hullAcrossTheLine(ServerLevel server) {
        if (!ModList.get().isLoaded("sable")) {
            return false;
        }
        double reach = this.endA().distanceTo(this.endB()) * 0.5D + 1.0D;
        return SableDropCompat.overlapsAnySubLevel(server, this.position(), reach);
    }

    private static boolean isPost(Level level, BlockPos pos) {
        return level.isLoaded(pos) && canAnchor(level.getBlockState(pos));
    }

    /**
     * Anything a wire can be tied to: any real block, at any angle between two of them.
     * Only fluids and things you walk through are refused — there is nothing there to
     * take the strain.
     */
    public static boolean canAnchor(BlockState state) {
        return !state.isAir()
                && !state.canBeReplaced()
                && state.getFluidState().isEmpty();
    }

    /**
     * The wire parts and both posts go live.
     * <p>
     * It carries no charge of its own on purpose: a signal is the useful thing to hand
     * to whatever is already built around it, and a charge is not.
     */
    private void part(ServerLevel server) {
        TripwireSignal.pulse(server, this.entityData.get(ANCHOR_A));
        TripwireSignal.pulse(server, this.entityData.get(ANCHOR_B));
        server.playSound(null, this.blockPosition(), SoundEvents.LEASH_KNOT_BREAK,
                SoundSource.BLOCKS, 1.0f, 1.6f);
        this.snap();
    }

    /**
     * A post came down, or the wire was cut, or it parted. Nothing is dropped: a wire
     * that has been pulled apart is spent, and leaving a coil on the ground every time
     * turned a one-shot trap into a reusable trigger nobody had to replace.
     */
    private void snap() {
        this.level().playSound(null, this.blockPosition(), SoundEvents.LEASH_KNOT_BREAK,
                SoundSource.BLOCKS, 0.7f, 1.4f);
        this.discard();
    }

    /** Cuts any wire tied off at {@code post}. Called by the wire cutters. */
    public static boolean cutAt(ServerLevel server, BlockPos post) {
        AABB around = new AABB(post).inflate(MAX_SPAN + 1);
        boolean cut = false;
        for (TripwireEntity wire : server.getEntitiesOfClass(TripwireEntity.class, around)) {
            if (post.equals(wire.entityData.get(ANCHOR_A))
                    || post.equals(wire.entityData.get(ANCHOR_B))) {
                wire.snap();
                cut = true;
            }
        }
        return cut;
    }

    /** True if a wire is already tied off at this post, so one post carries one wire. */
    public static boolean occupied(ServerLevel server, BlockPos post) {
        AABB around = new AABB(post).inflate(MAX_SPAN + 1);
        for (TripwireEntity wire : server.getEntitiesOfClass(TripwireEntity.class, around)) {
            if (post.equals(wire.entityData.get(ANCHOR_A))
                    || post.equals(wire.entityData.get(ANCHOR_B))) {
                return true;
            }
        }
        return false;
    }

    // —— persistence ——

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        tag.put("AnchorA", NbtUtils.writeBlockPos(this.entityData.get(ANCHOR_A)));
        tag.put("AnchorB", NbtUtils.writeBlockPos(this.entityData.get(ANCHOR_B)));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(ANCHOR_A,
                NbtUtils.readBlockPos(tag, "AnchorA").orElse(BlockPos.ZERO));
        this.entityData.set(ANCHOR_B,
                NbtUtils.readBlockPos(tag, "AnchorB").orElse(BlockPos.ZERO));
        this.setBoundingBox(this.makeBoundingBox());
    }

    private record Snag(float along, Vec3 point, Vec3 offset) {
    }
}
