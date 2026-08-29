package com.cbc_more_content.block;

import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.munitions.DropBombProjectile;
import com.cbc_more_content.munitions.SeaBombProjectile;
import com.cbc_more_content.siren.BlastLog;
import com.cbc_more_content.siren.SirenSettings;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * An air-raid post: what it is watching for, how much longer it has to wail, and how hard
 * its rotor is being turned.
 * <p>
 * Two separate reasons to be sounding, and they behave differently on purpose. A signal
 * simply holds the post up and lets go of it — the operator is standing at the lever and
 * does not want to argue with a timer. A sighting sets the linger running instead, and
 * that keeps going after whatever was seen has come and gone, because the whole point of
 * one is that nothing announces the all-clear.
 * <p>
 * Neither of them makes a sound on their own. The note is the rotor, so drive decides
 * whether there is one at all and how loud it is; the rest only decides whether the post
 * is trying.
 */
public class SirenBlockEntity extends KineticBlockEntity {
    /**
     * How often the post looks around.
     * <p>
     * Was once a second, and that alone was enough for a bomb to fall past a post and go
     * off without it ever making a sound: a bomb released low is in the air for barely a
     * second, so most of them fell entirely between two looks.
     */
    private static final int SCAN_INTERVAL = 5;
    /** How often listeners are reminded, so walking into a raid puts you under it. */
    private static final int KEEPALIVE_TICKS = 40;
    /** How far the wail is worth sending at all; past this no layer is audible. */
    private static final double AUDIBLE = 340.0D;
    /**
     * What a signal-held post promises a listener before the next keepalive.
     * <p>
     * A held post has no end to quote — the lever decides — so it hands out a little more
     * than the gap between reminders and relies on the next one arriving. If the post
     * stops being ticked at all, that is the server no longer simulating it, and the wail
     * running out a few seconds later is the honest outcome.
     */
    private static final int HELD_GRACE = KEEPALIVE_TICKS * 3;
    /** Above this something is on its way somewhere; below it, it is not moving. */
    private static final double MOVING = 0.01D;
    /** Cosine of how far off the beam an inbound missile may be and still be inbound. */
    private static final double INBOUND_DOT = 0.25D;
    /**
     * Rotor speed, in RPM, at which the post is at full voice. A shaft off a windmill
     * will not do; a gearbox stepped up to a working speed will.
     */
    private static final float FULL_VOICE_RPM = 64.0f;
    /** Below this the rotor is barely turning and there is no note to speak of. */
    private static final float STALL_RPM = 1.0f;
    /**
     * What the housing costs the network.
     * <p>
     * Heavy on purpose. A siren rotor is a compressor being spun against the air it is
     * shifting, and a post that could be run off a hand crank would make the whole
     * business of driving it a formality.
     */
    private static final float STRESS_IMPACT = 1.0f;

    private SirenSettings settings = SirenSettings.DEFAULT;
    /** Ticks of linger left, from a sighting. Nothing to do with the signal. */
    private int lingerTicks;
    /** A signal is on the post right now. Derived from the world, never saved. */
    private boolean held;
    /** Ticks until listeners are reminded this post is still going. */
    private int keepalive;

    /** Loudness last sent out, so a drifting speed does not spam every listener. */
    private float announcedVoice = -1.0f;

    public SirenBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(java.util.List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }

    @Override
    public float calculateStressApplied() {
        this.lastStressApplied = STRESS_IMPACT;
        return STRESS_IMPACT;
    }

    /**
     * How loud the rotor is, 0 when it is not turning at all.
     * <p>
     * Read from the kinetic speed, which Create already keeps on both sides, so a
     * listener standing next to the post hears it follow the gearbox without anything
     * of ours being synced for it.
     */
    public float voice() {
        float rpm = Math.abs(this.getSpeed());
        if (rpm < STALL_RPM) {
            return 0.0f;
        }
        // Straight proportion: half the speed is half the voice, and past full speed
        // there is nothing left to give. A curve here read as the post being loud almost
        // as soon as it turned at all, which made the gearbox between them pointless.
        return Mth.clamp(rpm / FULL_VOICE_RPM, 0.0f, 1.0f);
    }

