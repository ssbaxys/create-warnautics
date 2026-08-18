package com.cbc_more_content.effects;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Claim-protection gate for block destruction that does not go through a vanilla
 * explosion.
 * <p>
 * Protection mods (FTB Chunks, GriefPrevention, …) veto blocks by filtering
 * {@link ExplosionEvent.Detonate}. Any path that carves terrain itself has to post that
 * event on its own, or it silently digs through claimed land.
 */
public final class BlastProtection {
    private BlastProtection() {
    }

    /**
     * Posts {@link ExplosionEvent.Detonate} for {@code candidates} and returns the
     * positions still allowed to break. On failure the raw list is returned — a broken
     * listener must not make explosives inert.
     */
    public static Set<BlockPos> filter(
            ServerLevel level,
            Vec3 center,
            float power,
            Collection<BlockPos> candidates) {
        Set<BlockPos> allowed = new HashSet<>(Math.max(16, candidates.size() * 2));
        if (candidates.isEmpty()) {
            return allowed;
        }
        try {
            Explosion explosion = new Explosion(
                    level, null, center.x, center.y, center.z, power,
                    false, Explosion.BlockInteraction.DESTROY);
            List<BlockPos> toBlow = explosion.getToBlow();
            toBlow.addAll(candidates);
            NeoForge.EVENT_BUS.post(new ExplosionEvent.Detonate(level, explosion, new ArrayList<>()));
            allowed.addAll(toBlow);
        } catch (Throwable ignored) {
            allowed.addAll(candidates);
        }
        return allowed;
    }
}
