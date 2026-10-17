package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.munitions.CruiseMissileProjectile;
import com.cbc_more_content.registry.ModBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws the missile in flight from the same airframe the placed block uses, split into
 * the body and its two sets of fins so the fins can deploy.
 * <p>
 * On the rail and through the boost the wings and tail fins are folded against the body;
 * once the booster drops away they spring out to full span, the way a Tomahawk's do.
 */
@OnlyIn(Dist.CLIENT)
public class CruiseMissileRenderer extends EntityRenderer<CruiseMissileProjectile> {
    public static final ModelResourceLocation BODY = part("cruise_missile_body");
    public static final ModelResourceLocation TAIL_FINS = part("cruise_missile_tail_fins");
    public static final ModelResourceLocation CANARDS = part("cruise_missile_canards");
    /** Span the fins keep while folded: stubs along the body, as on the rail. */
    private static final float FOLDED = 0.55f;

    public CruiseMissileRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    private static ModelResourceLocation part(String name) {
        return ModelResourceLocation.standalone(
                ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "entity/" + name));
    }

    @Override
    public void render(
            CruiseMissileProjectile entity,
            float yaw,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight) {
        pose.pushPose();

        float yRot = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
        float xRot = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());

        // Heading and pitch put the nose along local -X; bank rolls about that axis.
        // Local +Z is the left wing, so a positive roll (left turn) drops it into the turn.
        pose.mulPose(Axis.YP.rotationDegrees(90.0f - yRot));
        pose.mulPose(Axis.ZP.rotationDegrees(xRot));
        pose.mulPose(Axis.XP.rotation(entity.roll(partialTick)));
        pose.translate(-0.5D, -0.5D, -0.5D);

        var mc = Minecraft.getInstance();
        BlockState state = ModBlocks.CRUISE_MISSILE.get().defaultBlockState();
        VertexConsumer consumer = buffers.getBuffer(RenderType.cutout());
        var models = mc.getModelManager();
        BakedModel body = models.getModel(BODY);
        if (body == models.getMissingModel()) {
            // Parts unavailable (a resource pack replaced them): fall back to the whole airframe.
            draw(mc, pose, consumer, state, mc.getBlockRenderer().getBlockModel(state), packedLight);
            pose.popPose();
            super.render(entity, yaw, partialTick, pose, buffers, packedLight);
            return;
        }

        // The parts are authored nose-along +Z; the block variant turns them a quarter
        // turn to nose-along -X, so do the same here.
        pose.translate(0.5D, 0.5D, 0.5D);
        pose.mulPose(Axis.YP.rotationDegrees(-90.0f));
        pose.translate(-0.5D, -0.5D, -0.5D);
        draw(mc, pose, consumer, state, body, packedLight);

        float deploy = entity.wingDeploy(partialTick);
        // The tail fins come out first; the forward fins a beat later.
        drawFins(mc, pose, consumer, state, models.getModel(TAIL_FINS), packedLight, deploy);
        drawFins(
                mc,
                pose,
                consumer,
                state,
                models.getModel(CANARDS),
                packedLight,
                Mth.clamp((deploy - 0.15f) / 0.85f, 0, 1.12f));

        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
    }

    /** Fins scaled about the body's long axis: folded stubs at 0, full span at 1. */
    private static void drawFins(
            Minecraft mc,
            PoseStack pose,
            VertexConsumer consumer,
            BlockState state,
            BakedModel fins,
            int light,
            float deploy) {
        float span = Mth.lerp(deploy, FOLDED, 1.0f);
        pose.pushPose();
        pose.translate(0.5D, 0.5D, 0.0D);
        pose.scale(span, span, 1.0f);
        pose.translate(-0.5D, -0.5D, 0.0D);
        draw(mc, pose, consumer, state, fins, light);
        pose.popPose();
    }

    private static void draw(
            Minecraft mc, PoseStack pose, VertexConsumer consumer, BlockState state, BakedModel model, int light) {
        mc.getBlockRenderer()
                .getModelRenderer()
                .renderModel(pose.last(), consumer, state, model, 1.0f, 1.0f, 1.0f, light, OverlayTexture.NO_OVERLAY);
    }

    @Override
    public ResourceLocation getTextureLocation(CruiseMissileProjectile entity) {
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }
}
