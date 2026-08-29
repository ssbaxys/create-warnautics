package com.cbc_more_content.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for {@link C4ModeScreen}. */
@OnlyIn(Dist.CLIENT)
public final class C4ModeClient {
    private C4ModeClient() {}

    public static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new C4ModeScreen(pos));
    }
}
