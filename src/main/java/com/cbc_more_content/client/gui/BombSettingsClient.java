package com.cbc_more_content.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for {@link BombSettingsScreen}. */
@OnlyIn(Dist.CLIENT)
public final class BombSettingsClient {
    private BombSettingsClient() {
    }

    public static void open(BlockPos pos, int storedDelay, int cassette) {
        Minecraft.getInstance().setScreen(new BombSettingsScreen(pos, storedDelay, cassette));
    }
}
