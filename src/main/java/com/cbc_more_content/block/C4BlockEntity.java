package com.cbc_more_content.block;

import com.cbc_more_content.block.C4Block.Fuse;
import com.cbc_more_content.damage.BombDamageSource;
import com.cbc_more_content.effects.BombBlastFx;
import com.cbc_more_content.effects.BombExplosionHandler;
import com.cbc_more_content.registry.ModBlockEntities;
import com.cbc_more_content.registry.ModSounds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A placed C4 charge: holds its fuse setting and, once armed, counts down to detonation.
 * Whether it is armed lives in the block state, which is what drives the model.
 */
public class C4BlockEntity extends BlockEntity {
    public static final int MIN_SECONDS = 5;
    public static final int MAX_SECONDS = 45;
    public static final int DEFAULT_SECONDS = 15;

    /** Breaching charge: hits hard, but over a tight radius. */
    private static final float BLOCK_POWER = 7.5f;

    private static final float ENTITY_POWER = 9.0f;
    /** Beep spacing: one a second while there is time, frantic at the end. */
    private static final int START_INTERVAL = 20;

    private static final int END_INTERVAL = 4;
    /** Screen stays lit this long after each beep — always under {@link #END_INTERVAL}. */
    private static final int BLINK_TICKS = 2;

    /** Digits in an arming code. */
    public static final int CODE_LENGTH = 4;
    /** Wires behind the panel, one of each role. */
    public static final int WIRE_COUNT = 3;
    /** Colours a wire can take, as packed RGB. Three distinct ones are drawn per charge. */
    public static final int[] WIRE_PALETTE = {
        0xD03A32, 0x3A6FD0, 0x4CA84C, 0xD8B43A, 0xE0E0E0, 0x2A2A2A, 0x9A4CC8, 0xD8843A,
    };

    private int fuseSeconds = DEFAULT_SECONDS;
    private int remaining;
    /** Set when the charge is armed; the same code disarms it. -1 while unset. */
    private int code = -1;
    /** Seeded at the widest spacing, never above it, so the first beep lands at once. */
    private int sinceLastBeep = START_INTERVAL;
    /** Counting down to a tamper detonation; 0 when nobody is prising at it. */
    private int tamperTicks;
    /** Palette index per wire. Synced: the cutter has to see what it is cutting. */
    private final int[] wireColours = new int[WIRE_COUNT];
    /** Which wire does what. Never synced — that is the whole puzzle. */
    private int defuseWire = -1;

    private int detonateWire = -1;
    /** Bitmask of wires already cut. Synced, and persists across closing the panel. */
    private int cutMask;
    /** Waiting on a detonator instead of counting down. */
    private boolean remote;

