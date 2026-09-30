package com.cbc_more_content.client.ponder;

import com.cbc_more_content.entity.BlastDebrisEntity;
import java.util.ArrayList;
import java.util.List;
import net.createmod.ponder.api.element.PonderSceneElement;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.PonderElementBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Actual fractured meshes, with a bounded illustrative flight inside the replayable scene. */
public final class PonderBlastDebris extends PonderElementBase implements PonderSceneElement {
    private final Vec3 center;
    private final BlockState material;
    private final List<BlastDebrisEntity> pieces = new ArrayList<>();
    private int age;

    public PonderBlastDebris(Vec3 center, BlockState material) {
        this.center = center;
        this.material = material;
    }

    @Override
    public void tick(PonderScene scene) {
        var world = scene.getWorld();
        if (pieces.isEmpty()) {
            for (int i = 0; i < 5; i++) {
                double angle = i * Math.PI * 2 / 5 + .3;
                var piece = BlastDebrisEntity.create(
                        world,
                        material,
                        center,
                        new Vec3(Math.cos(angle) * .14, .24 + i * .018, Math.sin(angle) * .14));
                piece.setId(-1000 - i);
                pieces.add(piece);
            }
        }
        if (++age > 80) {
            setVisible(false);
            return;
        }
        for (var piece : pieces) {
            piece.tick();
            piece.tickCount = BlastDebrisEntity.MAX_LIFETIME - 80 + age;
            Vec3 velocity = piece.getDeltaMovement().scale(.98).add(0, -.028, 0);
            Vec3 next = piece.position().add(velocity);
            // The cutaway base is one block high. Keep the demonstration on the plate.
            if (next.y < 1.05) {
                next = new Vec3(next.x, 1.05, next.z);
                velocity = new Vec3(velocity.x * .55, Math.abs(velocity.y) * .25, velocity.z * .55);
            }
            piece.setOldPosAndRot();
            piece.setPos(next);
            piece.setDeltaMovement(velocity);
        }
    }

    @Override
    public void renderFirst(PonderLevel world, MultiBufferSource buffers, GuiGraphics graphics, float partial) {}

    @Override
    public void renderLayer(
            PonderLevel world, MultiBufferSource buffers, RenderType type, GuiGraphics graphics, float partial) {}

    @Override
    public void renderLast(PonderLevel world, MultiBufferSource buffers, GuiGraphics graphics, float partial) {
        if (!isVisible()) {
            return;
        }
        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        for (var piece : pieces) {
            Vec3 pos = piece.position();
            dispatcher.render(
                    piece, pos.x, pos.y, pos.z, 0, partial, graphics.pose(), buffers, LightTexture.FULL_BRIGHT);
        }
    }
}