    /** Whether there is drive enough to make a note. */
    public boolean isTurning() {
        return this.voice() > 0.0f;
    }

    public SirenSettings settings() {
        return this.settings;
    }

    public void applySettings(SirenSettings settings) {
        this.settings = settings;
        this.setChanged();
        this.sync();
        // Turning a post off has to actually shut it up. Leaving it howling until the old
        // linger ran out made the panel look like it had done nothing.
        if (!settings.watchesAnything()) {
            this.lingerTicks = 0;
            this.refresh();
        }
    }

    /**
     * Trying to sound, and able to. The two are separate on purpose: a post with the
     * lever thrown and no drive is still armed, it simply has no voice, and it starts
     * making one the moment the shaft turns rather than needing to be told again.
     */
    public boolean isWailing() {
        return this.wants() && this.isTurning();
    }

    /** Whether anything is asking this post to sound, drive aside. */
    public boolean wants() {
        return this.held || this.lingerTicks > 0;
    }

    /**
     * A sighting: start the linger, or top it up if one is already running.
     * <p>
     * Private, and it must stay that way. This is the only thing that writes the linger,
     * and the linger is what keeps a post going once whatever set it off has gone. A
     * redstone signal must never come through here — it only ever holds the post up, and
     * the one time this was wired to a signal, cutting the line left it wailing out the
     * full forty-five seconds with no way to stop it.
     */
    private void raise() {
        int wanted = Math.max(20, this.settings.lingerSeconds() * 20);
        if (wanted > this.lingerTicks) {
            this.lingerTicks = wanted;
            this.setChanged();
        }
        this.refresh();
    }

    @Override
    public void tick() {
        // Create's own bookkeeping first: the speed everything below reads is its answer.
        super.tick();
        if (!(this.level instanceof ServerLevel server)) {
            return;
        }
        BlockPos pos = this.worldPosition;

        // The signal is read fresh every tick rather than remembered. A post that had been
        // switched on once went on wailing out the whole linger after the line went dead,
        // because being told by a lever and being told by a sighting were the same state.
        this.held = server.hasNeighborSignal(pos);

        if (this.settings.watchesAnything()
                && server.getGameTime() % SCAN_INTERVAL == 0
                && this.threatNearby(server, pos)) {
            this.raise();
        }
        if (this.lingerTicks > 0) {
            this.lingerTicks--;
        }

        this.refresh();
        if (!this.isWailing()) {
            return;
        }
        // A rotor that has changed pace has to be reported before the next keepalive,
        // or the gearbox would be turned up and nothing would happen for two seconds.
        boolean voiceMoved = Math.abs(this.voice() - this.announcedVoice) > 0.05f;
        if (--this.keepalive <= 0 || voiceMoved) {
            this.keepalive = KEEPALIVE_TICKS;
            this.announce();
        }
    }

    /** Brings the lamp and the horn into line, and opens voices when one starts. */
    private void refresh() {
        boolean wailing = this.isWailing();
        boolean was = this.getBlockState().hasProperty(SirenBlock.SOUNDING)
                && this.getBlockState().getValue(SirenBlock.SOUNDING);
        // The lamp follows the control circuit, the horn follows the rotor. A post lit
        // and silent is one waiting for drive.
        this.setStates(this.wants(), wailing);
        if (wailing && !was) {
            this.keepalive = KEEPALIVE_TICKS;
            this.announce();
        }
    }

