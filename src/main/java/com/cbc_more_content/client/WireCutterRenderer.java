package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws the wire cutters with their handles hinged, so they close as the arm swings.
 * <p>
 * The angle is read from the holder's swing rather than from a local timer, because a
 * swing is already replicated to everyone — that is what makes the snip look the same
 * in your own hands and from across the room, without a packet of its own.
 */
@OnlyIn(Dist.CLIENT)
public class WireCutterRenderer extends BlockEntityWithoutLevelRenderer {
    /** The rivet, from the authored element rotation origins. */
    private static final float PIVOT_X = 8.0f / 16.0f;

    private static final float PIVOT_Y = 5.5f / 16.0f;
    private static final float PIVOT_Z = 7.5f / 16.0f;
    /** How far each handle travels at the tightest point of the squeeze. */
    private static final float MAX_ANGLE = 18.0f;

    public static final ModelResourceLocation FULL = standalone("wire_cutters");
    public static final ModelResourceLocation RIGHT = standalone("wire_cutters_right");
    public static final ModelResourceLocation LEFT = standalone("wire_cutters_left");
    public static final ModelResourceLocation CENTER = standalone("wire_cutters_center");

    public WireCutterRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelSet models) {
        super(dispatcher, models);
    }

    private static ModelResourceLocation standalone(String path) {
        return ModelResourceLocation.standalone(
                ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "item/" + path));
    }

    @Override
    public void renderByItem(
            ItemStack stack,
            ItemDisplayContext context,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            int overlay) {
        Minecraft mc = Minecraft.getInstance();
        ItemRenderer items = mc.getItemRenderer();
        BakedModel full = mc.getModelManager().getModel(FULL);

        boolean leftHand = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;

        pose.pushPose();
        // Same order ItemRenderer uses: place the model, then move its origin to a corner.
        full.applyTransform(context, pose, leftHand);
        pose.translate(-0.5f, -0.5f, -0.5f);

        float angle = squeeze(stack, context);
        renderPart(items, mc.getModelManager().getModel(CENTER), stack, pose, buffers, light, overlay);
        renderHinged(items, mc.getModelManager().getModel(RIGHT), stack, pose, buffers, light, overlay, angle);
        renderHinged(items, mc.getModelManager().getModel(LEFT), stack, pose, buffers, light, overlay, -angle);
        pose.popPose();
    }

    private void renderHinged(
            ItemRenderer items,
            BakedModel model,
            ItemStack stack,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            int overlay,
            float angle) {
        pose.pushPose();
        pose.translate(PIVOT_X, PIVOT_Y, PIVOT_Z);
        pose.mulPose(Axis.ZP.rotationDegrees(angle));
        pose.translate(-PIVOT_X, -PIVOT_Y, -PIVOT_Z);
        renderPart(items, model, stack, pose, buffers, light, overlay);
        pose.popPose();
    }

    private void renderPart(
            ItemRenderer items,
            BakedModel model,
            ItemStack stack,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            int overlay) {
        items.renderModelLists(
                model,
                stack,
                light,
                overlay,
                pose,
                buffers.getBuffer(net.minecraft.client.renderer.Sheets.translucentCullBlockSheet()));
    }

    /**
     * Handle travel for this frame. A half sine over the swing closes the jaws and opens
     * them again with no corner at either end, which is what keeps the motion smooth.
     */
    private static float squeeze(ItemStack stack, ItemDisplayContext context) {
        LivingEntity holder = holderOf(stack, context);
        if (holder == null) {
            return 0.0f;
        }
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        float progress = Mth.clamp(holder.getAttackAnim(partial), 0.0f, 1.0f);
        return Mth.sin(progress * Mth.PI) * MAX_ANGLE;
    }

    /**
     * Whose hand this is. First person is always the local player; for anyone else the
     * rendered stack is the very object their hand holds, so identity finds them.
     */
    private static LivingEntity holderOf(ItemStack stack, ItemDisplayContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
            return mc.player;
        }
        if (context != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                && context != ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
            return null;
        }
        if (mc.level == null) {
            return null;
        }
        for (net.minecraft.world.entity.player.Player player : mc.level.players()) {
            if (player.getMainHandItem() == stack || player.getOffhandItem() == stack) {
                return player;
            }
        }
        return null;
    }
}
