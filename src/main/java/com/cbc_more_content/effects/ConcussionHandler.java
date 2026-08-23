package com.cbc_more_content.effects;

import com.cbc_more_content.network.ConcussionPayload;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Shell shock for players who live through a near miss.
 * <p>
 * Blast and sound do not travel the same way, so they are reported separately: light and
 * pressure need a path, while sound diffracts around cover. Someone sheltering behind
 * armour metres from a detonation gets their ears rung and nothing else.
 */
public final class ConcussionHandler {
    /** Below this share of the blast radius nothing is sent at all. */
    private static final double MIN_FALLOFF = 0.18D;
    /** Length of bomb_concussion.ogg — a full-strength hit rings for the whole track. */
    private static final int MAX_DURATION_TICKS = 300;
    private static final int MIN_DURATION_TICKS = 60;
    /** Cover this thick removes the visual shock, leaving only the ringing. */
    private static final double VISUAL_CUTOFF = 0.12D;
    /**
     * Inside this share of the radius the pressure wave wraps the target and cover stops
     * counting. Bombs bury their burst slightly inside whatever they struck, so without
     * this the cover rays run through the ground the charge sits in and a direct hit
     * reports near-total shielding — the exact inverse of what should happen.
     */
    private static final double NEAR_FIELD = 0.62D;

    private ConcussionHandler() {
    }

    /**
     * @param falloff      1 at the blast centre, 0 at the edge of the damage radius
     * @param transmission share of blast energy that got through cover
     * @param lineOfSight  whether any sample ray reached the player unobstructed
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
        // Ramp the usable band up to a full 0..1; the square root front-loads it so
        // anything short of a distant miss lands hard.
        float ranged = (float) Math.sqrt(Mth.clamp(
                (falloff - MIN_FALLOFF) / (1.0D - MIN_FALLOFF), 0.0D, 1.0D));
        float powerBias = Mth.clamp(entityPower / 16.0f, 0.45f, 1.0f);

        double nearField = Mth.clamp((falloff - NEAR_FIELD) / (1.0D - NEAR_FIELD), 0.0D, 1.0D);
        double effective = Math.max(transmission, nearField);
        boolean reached = lineOfSight || nearField > 0.0D;

        // A wall muffles a blast, it does not silence one going off behind it: full
        // shielding still passes 70% of the audio.
        float muffle = 0.7f + 0.3f * (float) effective;
        float audio = Mth.clamp(ranged * (0.7f + 0.3f * powerBias) * muffle, 0.05f, 1.0f);

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

        // Played client-side as a UI sound: the ringing belongs inside the affected
        // player's head, and a world sound would let bystanders hear it.
        PacketDistributor.sendToPlayer(player, new ConcussionPayload(visual, audio, duration));
    }
}
