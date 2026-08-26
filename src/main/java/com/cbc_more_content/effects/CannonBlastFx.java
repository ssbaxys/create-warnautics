package com.cbc_more_content.effects;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.bomb.BombSize;
import com.cbc_more_content.settings.WarnauticsServerSettings;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import rbasamoyai.createbigcannons.munitions.ImpactExplosion;
import rbasamoyai.createbigcannons.munitions.ShellExplosion;
import rbasamoyai.createbigcannons.munitions.autocannon.flak.FlakExplosion;
import rbasamoyai.createbigcannons.munitions.big_cannon.mortar_stone.MortarStoneExplosion;

/**
 * Dresses a Big Cannons blast with this mod's effects.
 * <p>
 * A shell already carves a crater and throws a cloud; what it does not do is the part that
 * sells the scale of it — the flash that reaches you before the sound, the low roll that
 * arrives late from a long way off, the ground left scarred well past the hole. The bombs
 * in this mod carry all of that, and there is no reason a fourteen-inch shell should land
 * more quietly than a bomb half its size.
 * <p>
 * Which blast is which is read from its radius rather than from the shell that fired it,
 * so any munition — this mod's, Big Cannons' own, or an add-on's built on the same
 * explosion types — is dressed to the size it actually is.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = {Dist.CLIENT, Dist.DEDICATED_SERVER})
public final class CannonBlastFx {
    /** Below this a blast is a bursting autocannon round, not something worth a mushroom. */
    private static final float MIN_RADIUS = 1.5f;
    /**
     * Radius bands, in blocks, for picking the profile. Read off the explosion because a
     * shell's own configured power is what decides how big the hole is.
     */
    private static final float MEDIUM_RADIUS = 4.0f;
    private static final float LARGE_RADIUS = 7.0f;
    /** Scorched, churned ground well past the hole itself. */
    private static final double SCUFF_FACTOR = 1.8D;

    /**
     * Depth of this mod's own explosions currently running.
     * <p>
     * The bombs here build a Big Cannons {@code ShellExplosion} of their own and then play
     * their effects directly, so without this every bomb in the mod would be dressed twice
     * — once by its own handler and once by this listener watching the explosion it just
     * made. Counted rather than a flag because a blast can set off a neighbouring charge
     * while it is still running.
     */
    private static int reentry;

    private CannonBlastFx() {
    }

    /** Runs a blast this mod is already dressing itself, so this listener leaves it alone. */
    public static void own(Runnable blast) {
        reentry++;
        try {
            blast.run();
        } finally {
            reentry--;
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (reentry > 0 || level.isClientSide || !(level instanceof ServerLevel server)) {
            return;
        }
        Explosion explosion = event.getExplosion();
        if (!isCannonBlast(explosion)) {
            return;
        }
        float radius = explosion.radius();
        if (!Float.isFinite(radius) || radius < MIN_RADIUS) {
            return;
        }
        if (!WarnauticsServerSettings.get(server).cannonFx()) {
            return;
        }

        Vec3 at = explosion.center();
        BombSize size = sizeFor(radius);
        try {
            BombBlastFx.play(server, at, size, radius);
            // Past the crater rim the ground is churned rather than removed, so the shell
            // leaves a scar rather than a clean hole in an untouched field.
            BlastScorch.scuff(server, at, radius * SCUFF_FACTOR, 1.0f);
            // The blocks the blast is about to take, read before it takes them, so the
            // debris carries the model that was actually standing there.
            BlastDebris.fling(server, at, event.getAffectedBlocks());
        } catch (Throwable t) {
            // A blast that cannot be dressed still has to go off.
            CBCMoreContent.LOGGER.debug("Cannon blast FX failed at {}: {}", at, t.toString());
        }
    }

    /**
     * Whether this is a cannon munition going off rather than a creeper or a bed.
     * <p>
     * Shrapnel bursts are left out on purpose: they are a cloud of fragments, not a
     * detonation, and giving each one a mushroom would bury the map in them.
     */
    private static boolean isCannonBlast(Explosion explosion) {
        return explosion instanceof ShellExplosion
                || explosion instanceof ImpactExplosion
                || explosion instanceof MortarStoneExplosion
                || explosion instanceof FlakExplosion;
    }

    private static BombSize sizeFor(float radius) {
        if (radius >= LARGE_RADIUS) {
            return BombSize.LARGE;
        }
        return radius >= MEDIUM_RADIUS ? BombSize.MEDIUM : BombSize.SMALL;
    }
}
