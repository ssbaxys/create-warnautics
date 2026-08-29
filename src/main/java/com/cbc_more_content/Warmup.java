package com.cbc_more_content;

/**
 * Loads the detonation code paths ahead of the first detonation.
 * <p>
 * A blast touches all of these classes for the first time at once, and the JVM
 * resolves and verifies every one inside that single tick — the stall players see
 * on the first bomb of a session. This only forces linkage.
 */
public final class Warmup {
    private static final String[] COMMON = {
        "com.cbc_more_content.effects.BombExplosionHandler",
        "com.cbc_more_content.effects.BombBlastFx",
        "com.cbc_more_content.effects.BombBurstBudget",
        "com.cbc_more_content.effects.BlastCover",
        "com.cbc_more_content.effects.BlastFracture",
        "com.cbc_more_content.effects.BlastProtection",
        "com.cbc_more_content.effects.BlastRubble",
        "com.cbc_more_content.effects.BombSympatheticDetonation",
        "com.cbc_more_content.effects.ConcussionHandler",
        "com.cbc_more_content.effects.MineExplosionHandler",
        "com.cbc_more_content.damage.BombDamageSource",
        "com.cbc_more_content.damage.MineDamageSource",
        "com.cbc_more_content.damage.ModDamageTypes",
        "com.cbc_more_content.network.BombFlashPayload",
        "com.cbc_more_content.network.ConcussionPayload",
        "rbasamoyai.createbigcannons.munitions.ShellExplosion",
    };

    private static final String[] CLIENT = {
        "com.cbc_more_content.client.BombFlashClient",
        "com.cbc_more_content.client.BombFlashOverlay",
        "com.cbc_more_content.client.ConcussionClient",
        "com.cbc_more_content.client.FlashRenderMode",
    };

    private Warmup() {}

    public static void common() {
        load(COMMON);
    }

    public static void client() {
        load(CLIENT);
    }

    private static void load(String[] names) {
        ClassLoader loader = Warmup.class.getClassLoader();
        for (String name : names) {
            try {
                Class.forName(name, true, loader);
            } catch (Throwable ignored) {
                // An absent optional class is not a reason to fail startup.
            }
        }
    }
}