    /**
     * Tells everyone in earshot to hold a pair of voices open on this post.
     * <p>
     * Deliberately not {@code playSound}. A sample fired that way cannot be recalled, so a
     * post broken mid-wail went on howling to the end of its clip; and each layer carried
     * a fixed distance of its own, so walking out of the near one's range swapped the
     * wail for the rumble mid-note instead of crossing between them.
     */
    private void announce() {
        if (!(this.level instanceof ServerLevel server)) {
            return;
        }
        int remaining = Math.max(this.lingerTicks, this.held ? HELD_GRACE : 0);
        float voice = this.voice();
        this.announcedVoice = voice;
        var payload = new com.cbc_more_content.network.SirenWailPayload(this.worldPosition, remaining, voice);
        double reachSqr = AUDIBLE * AUDIBLE;
        double x = this.worldPosition.getX() + 0.5D;
        double y = this.worldPosition.getY() + 0.5D;
        double z = this.worldPosition.getZ() + 0.5D;
        for (ServerPlayer player : server.players()) {
            if (player.distanceToSqr(x, y, z) <= reachSqr) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    /** Anything inbound, falling or already going off worth shouting about. */
    private boolean threatNearby(ServerLevel level, BlockPos pos) {
        Vec3 here = Vec3.atCenterOf(pos);
        double radius = this.settings.radius();
        AABB watched = new AABB(here, here).inflate(radius);
        double radiusSqr = radius * radius;

        // Asked by class rather than sweeping everything alive in a quarter-kilometre
        // box and sorting it out afterwards.
        if (this.settings.watchMissiles()) {
            for (CruiseMissileProjectile missile : level.getEntitiesOfClass(CruiseMissileProjectile.class, watched)) {
                // The scan box is square and the watched area is not.
                if (missile.distanceToSqr(here) <= radiusSqr && inbound(missile, here)) {
                    return true;
                }
            }
        }
        if (!this.settings.watchBombs()) {
            return false;
        }
        // Something already going off counts too — a shell that arrives without warning
        // still means the place is under fire, and it is the reason to sound the post
        // even though there was nothing to see beforehand.
        if (BlastLog.blastNear(level, here, radius)) {
            return true;
        }
        for (Entity bomb : level.getEntitiesOfClass(DropBombProjectile.class, watched)) {
            if (falling(bomb, here, radiusSqr)) {
                return true;
            }
        }
        for (Entity bomb : level.getEntitiesOfClass(SeaBombProjectile.class, watched)) {
            if (falling(bomb, here, radiusSqr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * In range and in the air.
     * <p>
     * A bomb only exists as an entity while it is falling — on the rack it is a block and
     * on the ground it is a crater — so being there at all is very nearly the whole test.
     * The old threshold of a sixth of a block per tick threw away the opening ticks of
     * every drop, which for a low release is the entire fall.
     */
    private static boolean falling(Entity bomb, Vec3 here, double radiusSqr) {
        return !bomb.isRemoved()
                && bomb.distanceToSqr(here) <= radiusSqr
                && bomb.getDeltaMovement().lengthSqr() > MOVING * MOVING;
    }

    /**
     * Whether a missile is coming this way rather than merely passing through the circle.
     * <p>
     * A cruise missile crossing the sky on its way somewhere else is not this post's
     * business, and a siren that howled at every overflight would tell nobody anything.
     */
    private static boolean inbound(CruiseMissileProjectile missile, Vec3 here) {
        Vec3 motion = missile.getDeltaMovement();
        if (motion.lengthSqr() < 1.0E-4D) {
            return false;
        }
        Vec3 toPost = here.subtract(missile.position());
        if (toPost.lengthSqr() < 1.0E-4D) {
            return true;
        }
        return motion.normalize().dot(toPost.normalize()) >= INBOUND_DOT;
    }

    /** Both lights are block states, so they draw without anything being synced. */
    private void setStates(boolean powered, boolean sounding) {
        if (this.level == null) {
            return;
        }
        BlockState state = this.level.getBlockState(this.worldPosition);
        if (!state.hasProperty(SirenBlock.POWERED) || !state.hasProperty(SirenBlock.SOUNDING)) {
            return;
        }
        if (state.getValue(SirenBlock.POWERED) == powered && state.getValue(SirenBlock.SOUNDING) == sounding) {
            return;
        }
        this.level.setBlock(
                this.worldPosition,
                state.setValue(SirenBlock.POWERED, powered).setValue(SirenBlock.SOUNDING, sounding),
                Block.UPDATE_CLIENTS);
    }

    private void sync() {
        // Create's own path, so the settings ride the same packet as the kinetic state
        // rather than racing a second one against it.
        this.sendData();
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        this.settings = SirenSettings.load(tag.getCompound("Settings"));
        this.lingerTicks = tag.getInt("Linger");
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.put("Settings", this.settings.save());
        tag.putInt("Linger", this.lingerTicks);
    }
}
