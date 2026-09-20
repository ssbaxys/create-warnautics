package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.item.BombVestItem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class BombVestLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {
    public static final ModelResourceLocation LINKED_MODEL = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "item/bomb_vest_linked"));

    private final ItemInHandRenderer itemInHandRenderer;

    public BombVestLayer(RenderLayerParent<T, M> renderer, ItemInHandRenderer itemInHandRenderer) {
        super(renderer);
        this.itemInHandRenderer = itemInHandRenderer;
    }

    @Override
    public void render(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            T entity,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch) {
        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        if (!(chest.getItem() instanceof BombVestItem)) {
            return;
        }
        boolean linked = BombVestItem.isLinked(chest);
        boolean lit = linked && ((entity.tickCount + partialTicks) % 60.0f) < 30.0f;

        poseStack.pushPose();
        this.getParentModel().body.translateAndRotate(poseStack);
        CustomHeadLayer.translateToHead(poseStack, false);
        if (lit) {
            BakedModel model = Minecraft.getInstance().getModelManager().getModel(LINKED_MODEL);
            Minecraft.getInstance()
                    .getItemRenderer()
                    .render(
                            chest,
                            ItemDisplayContext.HEAD,
                            false,
                            poseStack,
                            buffer,
                            packedLight,
                            OverlayTexture.NO_OVERLAY,
                            model);
        } else {
            this.itemInHandRenderer.renderItem(
                    entity, chest, ItemDisplayContext.HEAD, false, poseStack, buffer, packedLight);
        }
        poseStack.popPose();
    }
}
