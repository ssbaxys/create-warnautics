package com.cbc_more_content.bombsight;

import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.compat.SableDropCompat;
import com.cbc_more_content.network.BombsightSolutionPayload;
import com.cbc_more_content.registry.ModBlockEntities;
import com.cbc_more_content.util.DropBombUtil;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The bombsight's computer.
 * <p>
 * Every tick or two it finds the bomb rack it serves (the nearest one on the same carrier),
 * takes the start a bomb from it would get if released now (position, the carrier's
 * velocity and the rack's own push), and follows the fall to the ground. With a target set
 * it measures the run: how far is still to go before the release point, and how far the
 * target lies off the track. With automatic release on, it puts out a redstone signal at
 * the moment of release, held for as long as the stick is set to last, and started early by
 * half of it when the stick is to be centred on the target, so a rack that drops while
 * powered lays its bombs across the target rather than past it.
 */
public class BombsightBlockEntity extends BlockEntity {
    public static final int MIN_SERIES = 2;
    public static final int MAX_SERIES = 100;
    public static final int DEFAULT_SERIES = 6;
    /** How far from the sight a rack still counts as the one it serves. */
    private static final int RACK_REACH = 6;
    /** A run off the target by more than this, sideways, is not released on. */
    public static final double CROSS_TOLERANCE = 4.0D;
    /** Far enough past the release point to count as lining up a fresh run. */
    private static final double REARM_ALONG = 40.0D;
    /** The crew: players this close to the sight get its readings. */
    public static final double CREW_RANGE = 64.0D;

    // —— settings ——
    private byte mode;
    private boolean hasTarget;
    private int targetX;
    private int targetZ;
    private boolean autoRelease = true;
    private int seriesTicks = DEFAULT_SERIES;
    private boolean centred = true;

    // —— running state ——
    @Nullable
    private BlockPos rack;

    private long rackScan = Long.MIN_VALUE;
    private int releaseLeft;
    private boolean spent;

    @Nullable
    private BombSize probedSize;

    private double gravity = -0.05D;
    private double drag = 0.018D;
    private boolean quadratic;

    /** The last impact point worked out, and when: to see how fast the point really moves. */
    @Nullable
    private Vec3 lastImpact;

    private long lastImpactTick;
    /** How fast the impact point runs along the track, blocks per tick, smoothed. */
    private double closing;

    @Nullable
    private BombsightSolutionPayload last;

    public BombsightBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BOMBSIGHT.get(), pos, state);
    }

    public byte mode() {
        return mode;
    }

    public boolean hasTarget() {
        return hasTarget;
    }

    @Nullable
    public BombsightSolutionPayload lastSolution() {
        return last;
    }

    public void configure(
            byte mode, boolean hasTarget, int x, int z, boolean autoRelease, int seriesTicks, boolean centred) {
        this.mode = (byte) Mth.clamp(mode, 0, 1);
        this.hasTarget = hasTarget;
        this.targetX = x;
        this.targetZ = z;
        this.autoRelease = autoRelease;
        this.seriesTicks = Mth.clamp(seriesTicks, MIN_SERIES, MAX_SERIES);
        this.centred = centred;
        this.spent = false;
        setChanged();
    }

    /** A target marked from afar, with the settings key: also switches to the target run. */
    public void markTarget(int x, int z) {
        this.mode = 1;
        this.hasTarget = true;
        this.targetX = x;
        this.targetZ = z;
        this.spent = false;
        setChanged();
    }

    /** The bomb aimer's own hand on the release: one stick, now, whatever the run says. */
    public boolean releaseNow(ServerLevel level) {
        if (releaseLeft > 0) {
            return false;
        }
        releaseLeft = seriesTicks;
        setReleasing(level, true);
        level.playSound(null, worldPosition, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.9f, 1.6f);
        return true;
    }

    public boolean autoRelease() {
        return autoRelease;
    }

    public void setAutoRelease(boolean value) {
        this.autoRelease = value;
        this.spent = false;
        setChanged();
    }

    public void clearTarget() {
        this.hasTarget = false;
        this.mode = 0;
        this.spent = false;
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BombsightBlockEntity sight) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        if (sight.releaseLeft > 0 && --sight.releaseLeft == 0) {
            sight.setReleasing(server, false);
            sight.spent = true;
        }
        boolean run = sight.mode == 1 && sight.hasTarget;
        // A target run is worked out every tick, so the release lands on the right one;
        // just showing the impact point, every other tick is plenty.
        if (!run && (server.getGameTime() + pos.asLong()) % 2 != 0) {
            return;
        }
        sight.solve(server, pos, run);
    }

    private void solve(ServerLevel level, BlockPos pos, boolean run) {
        var frame = SableDropCompat.resolveLaunch(level, pos, pos.getCenter(), Vec3.ZERO, null);
        ServerLevel world = frame.level();
        Vec3 carrier = frame.vel();
        // Nobody aboard to read it and nothing to release: no work.
        boolean crew = false;
        for (ServerPlayer player : world.players()) {
            if (player.distanceToSqr(frame.pos()) <= CREW_RANGE * CREW_RANGE) {
                crew = true;
                break;
            }
        }
        if (!crew && !(run && autoRelease)) {
            return;
        }

        var fall = this.predict(level, frame).fall();
        Vec3 impact = fall.impact();

        float along = 0;
        float cross = 0;
        int untilDrop = -1;
        Vec3 target = new Vec3(targetX + 0.5D, impact.y, targetZ + 0.5D);
        if (hasTarget) {
            double dx = target.x - impact.x;
            double dz = target.z - impact.z;
            double speed = Math.hypot(carrier.x, carrier.z);
            if (speed < 0.05D) {
                // Hovering: the point under the bomb is the point.
                along = (float) Math.hypot(dx, dz);
                untilDrop = along <= 1.0F ? 0 : -1;
            } else {
                double ux = carrier.x / speed;
                double uz = carrier.z / speed;
                along = (float) (dx * ux + dz * uz);
                cross = (float) (dx * -uz + dz * ux);
                double rate = closing(level.getGameTime(), impact, ux, uz, speed);
                untilDrop = along >= 0 ? (int) Math.ceil(along / rate) : -1;
                if (run && autoRelease) {
                    release(level, speed, rate, along, cross, fall.landed());
                }
            }
            if (speed < 0.05D && run && autoRelease) {
                release(level, speed, speed, along, cross, fall.landed());
            }
        }
        lastImpact = impact;
        lastImpactTick = level.getGameTime();
        if (!crew) {
            return;
        }
        last = new BombsightSolutionPayload(
                pos,
                frame.pos(),
                mode,
                rack != null && level.getBlockState(rack).getBlock() instanceof DropBombBlock,
                fall.landed(),
                impact,
                fall.ticks(),
                carrier,
                hasTarget,
                target,
                along,
                cross,
                untilDrop,
                releaseLeft > 0,
                autoRelease,
                seriesTicks,
                centred);
        for (ServerPlayer player : world.players()) {
            if (player.distanceToSqr(frame.pos()) <= CREW_RANGE * CREW_RANGE) {
                PacketDistributor.sendToPlayer(player, last);
            }
        }
    }

    /** What the sight works out: where its bombs start, and where they come down. */
    public record Prediction(boolean rack, Vec3 start, Vec3 velocity, BombsightBallistics.Fall fall) {}

    /** The fall of a bomb released from the rack this sight serves, if let go this tick. */
    public Prediction predict(ServerLevel level) {
        return this.predict(
                level, SableDropCompat.resolveLaunch(level, worldPosition, worldPosition.getCenter(), Vec3.ZERO, null));
    }

    private Prediction predict(ServerLevel level, SableDropCompat.LaunchFrame frame) {
        findRack(level, worldPosition);
        Vec3 start;
        Vec3 velocity;
        boolean found = rack != null && level.getBlockState(rack).getBlock() instanceof DropBombBlock;
        if (found) {
            // A powered rack lets its lowest bomb go first.
            BlockPos first = rack;
            while (level.getBlockState(first.below()).getBlock() instanceof DropBombBlock) {
                first = first.below();
            }
            DropBombBlock bomb = (DropBombBlock) level.getBlockState(first).getBlock();
            var launch = bomb.predictLaunch(level, first, level.getBlockState(first));
            start = launch.pos();
            velocity = launch.vel();
            probe(frame.level(), bomb.bombSize());
        } else {
            // No rack: a bomb let go from right here, with the carrier's speed.
            start = frame.pos().add(0, -0.75D, 0);
            velocity = frame.vel();
        }
        return new Prediction(
                found,
                start,
                velocity,
                BombsightBallistics.trace(frame.level(), start, velocity, gravity, drag, quadratic));
    }

    /**
     * How fast the impact point closes on the target, in blocks per tick along the track.
     * In level flight that is the carrier's speed; climbing, diving or sinking the fall time
     * changes as it goes, and the point runs slower or faster than the carrier itself, so
     * it is measured from the last two solutions, falling back to the carrier's speed.
     */
    private double closing(long now, Vec3 impact, double ux, double uz, double speed) {
        long dt = now - lastImpactTick;
        if (lastImpact == null || dt <= 0 || dt > 4) {
            closing = speed;
            return speed;
        }
        double moved = ((impact.x - lastImpact.x) * ux + (impact.z - lastImpact.z) * uz) / dt;
        double measured = Mth.clamp(moved, speed * 0.15D, speed * 2.0D);
        closing = closing <= 0 ? measured : closing + (measured - closing) * 0.5D;
        return closing;
    }

    /**
     * The drop itself. Started when the release point arrives, or half a stick early when
     * the stick is centred; never when the run is off to the side or already past.
     */
    private void release(ServerLevel level, double speed, double rate, float along, float cross, boolean landed) {
        if (spent && along > REARM_ALONG) {
            spent = false;
        }
        if (spent || releaseLeft > 0 || !landed || Math.abs(cross) > CROSS_TOLERANCE) {
            return;
        }
        boolean due;
        if (speed < 0.05D) {
            due = along <= 1.0F;
        } else {
            // The rack lets go a tick after it is powered, a tick more aboard a ship;
            // a centred stick starts half its length early.
            double latency = SableDropCompat.isInsideSubLevel(level, worldPosition) ? 2.0D : 1.0D;
            double lead = latency + (centred ? seriesTicks / 2.0D : 0.0D);
            double ticks = along / rate;
            due = ticks <= lead + 0.5D && along > -speed * 2.0D;
        }
        if (due) {
            releaseLeft = seriesTicks;
            setReleasing(level, true);
            level.playSound(null, worldPosition, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.9f, 1.6f);
        }
    }

    private void setReleasing(ServerLevel level, boolean releasing) {
        BlockState state = getBlockState();
        if (state.getBlock() instanceof BombsightBlock && state.getValue(BombsightBlock.RELEASING) != releasing) {
            level.setBlock(worldPosition, state.setValue(BombsightBlock.RELEASING, releasing), Block.UPDATE_ALL);
        }
    }

    /** The nearest bomb rack on the same carrier; looked for again every two seconds. */
    private void findRack(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        if (rack != null && level.getBlockState(rack).getBlock() instanceof DropBombBlock && now - rackScan < 40) {
            return;
        }
        rackScan = now;
        rack = null;
        double best = Double.MAX_VALUE;
        for (BlockPos at : BlockPos.betweenClosed(
                pos.offset(-RACK_REACH, -RACK_REACH, -RACK_REACH), pos.offset(RACK_REACH, RACK_REACH, RACK_REACH))) {
            if (level.getBlockState(at).getBlock() instanceof DropBombBlock) {
                double distance = at.distSqr(pos);
                if (distance < best) {
                    best = distance;
                    rack = at.immutable();
                }
            }
        }
    }

    /** Reads the ballistics of the size of bomb the rack holds, once per size. */
    private void probe(ServerLevel level, BombSize size) {
        if (size == probedSize) {
            return;
        }
        var bomb = DropBombUtil.probe(size, level);
        gravity = bomb.ballisticGravity();
        drag = bomb.ballisticDrag();
        quadratic = bomb.ballisticQuadraticDrag();
        probedSize = size;
        bomb.discard();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putByte("Mode", mode);
        tag.putBoolean("HasTarget", hasTarget);
        tag.putInt("TargetX", targetX);
        tag.putInt("TargetZ", targetZ);
        tag.putBoolean("AutoRelease", autoRelease);
        tag.putInt("Series", seriesTicks);
        tag.putBoolean("Centred", centred);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        mode = (byte) Mth.clamp(tag.getByte("Mode"), 0, 1);
        hasTarget = tag.getBoolean("HasTarget");
        targetX = tag.getInt("TargetX");
        targetZ = tag.getInt("TargetZ");
        autoRelease = !tag.contains("AutoRelease") || tag.getBoolean("AutoRelease");
        seriesTicks = tag.contains("Series") ? Mth.clamp(tag.getInt("Series"), MIN_SERIES, MAX_SERIES) : DEFAULT_SERIES;
        centred = !tag.contains("Centred") || tag.getBoolean("Centred");
    }
}
