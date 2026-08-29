package com.cbc_more_content.client.gui;

import com.cbc_more_content.network.OpenRadarSettingsPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for {@link RadarSettingsScreen}. */
@OnlyIn(Dist.CLIENT)
public final class RadarSettingsClient {
    private RadarSettingsClient() {}

    public static void handle(OpenRadarSettingsPayload payload) {
        Minecraft.getInstance().setScreen(new RadarSettingsScreen(payload.controller(), payload.settings()));
    }
}
