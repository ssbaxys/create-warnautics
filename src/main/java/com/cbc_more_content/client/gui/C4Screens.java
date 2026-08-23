package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.C4Block;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Shared checks for the two panels that hang off a placed charge. */
@OnlyIn(Dist.CLIENT)
public final class C4Screens {
    private C4Screens() {
    }

    /** False once the charge has detonated, been broken, or fallen off its surface. */
    public static boolean stillThere(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getBlockState(pos).getBlock() instanceof C4Block;
    }
}