    public C4BlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.C4.get(), pos, state);
    }

    public int fuseSeconds() {
        return this.fuseSeconds;
    }

    public int remaining() {
        return this.remaining;
    }

    /** Server-side only — see {@link #getUpdateTag}. */
    public int code() {
        return this.code;
    }

    /**
     * Restores a fuse carried across by a charge that fell. Deliberately not
     * {@link #arm()}: nothing about landing should replay the arming sound or restart
     * the countdown from the top.
     */
    public void restore(int fuseSeconds, int remaining, int code, boolean armed, boolean remote) {
        this.fuseSeconds = Mth.clamp(fuseSeconds, MIN_SECONDS, MAX_SECONDS);
        this.remaining = remaining;
        this.code = code;
        this.sinceLastBeep = START_INTERVAL;
        this.setChanged();
        this.setRemote(remote);
        // A charge parked on a detonator carries a negative fuse rather than a running
        // one, so it lands still waiting on a set instead of quietly going inert. Not
        // paired, though: it moved, and whatever set held it is holding the old cell.
        if (armed && (remote || remaining > 0)) {
            this.setFuseState(Fuse.ARMED);
        }
    }

    /**
     * How far through the fuse this charge is, 0 at arming and 1 at detonation, squared
     * so most of the run is a steady pulse and the panic is at the end.
     */
    public static float urgency(int remaining, int fuseSeconds) {
        float progress = 1.0f - (remaining / (float) Math.max(1, fuseSeconds * 20));
        return Mth.clamp(progress * progress, 0.0f, 1.0f);
    }

    /** Ticks between beeps at the given urgency. */
    public static int beepInterval(float urgency) {
        return Math.max(END_INTERVAL, Math.round(Mth.lerp(urgency, START_INTERVAL, END_INTERVAL)));
    }

    /** Playback pitch at the given urgency; the tone tightens as the spacing closes. */
    public static float beepPitch(float urgency) {
        return 1.0f + urgency * 1.15f;
    }

    /** Someone has started breaking a live charge; it goes off partway through. */
    public void beginTamper(int ticks) {
        if (this.tamperTicks <= 0) {
            this.tamperTicks = Math.max(1, ticks);
            this.setChanged();
        }
    }

    /** True when this charge answers to a detonator rather than to its own clock. */
    public boolean isRemote() {
        return this.remote;
    }

    public void setRemote(boolean value) {
        if (this.remote == value) {
            return;
        }
        this.remote = value;
        this.setChanged();
        if (this.level == null) {
            return;
        }
        // The aerial is part of the model, so which trigger mode is set has to reach the
        // block state and not only this block entity.
        BlockState state = this.level.getBlockState(this.worldPosition);
        if (state.hasProperty(C4Block.RECEIVER) && state.getValue(C4Block.RECEIVER) != value) {
            this.level.setBlock(this.worldPosition, state.setValue(C4Block.RECEIVER, value), Block.UPDATE_CLIENTS);
        }
        BlockState updated = this.level.getBlockState(this.worldPosition);
        this.level.sendBlockUpdated(this.worldPosition, updated, updated, Block.UPDATE_CLIENTS);
    }

    /** Armed and waiting: the lamp is lit, the fuse is not running. */
    public boolean isWaitingOnRemote() {
        return this.remote && this.isArmed() && this.remaining < 0;
    }

    /**
     * The detonator pressed, on a whole ring at once. Returns how many answered.
     * <p>
     * Every charge is lifted out of the world before any of them goes off. Firing them
     * one at a time meant the first blast deleted the rest of the ring on its way out, and
     * the charges still queued behind it were simply gone by the time their turn came.
     */
    public static int fireRing(ServerLevel server, List<BlockPos> ring) {
        List<BlockPos> live = new ArrayList<>(ring.size());
        for (BlockPos at : ring) {
            if (server.getBlockEntity(at) instanceof C4BlockEntity charge && charge.isWaitingOnRemote()) {
                live.add(at);
            }
        }
        for (BlockPos at : live) {
            server.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (BlockPos at : live) {
            explode(server, Vec3.atCenterOf(at));
        }
        return live.size();
    }

    public boolean isArmed() {
        return this.getBlockState().getValue(C4Block.STATE) != Fuse.IDLE;
    }

    public void setFuseSeconds(int seconds) {
        int clamped = Mth.clamp(seconds, MIN_SECONDS, MAX_SECONDS);
        if (clamped == this.fuseSeconds) {
            return;
        }
        this.fuseSeconds = clamped;
        this.setChanged();
        // The settings screen reads this back off the client copy, so it has to travel.
        if (this.level != null) {
            this.level.sendBlockUpdated(
                    this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public void setCode(int code) {
        this.code = code;
        this.setChanged();
    }

    /** Whether this wire has already been cut. */
    public boolean isWireCut(int wire) {
        return (this.cutMask & (1 << wire)) != 0;
    }

    /** Palette index of a wire, for the cutting screen. */
    public int wireColour(int wire) {
        return this.wireColours[Mth.clamp(wire, 0, WIRE_COUNT - 1)];
    }

    /**
     * Rolls three distinct wire colours and hands out the roles. Called once when the
     * charge goes live, so the layout is fixed for as long as that fuse is running.
     */
    private void rollWires(net.minecraft.util.RandomSource random) {
        int[] palette = WIRE_PALETTE.clone();
        for (int i = palette.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = palette[i];
            palette[i] = palette[j];
            palette[j] = swap;
        }
        System.arraycopy(palette, 0, this.wireColours, 0, WIRE_COUNT);

        this.defuseWire = random.nextInt(WIRE_COUNT);
        do {
            this.detonateWire = random.nextInt(WIRE_COUNT);
        } while (this.detonateWire == this.defuseWire);
        this.cutMask = 0;
    }

    /**
     * Cuts a wire. One kills the fuse, one sets the charge off in your face, and the
     * remaining one halves whatever time is left.
     */
    public WireResult cutWire(ServerLevel server, int wire) {
        if (!this.isArmed() || wire < 0 || wire >= WIRE_COUNT || this.isWireCut(wire)) {
            return WireResult.NOTHING;
        }
        // Recorded before the outcome is applied, so a cut survives closing the panel
        // and a wire can never be cut twice for a second effect.
        this.cutMask |= 1 << wire;
        this.setChanged();
        if (this.level != null) {
            this.level.sendBlockUpdated(
                    this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
        }
        if (wire == this.defuseWire) {
            this.disarm();
            return WireResult.DEFUSED;
        }
        if (wire == this.detonateWire) {
            this.blow(server, this.worldPosition);
            return WireResult.DETONATED;
        }
        this.remaining = Math.max(1, this.remaining / 2);
        this.setChanged();
        return WireResult.ACCELERATED;
    }

    public enum WireResult {
        NOTHING,
        DEFUSED,
        DETONATED,
        ACCELERATED,
    }

    /** Whether {@code attempt} opens this charge. Only ever evaluated server-side. */
    public boolean matchesCode(int attempt) {
        return this.code >= 0 && this.code == attempt;
    }

    /** Stops the countdown and clears the code, leaving the charge placed and inert. */
    public void disarm() {
        this.remaining = 0;
        // Through the setter, so a disarmed charge loses its aerial along with its mode.
        this.setRemote(false);
        this.code = -1;
        this.sinceLastBeep = START_INTERVAL;
        this.cutMask = 0;
        this.setChanged();
        this.setFuseState(Fuse.IDLE);
    }

    /** Starts the countdown. Called when the player confirms on the settings screen. */
    public void arm() {
        if (this.level == null || this.isArmed()) {
            return;
        }
        // A remote charge is armed but silent: negative marks it as parked, waiting
        // for a detonator rather than for its own clock to run out.
        this.remaining = this.remote ? -1 : this.fuseSeconds * 20;
        this.sinceLastBeep = START_INTERVAL;
        this.setChanged();
        this.rollWires(this.level.random);
        // A remote charge comes up on the lamp alone: live, but with a dark screen and a
        // still cog, because it has nothing to say until a set is paired with it.
        this.setFuseState(Fuse.ARMED);
    }

    /**
     * A detonator taking this charge on, or letting it go. Paired is the only state in
     * which a remote charge lights its screen and turns its cog — it now has a set at the
     * other end, and that is what the panel is reporting.
     */
    public void setPaired(boolean paired) {
        if (!this.remote || !this.isArmed()) {
            return;
        }
        this.setFuseState(paired ? Fuse.LIT : Fuse.ARMED);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, C4BlockEntity be) {
        if (state.getValue(C4Block.STATE) == Fuse.IDLE || !(level instanceof ServerLevel server)) {
            return;
        }
        if (be.tamperTicks > 0 && --be.tamperTicks <= 0) {
            be.blow(server, pos);
            return;
        }
        if (be.remaining < 0) {
            // Parked on a detonator. Still fires if someone starts pulling it off.
            return;
        }
        if (be.remaining == 0) {
            be.blow(server, pos);
            return;
        }
        be.remaining--;

        // The sample is one short beep, so the acceleration is spacing rather than
        // playback rate; pitch rides along on top.
        float urgency = urgency(be.remaining, be.fuseSeconds);
        float pitch = beepPitch(urgency);
        int interval = beepInterval(urgency);

        // Capped rather than left to run away: a counter seeded with Integer.MAX_VALUE to
        // force an immediate first beep overflowed to MIN_VALUE here, and the fuse then
        // counted silently for two billion ticks without ever beeping or blinking.
        be.sinceLastBeep = Math.min(be.sinceLastBeep + 1, START_INTERVAL);
        if (be.sinceLastBeep >= interval) {
            be.sinceLastBeep = 0;
            server.playSound(null, pos, ModSounds.C4_TICK.get(), SoundSource.BLOCKS, 1.1f, pitch);
            be.setFuseState(Fuse.LIT);
        } else if (be.sinceLastBeep >= BLINK_TICKS) {
            be.setFuseState(Fuse.ARMED);
        }
    }

    private void setFuseState(Fuse fuse) {
        if (this.level == null) {
            return;
        }
        // Read back rather than trusting the cached copy: setting the trigger mode also
        // rewrites this block state, and a stale snapshot here would undo the aerial.
        BlockState state = this.level.getBlockState(this.worldPosition);
        if (!state.hasProperty(C4Block.STATE) || state.getValue(C4Block.STATE) == fuse) {
            return;
        }
        // Clients only — a blinking lamp must not spam neighbour updates every beep.
        this.level.setBlock(this.worldPosition, state.setValue(C4Block.STATE, fuse), Block.UPDATE_CLIENTS);
    }

    private void blow(ServerLevel server, BlockPos pos) {
        // Cleared first so the blast cannot re-enter this block entity.
        server.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        explode(server, Vec3.atCenterOf(pos));
    }

    /** The charge going off, wherever it happens to be — placed or still falling. */
    public static void explode(ServerLevel server, Vec3 at) {
        BombExplosionHandler.detonateBreachingCharge(
                server, BombDamageSource.create(server), at, BLOCK_POWER, ENTITY_POWER);
        BombBlastFx.playBreachingCharge(server, at, BLOCK_POWER);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.fuseSeconds = Mth.clamp(
                tag.contains("FuseSeconds") ? tag.getInt("FuseSeconds") : DEFAULT_SECONDS, MIN_SECONDS, MAX_SECONDS);
        this.remaining = tag.getInt("Remaining");
        this.code = tag.contains("Code") ? tag.getInt("Code") : -1;
        this.tamperTicks = tag.getInt("Tamper");
        int[] colours = tag.getIntArray("WireColours");
        if (colours.length == WIRE_COUNT) {
            System.arraycopy(colours, 0, this.wireColours, 0, WIRE_COUNT);
        }
        this.defuseWire = tag.contains("DefuseWire") ? tag.getInt("DefuseWire") : -1;
        this.detonateWire = tag.contains("DetonateWire") ? tag.getInt("DetonateWire") : -1;
        this.cutMask = tag.getInt("CutWires");
        this.remote = tag.getBoolean("Remote");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("FuseSeconds", this.fuseSeconds);
        tag.putInt("Remaining", this.remaining);
        tag.putInt("Code", this.code);
        tag.putInt("Tamper", this.tamperTicks);
        tag.putIntArray("WireColours", this.wireColours);
        tag.putInt("DefuseWire", this.defuseWire);
        tag.putInt("DetonateWire", this.detonateWire);
        tag.putInt("CutWires", this.cutMask);
        tag.putBoolean("Remote", this.remote);
    }

    /**
     * Deliberately not {@code saveWithoutMetadata}: the arming code must never reach a
     * client, or reading it out of the block entity would be enough to defuse anything.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("FuseSeconds", this.fuseSeconds);
        // Colours and which wires are already cut travel, so reopening the panel shows
        // the same board. The roles never do.
        tag.putIntArray("WireColours", this.wireColours);
        tag.putInt("CutWires", this.cutMask);
        tag.putBoolean("Remote", this.remote);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
