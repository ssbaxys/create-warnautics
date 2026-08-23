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
 * Claim-protection gate for block destruction that does not run through a vanilla
 * explosion. Protection mods veto blocks by filtering {@link ExplosionEvent.Detonate},
 * so any path that carves terrain itself has to post that event or it digs through
 * claimed land unchallenged.
 */
public final class BlastProtection {
    private BlastProtection() {
    }

    /**
     * @return the subset of {@code candidates} still allowed to break. On failure the raw
     *         list comes back — a broken listener must not make explosives inert.
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
