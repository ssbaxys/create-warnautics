package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.munitions.CruiseMissileProjectile;

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
 * Hangs a Veil point light on the nozzle of every missile under power nearby.
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
            return;
        }

        AABB area = player.getBoundingBox().inflate(RANGE);
        for (CruiseMissileProjectile missile
                : level.getEntitiesOfClass(CruiseMissileProjectile.class, area)) {
            com.cbc_more_content.client.veil.VeilMissileFx.follow(
                    missile, nozzleOf(missile), missile.isPowered());
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
