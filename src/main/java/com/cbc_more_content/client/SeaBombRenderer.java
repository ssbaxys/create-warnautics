package com.cbc_more_content.client;

import com.cbc_more_content.block.DropBombBlock;
import com.cbc_more_content.munitions.SeaBombProjectile;
import com.cbc_more_content.registry.ModBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonProjectileRenderer;
import rbasamoyai.createbigcannons.utils.CBCUtils;

/**
 * Renders the author-model propeller itself as the animated rotor. No auxiliary
 * red texture, translucent ghost, or second propeller is created.
 */
public class SeaBombRenderer extends BigCannonProjectileRenderer<SeaBombProjectile> {
    public SeaBombRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(
            SeaBombProjectile entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);

        float degreesPerTick = switch (entity.phase()) {
            case SeaBombProjectile.PHASE_SWIM -> 72.0f;
            case SeaBombProjectile.PHASE_SINK -> 28.0f;
            default -> 12.0f;
        };
        float spin = entity.clientPropellerDegrees(partialTick, degreesPerTick);

        Vec3 orientation = entity.getOrientation();
        if (orientation.lengthSqr() < 1.0E-4D) {
            orientation = new Vec3(0.0D, -1.0D, 0.0D);
        }

        poseStack.pushPose();
        applyProjectileOrientation(poseStack, orientation);
        poseStack.translate(-0.5D, -0.5D, -0.5D);

        // The NORTH block-state bake rotates the vertical model into CBC's local
        // Z projectile axis. Rotate around that exact axis through the model center.
        poseStack.translate(0.5D, 0.5D, 0.5D);
        poseStack.mulPose(Axis.ZP.rotationDegrees(spin));
        poseStack.translate(-0.5D, -0.5D, -0.5D);

        BlockState rotor = ModBlocks.SEA_BOMB.get().defaultBlockState()
                .setValue(DropBombBlock.FACING, Direction.NORTH)
                .setValue(DropBombBlock.POWERED, true)
                .setValue(DropBombBlock.CASSETTE, 3);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                rotor,
                poseStack,
                buffer,
                packedLight,
                OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    private static void applyProjectileOrientation(PoseStack poseStack, Vec3 orientation) {
        if (orientation.horizontalDistanceSqr() > 1.0E-4D
                && Math.abs(orientation.y) > 0.01D) {
            Vec3 horizontal = new Vec3(orientation.x, 0.0D, orientation.z).normalize();
            poseStack.mulPose(CBCUtils.mat4x4fFacing(orientation.normalize().reverse(), horizontal));
            poseStack.mulPose(CBCUtils.mat4x4fFacing(horizontal));
        } else {
            poseStack.mulPose(CBCUtils.mat4x4fFacing(orientation.normalize()));
        }
    }
}
