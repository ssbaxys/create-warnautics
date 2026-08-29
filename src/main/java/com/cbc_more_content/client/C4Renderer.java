package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.block.C4Block;
import com.cbc_more_content.block.C4Block.Fuse;
import com.cbc_more_content.block.C4BlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Spins the timer cog inside an armed charge.
 * <p>
 * The cog lives in its own model rather than in the block model, because a block model
 * cannot animate. That means this has to reapply the facing rotation the blockstate would
 * have baked in, then turn the cog about its own axis inside that.
 */
@OnlyIn(Dist.CLIENT)
public class C4Renderer implements BlockEntityRenderer<C4BlockEntity> {
    /** Cog centre in model space, from the authored geometry. */
    private static final float PIVOT_X = 8.0f / 16.0f;

    private static final float PIVOT_Y = 4.1f / 16.0f;
    private static final float PIVOT_Z = 6.0f / 16.0f;
    /** Degrees per tick. Whole revolutions per 90 ticks, so the angle never jumps. */
    private static final float DEGREES_PER_TICK = 4.0f;

    private static final long REVOLUTION_TICKS = 90L;

    public static final ModelResourceLocation COG = standalone("block/c4/cog");
    public static final ModelResourceLocation COG_ARMED = standalone("block/c4/cog_armed");
    public static final ModelResourceLocation COG_LIT = standalone("block/c4/cog_lit");

    public C4Renderer(BlockEntityRendererProvider.Context context) {}

    private static ModelResourceLocation standalone(String path) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, path));
    }

    @Override
    public void render(
            C4BlockEntity be,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay) {
        if (be.getLevel() == null) {
            return;
        }
        BlockState state = be.getBlockState();
        Fuse fuse = state.getValue(C4Block.STATE);

        // The cog is drawn here in every state, including IDLE — it was cut out of the
        // block model, so skipping it left an unarmed charge with a hole in its casing.
        // Only a live fuse turns it.
        BakedModel model = Minecraft.getInstance()
                .getModelManager()
                .getModel(
                        switch (fuse) {
                            case IDLE -> COG;
                            case ARMED -> COG_ARMED;
                            case LIT -> COG_LIT;
                        });

        // A running fuse turns the cog. A remote charge has none, so it only turns once a
        // set has taken it on — which is the same thing that lights its screen, so the
        // lit state is the whole tell and nothing extra has to be synced for it.
        boolean turning = fuse != Fuse.IDLE && (!be.isRemote() || fuse == Fuse.LIT);

        pose.pushPose();
        applyFacing(pose, state.getValue(C4Block.FACING), state.getValue(C4Block.ROTATION));
        if (turning) {
            long ticks = be.getLevel().getGameTime() % REVOLUTION_TICKS;
            float spin = (ticks + partialTick) * DEGREES_PER_TICK;
            pose.translate(PIVOT_X, PIVOT_Y, PIVOT_Z);
            pose.mulPose(Axis.YP.rotationDegrees(spin));
            pose.translate(-PIVOT_X, -PIVOT_Y, -PIVOT_Z);
        }

        Minecraft.getInstance()
                .getBlockRenderer()
                .getModelRenderer()
                .renderModel(
                        pose.last(),
                        buffers.getBuffer(RenderType.cutout()),
                        state,
                        model,
                        1.0f,
                        1.0f,
                        1.0f,
                        packedLight,
                        packedOverlay,
                        ModelData.EMPTY,
                        RenderType.cutout());
        pose.popPose();
    }

    /**
     * The same transform {@code blockstates/c4.json} applies. A blockstate {@code x}/{@code y}
     * pair rotates the model by the negated angles, with {@code y} outermost.
     */
    private static void applyFacing(PoseStack pose, Direction facing, int rotation) {
        int x;
        int y;
        switch (facing) {
            case UP -> {
                x = 0;
                y = 0;
            }
            case DOWN -> {
                x = 180;
                y = 0;
            }
            case NORTH -> {
                x = 90;
                y = 0;
            }
            case SOUTH -> {
                x = 90;
                y = 180;
            }
            case WEST -> {
                x = 90;
                y = 270;
            }
            case EAST -> {
                x = 90;
                y = 90;
            }
            default -> {
                return;
            }
        }
        // The quarter turns ride on the same y the blockstate uses, so the cog lands in
        // the casing however the charge is turned.
        y = (y + C4Block.usableRotation(facing, rotation) * 90) % 360;
        if (x == 0 && y == 0) {
            return;
        }
        pose.translate(0.5f, 0.5f, 0.5f);
        if (y != 0) {
            pose.mulPose(Axis.YP.rotationDegrees(-y));
        }
        if (x != 0) {
            pose.mulPose(Axis.XP.rotationDegrees(-x));
        }
        pose.translate(-0.5f, -0.5f, -0.5f);
    }
}
