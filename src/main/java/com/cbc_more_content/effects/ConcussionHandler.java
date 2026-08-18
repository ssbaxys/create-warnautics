package com.cbc_more_content.effects;

import com.cbc_more_content.network.ConcussionPayload;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Shell shock for players who live through a near miss.
 * <p>
 * Blast and sound do not travel the same way, so they are reported separately. Light and
 * pressure need a path: behind a wall that holds, there is no flash and no punch. Sound
 * diffracts around cover and through it, so someone sheltering behind armour a few metres
 * from a detonation still gets their ears rung even though nothing reached them visually.
 */
public final class ConcussionHandler {
    /** Below this share of the blast radius nothing is sent at all. */
    private static final double MIN_FALLOFF = 0.18D;
    /** Length of bomb_concussion.ogg — a full-strength hit rings for the whole track. */
    private static final int MAX_DURATION_TICKS = 300;
    /** A hit right at the threshold still rings, just briefly. */
    private static final int MIN_DURATION_TICKS = 60;
    /** Cover this thick removes the visual shock entirely, leaving only the ringing. */
    private static final double VISUAL_CUTOFF = 0.12D;
    /**
     * Inside this share of the blast radius the pressure wave wraps the target and cover
     * stops meaning anything.
     * <p>
     * Bombs bury their detonation point slightly inside whatever they struck, so for
     * someone standing on the impact the cover rays run through the ground the charge is
     * sitting in and report near-total shielding. The result was the exact inverse of
     * what should happen: a direct hit produced no shock while a moderate miss did.
     */
    private static final double NEAR_FIELD = 0.62D;

    private ConcussionHandler() {
    }

    /**
     * @param falloff       1 at the blast centre, 0 at the edge of the damage radius
     * @param transmission  share of blast energy that got through cover
     * @param lineOfSight   whether any sample ray reached the player unobstructed
     */
    public static void offer(
            ServerPlayer player,
            float entityPower,
            double falloff,
            double transmission,
            boolean lineOfSight) {
        if (falloff < MIN_FALLOFF) {
            return;
        }
        // Ramp the usable band (MIN_FALLOFF..1) up to a full 0..1 so a hit right at the
        // threshold still reads as a faint ring rather than nothing. The square root
        // front-loads it, so anything short of a distant miss lands hard.
        float ranged = (float) Math.sqrt(Mth.clamp(
                (falloff - MIN_FALLOFF) / (1.0D - MIN_FALLOFF), 0.0D, 1.0D));
        // Heavier charges ring longer even at the same relative distance.
        float powerBias = Mth.clamp(entityPower / 16.0f, 0.45f, 1.0f);

        // Close in, the blast envelops the target regardless of what the cover rays say.
        double nearField = Mth.clamp((falloff - NEAR_FIELD) / (1.0D - NEAR_FIELD), 0.0D, 1.0D);
        double effective = Math.max(transmission, nearField);
        boolean reached = lineOfSight || nearField > 0.0D;

        // Audio ignores cover almost entirely — a wall muffles a blast, it does not
        // silence one going off on the other side of it. Written out rather than
        // interpolated so the floor is obvious: full shielding still passes 70%.
        float muffle = 0.7f + 0.3f * (float) effective;
        float audio = Mth.clamp(ranged * (0.7f + 0.3f * powerBias) * muffle, 0.05f, 1.0f);

        // The white-out and defocus need the blast to have actually reached the eye.
        float visual = 0.0f;
        if (reached && effective > VISUAL_CUTOFF) {
            visual = Mth.clamp(
                    ranged * (float) Math.sqrt(effective) * (0.72f + 0.28f * powerBias),
                    0.0f, 1.0f);
        }

        if (audio <= 0.02f && visual <= 0.02f) {
            return;
        }

        int duration = Math.round(Mth.lerp(
                Math.max(audio, visual), MIN_DURATION_TICKS, MAX_DURATION_TICKS));

        // The ringing is played client-side as a UI sound, not from the world: it belongs
        // inside the affected player's head, and a world sound here would let bystanders
        // hear someone else's concussion.
        PacketDistributor.sendToPlayer(player, new ConcussionPayload(visual, audio, duration));
    }
}
