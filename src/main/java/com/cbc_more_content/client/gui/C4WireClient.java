package com.cbc_more_content.client.gui;

import com.cbc_more_content.block.C4BlockEntity.WireResult;
import com.cbc_more_content.network.C4WireResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for {@link C4WireScreen}. */
@OnlyIn(Dist.CLIENT)
public final class C4WireClient {
    private C4WireClient() {}

    public static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new C4WireScreen(pos));
    }

    public static void handle(C4WireResultPayload payload) {
        if (Minecraft.getInstance().screen instanceof C4WireScreen screen) {
            WireResult[] results = WireResult.values();
            int index = Math.max(0, Math.min(payload.outcome(), results.length - 1));
            screen.onResult(payload.wire(), results[index]);
        }
    }
}
