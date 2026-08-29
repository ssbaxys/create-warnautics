package com.cbc_more_content.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for {@link MissileTargetScreen}. */
@OnlyIn(Dist.CLIENT)
public final class MissileTargetClient {
    private MissileTargetClient() {}

    public static void open(BlockPos pos, BlockPos current, int mode) {
        Minecraft.getInstance().setScreen(new MissileTargetScreen(pos, current, mode));
    }
}
