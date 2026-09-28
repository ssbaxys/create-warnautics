package com.cbc_more_content.client.ponder;

import com.cbc_more_content.client.ChainModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBlocks;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.ponder.api.element.PonderSceneElement;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.element.PonderElementBase;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Uses the real chain strand model in a Ponder demonstration without spawning a live rope constraint. */
public final class PonderChainElement extends PonderElementBase implements PonderSceneElement {
    private final Vec3 start;
    private final Vec3 end;

    public PonderChainElement(Vec3 start, Vec3 end) {
        this.start = start;
        this.end = end;
    }

    @Override
    public void renderFirst(PonderLevel world, MultiBufferSource buffer, GuiGraphics graphics, float pt) {}

    @Override
    public void renderLayer(
            PonderLevel world, MultiBufferSource buffer, RenderType type, GuiGraphics graphics, float pt) {
        if (type != RenderType.cutoutMipped()) {
            return;
        }
        int links = 8;
        for (int link = 0; link < links; link++) {
            Vec3 from = point((double) link / links);
            Vec3 to = point((double) (link + 1) / links);
            Vec3 delta = to.subtract(from);
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(from.x, from.y, from.z);
            pose.mulPose(new Quaternionf()
                    .rotationTo(
                            new Vector3f(0, 1, 0),
                            new Vector3f((float) delta.x, (float) delta.y, (float) delta.z).normalize()));
            pose.translate(-.5, 0, -.5);
            pose.scale(1, (float) delta.length(), 1);
            CachedBuffers.partialFacing(ChainModels.STRAND, AllBlocks.ROPE.getDefaultState(), Direction.NORTH)
                    .light(LightTexture.FULL_BRIGHT)
                    .renderInto(pose, buffer.getBuffer(type));
            pose.popPose();
        }
    }

    private Vec3 point(double part) {
        // A little slack makes the hanging link visibly distinct from a straight guide line.
        return start.lerp(end, part).add(0, -.35 * Math.sin(Math.PI * part), 0);
    }

    @Override
    public void renderLast(PonderLevel world, MultiBufferSource buffer, GuiGraphics graphics, float pt) {}
}
