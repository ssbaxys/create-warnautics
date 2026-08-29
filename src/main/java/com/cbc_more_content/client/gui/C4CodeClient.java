package com.cbc_more_content.client.gui;

import com.cbc_more_content.network.C4CodeResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for {@link C4CodeScreen}. */
@OnlyIn(Dist.CLIENT)
public final class C4CodeClient {
    private C4CodeClient() {}

    public static void open(BlockPos pos, boolean disarming) {
        Minecraft.getInstance().setScreen(new C4CodeScreen(pos, disarming));
    }

    /** Routed here from the network handler; ignored if the player closed the keypad. */
    public static void handle(C4CodeResultPayload payload) {
        if (Minecraft.getInstance().screen instanceof C4CodeScreen screen) {
            screen.onVerdict(payload.accepted());
        }
    }
}
