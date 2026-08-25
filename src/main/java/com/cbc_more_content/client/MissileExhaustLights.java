package com.cbc_more_content.client;

import java.util.HashSet;
import java.util.Set;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.registry.ModParticles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Hangs a Veil point light on the nozzle of every missile under power nearby, and fires
 * the one-shot ignition burst the tick a cold-launched missile's motor catches.
 * <p>
 * Driven from a client tick rather than from the projectile, so nothing on the common
 * side ever names a Veil class: on a dedicated server, or with Veil absent, this handler
 * simply never resolves it.
 */
@EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
public final class MissileExhaustLights {
    private static final double RANGE = 160.0D;
    /** Distance behind the airframe the nozzle sits, matching the plume. */
    private static final double NOZZLE_OFFSET = 1.6D;

    /** Missiles seen still ejecting last tick, so ignition is caught on the falling edge. */
    private static final Set<Integer> WAS_EJECTING = new HashSet<>();

    private static Boolean veil;

    private MissileExhaustLights() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (veil == null) {
            veil = ModList.get().isLoaded("veil");
        }
        if (!veil) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            WAS_EJECTING.clear();
            return;
        }

        AABB area = player.getBoundingBox().inflate(RANGE);
        Set<Integer> stillEjecting = new HashSet<>();
        for (CruiseMissileProjectile missile
                : level.getEntitiesOfClass(CruiseMissileProjectile.class, area)) {
            Vec3 nozzle = nozzleOf(missile);
            com.cbc_more_content.client.veil.VeilMissileFx.follow(
                    missile, nozzle, missile.isPowered());

            if (missile.isEjecting()) {
                stillEjecting.add(missile.getId());
            } else if (WAS_EJECTING.contains(missile.getId())) {
                ignite(level, missile, nozzle);
            }
        }
        WAS_EJECTING.clear();
        WAS_EJECTING.addAll(stillEjecting);

        com.cbc_more_content.client.veil.VeilMissileFx.tickFlashes();
    }

    /**
     * The instant the motor catches: a bright Veil light burst plus a spray of the
     * mod's own hot exhaust particle thrown out radially, in place of a vanilla flash.
     */
    private static void ignite(ClientLevel level, CruiseMissileProjectile missile, Vec3 nozzle) {
        com.cbc_more_content.client.veil.VeilMissileFx.ignite(nozzle);
        var random = level.random;
        for (int i = 0; i < 18; i++) {
            double dx = random.nextDouble() - 0.5D;
            double dy = random.nextDouble() - 0.5D;
            double dz = random.nextDouble() - 0.5D;
            double len = Math.max(1.0E-4D, Math.sqrt(dx * dx + dy * dy + dz * dz));
            double speed = 0.12D + random.nextDouble() * 0.18D;
            level.addParticle(ModParticles.MISSILE_EXHAUST.get(), true,
                    nozzle.x, nozzle.y, nozzle.z,
                    dx / len * speed, dy / len * speed, dz / len * speed);
        }
    }

    /** Behind the airframe along its own heading, where the plume is drawn. */
    private static Vec3 nozzleOf(CruiseMissileProjectile missile) {
        float yaw = (missile.getYRot() + 90.0f) * Mth.DEG_TO_RAD;
        float pitch = -missile.getXRot() * Mth.DEG_TO_RAD;
        double cos = Mth.cos(pitch);
        Vec3 heading = new Vec3(Mth.cos(yaw) * cos, Mth.sin(pitch), Mth.sin(yaw) * cos);
        if (heading.lengthSqr() < 1.0E-6D) {
            return missile.position();
        }
        return missile.position().add(heading.normalize().scale(-NOZZLE_OFFSET));
    }
}
