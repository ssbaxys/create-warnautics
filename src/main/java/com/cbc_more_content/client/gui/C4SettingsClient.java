package com.cbc_more_content.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for {@link C4SettingsScreen}. */
@OnlyIn(Dist.CLIENT)
public final class C4SettingsClient {
    private C4SettingsClient() {
    }

    public static void open(BlockPos pos, int seconds) {
        Minecraft.getInstance().setScreen(new C4SettingsScreen(pos, seconds));
    }
}
