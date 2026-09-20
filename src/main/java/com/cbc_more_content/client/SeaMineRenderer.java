package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.entity.SeaMineEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws the moored mine and its chain.
 * <p>
 * Four oxidation models, one per stage, registered as standalone extras so they bake
 * outside any blockstate. The chain is real geometry — thin crossed strips, the same
 * trick the tripwire uses — running from the mine's keel down to whatever the mooring
 * stands on. It is never plumb: a slow bow circles off vertical with the water, and a
 * smaller ripple rides down the links out of phase with the body's rock.
 */
@OnlyIn(Dist.CLIENT)
public class SeaMineRenderer extends EntityRenderer<SeaMineEntity> {
    public static final ModelResourceLocation MODEL_COPPER = model("copper");
    public static final ModelResourceLocation MODEL_EXPOSED = model("exposed");
    public static final ModelResourceLocation MODEL_WEATHERED = model("weathered");
    public static final ModelResourceLocation MODEL_OXIDIZED = model("oxidized");

    /** Half the chain's thickness, in blocks. */
    private static final float CHAIN_RADIUS = 0.032f;
    /** A slow idle sway, degrees, on the mine body. Moored things are never still. */
    private static final float SWAY_DEGREES = 2.5f;
    /** How far the mooring bows off plumb at mid-depth, in blocks — a chain in a current is never straight. */
    private static final double CHAIN_DRIFT = 0.075D;
    /** The small travelling ripple riding down the links, in blocks. */
    private static final double CHAIN_RIPPLE = 0.02D;

