package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.RocketPodBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for {@link RocketPodSettingsScreen}. */
@OnlyIn(Dist.CLIENT)
public final class RocketPodSettingsClient {
    private RocketPodSettingsClient() {}

    public static void open(BlockPos body) {
        var mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(body) instanceof RocketPodBlockEntity pod) {
            mc.setScreen(new RocketPodSettingsScreen(body, pod.rippleTicks(), pod.salvoSize(), pod.rockets()));
        }
    }
}
