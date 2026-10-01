package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.Aim9BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class Aim9SettingsClient {
    private Aim9SettingsClient() {}

    public static void open(BlockPos pos) {
        var mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof Aim9BlockEntity be) {
            mc.setScreen(new Aim9SettingsScreen(pos, be.enabled(), be.interceptCruise(), be.range()));
        }
    }
}