    public SeaMineRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.4f;
    }

    private static ModelResourceLocation model(String stage) {
        return ModelResourceLocation.standalone(
                ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "block/sea_mine/" + stage));
    }

    @Override
    public ResourceLocation getTextureLocation(SeaMineEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    @Override
    public void render(
            SeaMineEntity mine,
            float yaw,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight) {
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            renderChain(mine, level, partialTick, pose, buffers, packedLight);
        }

        BakedModel model = Minecraft.getInstance()
                .getModelManager()
                .getModel(
                        switch (mine.getOxidation()) {
                            case 0 -> MODEL_COPPER;
                            case 1 -> MODEL_EXPOSED;
                            case 2 -> MODEL_WEATHERED;
                            default -> MODEL_OXIDIZED;
                        });

        pose.pushPose();
        // Sway off the mooring: pendulum rock about the centre of the body, slow, out
        // of phase between neighbours because the phase follows the entity id.
        float time = (mine.tickCount + partialTick) * 0.02f;
        float phase = (mine.getId() % 16) * Mth.TWO_PI / 16.0f;
        float rock = Mth.sin(time + phase) * SWAY_DEGREES;
        float roll = Mth.cos(time * 0.83f + phase) * SWAY_DEGREES * 0.7f;

        // Blockbench exported the model corner-anchored: 0..16 units out from the pose
        // origin, so its centre sat half a block off in both horizontal axes and the
        // body hung beside its own hitbox and chain. Pull the centre onto the entity
        // position — the middle of the cell it was planted from — and swing the sway
        // about that centre, so hull, hitbox and mooring all agree.
        pose.translate(-0.5D, -0.5D, -0.5D);
        pose.mulPose(Axis.ZP.rotationDegrees(rock));
        pose.mulPose(Axis.XP.rotationDegrees(roll));

        Minecraft.getInstance()
                .getBlockRenderer()
                .getModelRenderer()
                .renderModel(
                        pose.last(),
                        buffers.getBuffer(RenderType.cutout()),
                        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                        model,
                        1.0f,
                        1.0f,
                        1.0f,
                        packedLight,
                        OverlayTexture.NO_OVERLAY,
                        net.neoforged.neoforge.client.model.data.ModelData.EMPTY,
                        RenderType.cutout());
        pose.popPose();

        super.render(mine, yaw, partialTick, pose, buffers, packedLight);
    }

    /**
     * The mooring: down from the keel to the anchor block's top surface, drawn as
     * crossed strips so it reads from any angle, with tiny segment offsets that
     * alternate so the column reads as links rather than a rod.
     */
    private static void renderChain(
            SeaMineEntity mine,
            Level level,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight) {
        if (!mine.isAnchored()) {
            return;
        }
        BlockPos anchor = mine.getAnchor();
        if (anchor == null) {
            return;
        }
        // Interpolate the hull's height off the previous tick, exactly as the engine
        // positions the body above, so the chain rides it smoothly instead of
        // stair-stepping in twentieth-second jumps or pulling loose mid-frame.
        double y = Mth.lerp(partialTick, mine.yOld, mine.getY());
        double topY = -0.15D; // entity-relative, tucked into the keel so the joint never shows
        double bottomY = anchor.getY() + 1.0D - y;
        if (bottomY >= topY) {
            return;
        }

        // Chain points must be entity-relative to match the pose the quads are drawn
        // through — world-space coordinates here hang the chain a world away, invisible.
        Vec3 top = new Vec3(0.0D, topY, 0.0D);
        Vec3 bottom = new Vec3(0.0D, bottomY, 0.0D);

        // The chain block's own texture, off the block atlas: link-shaped, with the
        // transparent parts punched out so it reads as links rather than a painted bar.
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getModelManager()
                .getAtlas(TextureAtlas.LOCATION_BLOCKS)
                .getSprite(net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/chain"));

        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        PoseStack.Pose last = pose.last();

        // Same clock and per-entity phase as the body's rock, so both drift in the
        // same water — but the chain runs on its own periods, never locked to the hull.
        float time = (mine.tickCount + partialTick) * 0.02f;
        float phase = (mine.getId() % 16) * Mth.TWO_PI / 16.0f;

        int segments = Math.max(2, Mth.ceil((topY - bottomY) / 0.5D));
        Vec3 previous = chainPoint(top, bottom, 0.0f, time, phase);
        for (int i = 1; i <= segments; i++) {
            Vec3 next = chainPoint(top, bottom, i / (float) segments, time, phase);
            strand(consumer, last, sprite, previous, next, i, packedLight);
            previous = next;
        }
    }

    /**
     * One link-length of chain, as a cross, offset alternately so links read as links.
     * The strip wears the chain block's texture edge to edge, once per segment — a
     * segment is about half a block, which is one full texture, which is about three
     * links. Odd links sit in the other plane, as real chain does.
     */
    private static void strand(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            TextureAtlasSprite sprite,
            Vec3 from,
            Vec3 to,
            int index,
            int light) {
        float radius = index % 2 == 0 ? CHAIN_RADIUS : CHAIN_RADIUS * 0.82f;
        // Alternate links sit in the other plane, as real chain does: the main strip
        // faces one way, the crossed strip the other.
        Vec3 mainSide = (index & 1) == 0 ? new Vec3(radius, 0.0D, 0.0D) : new Vec3(0.0D, 0.0D, radius);
        Vec3 crossSide = (index & 1) == 0 ? new Vec3(0.0D, 0.0D, radius) : new Vec3(radius, 0.0D, 0.0D);
        float mainNx = (index & 1) == 0 ? 1.0f : 0.0f;
        float mainNz = (index & 1) == 0 ? 0.0f : 1.0f;

        // Cutout with no backface cull: one quad per strip shows from both sides, and
        // the sprite's transparent cut-outs put the actual link shape in it.
        face(
                consumer,
                pose,
                sprite,
                from.subtract(mainSide),
                from.add(mainSide),
                to.add(mainSide),
                to.subtract(mainSide),
                mainNx,
                0.0f,
                mainNz,
                light);
        face(
                consumer,
                pose,
                sprite,
                from.subtract(crossSide),
                from.add(crossSide),
                to.add(crossSide),
                to.subtract(crossSide),
                mainNz,
                0.0f,
                mainNx,
                light);
    }

    private static void face(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            TextureAtlasSprite sprite,
            Vec3 a,
            Vec3 b,
            Vec3 c,
            Vec3 d,
            float nx,
            float ny,
            float nz,
            int light) {
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        vertex(consumer, pose, a, u0, v0, nx, ny, nz, light);
        vertex(consumer, pose, b, u0, v1, nx, ny, nz, light);
        vertex(consumer, pose, c, u1, v1, nx, ny, nz, light);
        vertex(consumer, pose, d, u1, v0, nx, ny, nz, light);
    }

    private static void vertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            Vec3 at,
            float u,
            float v,
            float nx,
            float ny,
            float nz,
            int light) {
        consumer.addVertex(pose, (float) at.x, (float) at.y, (float) at.z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }

    /**
     * A point partway down the mooring, and where it drifts to. The bow circles slowly
     * off plumb — the current, not gravity alone, holds a mooring — and a smaller
     * ripple travels down the links on a heading turned square to the bow. Both are
     * faded to nothing at either end, so the keel and the anchor keep their hold.
     */
    private static Vec3 chainPoint(Vec3 top, Vec3 bottom, float t, float time, float phase) {
        float envelope = Mth.sin((float) Math.PI * t);
        double angle = phase + time * 0.55f;
        double dirX = Mth.cos((float) angle);
        double dirZ = Mth.sin((float) angle);
        double ripple = CHAIN_RIPPLE * Mth.sin(t * Mth.TWO_PI * 2.0f + time * 2.2f + phase * 2.0f);
        double offX = (CHAIN_DRIFT * dirX - ripple * dirZ) * envelope;
        double offZ = (CHAIN_DRIFT * dirZ + ripple * dirX) * envelope;
        return new Vec3(
                Mth.lerp(t, top.x, bottom.x) + offX, Mth.lerp(t, top.y, bottom.y), Mth.lerp(t, top.z, bottom.z) + offZ);
    }
}
