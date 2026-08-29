package com.cbc_more_content.client;

import com.cbc_more_content.block.SirenBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The stub of shaft under a siren, turning at whatever the network is giving it.
 * <p>
 * The post's own model stops at the underside of the stand and leaves the socket open,
 * which read as a hole in the block with nothing in it. Create's own half-shaft goes in
 * there, and because it is rendered rather than modelled it turns — which is the point of
 * a siren being driven at all. If it is not moving, neither is the rotor, and the block is
 * telling the truth about why it is quiet.
 * <p>
 * Deliberately not {@code super.renderSafe}: the base renderer spins the whole block, and
 * the housing is bolted to the stand. Only the shaft turns.
 */
@OnlyIn(Dist.CLIENT)
public class SirenRenderer extends KineticBlockEntityRenderer<SirenBlockEntity> {

    public SirenRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(
            SirenBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.solid());
        SuperByteBuffer shaft =
                CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, be.getBlockState(), Direction.DOWN);
        KineticBlockEntityRenderer.renderRotatingBuffer(be, shaft, ms, consumer, light);
    }
}
