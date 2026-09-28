package com.cbc_more_content.mixin.compat.simulated;

import com.cbc_more_content.client.ChainModels;
import com.cbc_more_content.compat.simulated.ChainConnection;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.simulated_team.simulated.content.blocks.rope.rope_connector.RopeConnectorBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.rope_connector.RopeConnectorRenderer;
import dev.simulated_team.simulated.index.SimPartialModels;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = RopeConnectorRenderer.class, remap = false)
public abstract class ChainConnectorRenderTypeMixin {
    @Redirect(
            method = "renderSafe",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Ldev/simulated_team/simulated/index/SimPartialModels;ROPE_CONNECTOR_KNOT:Ldev/engine_room/flywheel/lib/model/baked/PartialModel;"))
    private PartialModel warnautics$connectorKnot(
            RopeConnectorBlockEntity be,
            float partialTicks,
            PoseStack pose,
            MultiBufferSource buffer,
            int light,
            int overlay) {
        return ChainConnection.isChain(be.getRopeHolder())
                ? ChainModels.CONNECTOR_KNOT
                : SimPartialModels.ROPE_CONNECTOR_KNOT;
    }

    @Redirect(
            method = "renderSafe",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/RenderType;solid()Lnet/minecraft/client/renderer/RenderType;"))
    private RenderType warnautics$chainCutout(
            RopeConnectorBlockEntity be,
            float partialTicks,
            PoseStack pose,
            MultiBufferSource buffer,
            int light,
            int overlay) {
        return ChainConnection.isChain(be.getRopeHolder()) ? RenderType.cutoutMipped() : RenderType.solid();
    }
}
