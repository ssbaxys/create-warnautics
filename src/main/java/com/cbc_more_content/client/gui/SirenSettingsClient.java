package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.SirenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-only entry point for {@link SirenSettingsScreen}.
 * <p>
 * The post's settings ride along on its block entity update tag, so the panel opens
 * filled in from the copy the client already has rather than asking the server for it.
 */
@OnlyIn(Dist.CLIENT)
public final class SirenSettingsClient {
    private SirenSettingsClient() {}

    public static void open(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockEntity(pos) instanceof SirenBlockEntity siren)) {
            return;
        }
        mc.setScreen(new SirenSettingsScreen(pos, siren.settings()));
    }
}
