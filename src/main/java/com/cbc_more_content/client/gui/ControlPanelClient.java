package com.cbc_more_content.client.gui;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for {@link ControlPanelScreen}. */
@OnlyIn(Dist.CLIENT)
public final class ControlPanelClient {
    private ControlPanelClient() {}

    public static void open(boolean cannonFx) {
        Minecraft.getInstance().setScreen(new ControlPanelScreen(cannonFx));
    }
}
