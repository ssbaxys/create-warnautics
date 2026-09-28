package com.cbc_more_content.mixin.compat.simulated;

import com.cbc_more_content.client.ChainModels;
import com.cbc_more_content.compat.simulated.ChainConnection;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.RopeStrandRenderer;
import dev.simulated_team.simulated.index.SimPartialModels;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = RopeStrandRenderer.class, remap = false)
public abstract class ChainRenderTypeMixin {
    @Redirect(
            method = "render",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Ldev/simulated_team/simulated/index/SimPartialModels;ROPE:Ldev/engine_room/flywheel/lib/model/baked/PartialModel;"))
    private static PartialModel warnautics$strand(
            SmartBlockEntity be,
            RopeStrandHolderBehavior holder,
            float partialTicks,
            PoseStack pose,
            MultiBufferSource buffer) {
        return ChainConnection.isChain(holder) ? ChainModels.STRAND : SimPartialModels.ROPE;
    }

    @Redirect(
            method = "render",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Ldev/simulated_team/simulated/index/SimPartialModels;ROPE_KNOT:Ldev/engine_room/flywheel/lib/model/baked/PartialModel;"))
    private static PartialModel warnautics$knot(
            SmartBlockEntity be,
            RopeStrandHolderBehavior holder,
            float partialTicks,
            PoseStack pose,
            MultiBufferSource buffer) {
        return ChainConnection.isChain(holder) ? ChainModels.KNOT : SimPartialModels.ROPE_KNOT;
    }

    @Redirect(
            method = "render",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/RenderType;solid()Lnet/minecraft/client/renderer/RenderType;"))
    private static RenderType warnautics$chainCutout(
            SmartBlockEntity be,
            RopeStrandHolderBehavior holder,
            float partialTicks,
            PoseStack pose,
            MultiBufferSource buffer) {
        return ChainConnection.isChain(holder) ? RenderType.cutoutMipped() : RenderType.solid();
    }
}
