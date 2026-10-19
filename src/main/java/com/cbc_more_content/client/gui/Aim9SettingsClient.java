package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.Aim9BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class Aim9SettingsClient {
    private Aim9SettingsClient() {}

    /** The server's answer to the panel's status question. */
    public static void status(com.cbc_more_content.network.Aim9StatusPayload payload) {
        if (Minecraft.getInstance().screen instanceof Aim9SettingsScreen screen) {
            screen.onStatus(payload);
        }
    }

    public static void open(BlockPos pos) {
        var mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof Aim9BlockEntity be) {
            mc.setScreen(new Aim9SettingsScreen(pos, be.enabled(), be.interceptCruise(), be.range()));
        }
    }
}
