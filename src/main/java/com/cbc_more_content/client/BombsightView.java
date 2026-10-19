package com.cbc_more_content.client;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.bombsight.BombsightBlock;
import com.cbc_more_content.bombsight.BombsightBlockEntity;
import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.client.hud.NoticeHud;
import com.cbc_more_content.compat.sable.TripwireGeometry;
import com.cbc_more_content.network.BombsightCommandPayload;
import com.cbc_more_content.network.BombsightSolutionPayload;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Looking through the bombsight.
 * <p>
 * Put your eye to it (use it with an empty hand) and the view is the sight's: from under the
 * carrier's hull, looking down and ahead, through a round optic with an etched reticle. The
 * ground drifts through it as the carrier flies; a pip shows where a bomb let go now would
 * land, a cross the target, and the rate index slides down the track wire to the centre as
 * the release comes up. Instruments either side of the optic give the height, speed, fall
 * time, the run and the steer.
 * <p>
 * The mouse looks around, the wheel zooms, left click marks the spot under the reticle as
 * the target, right click drops by hand, R switches automatic release, X clears the target,
 * and sneak (or Esc) takes your eye from the sight.
 */
@OnlyIn(Dist.CLIENT)
public final class BombsightView {
    public static final ResourceLocation LAYER =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "bombsight_view");

    private static final float[] ZOOMS = {1.0f, 2.0f, 4.0f, 8.0f};
    /** How far from the sight the player may be and still be at its eyepiece. */
    private static final double REACH = 12.0D;
    /** Steepest the sight looks ahead of the vertical, as a pitch: 90 is straight down. */
    private static final float MIN_PITCH = 15.0f;

    @Nullable
    private static BlockPos sight;

    @Nullable
    private static Marker eye;

    private static float savedYRot;
    private static float savedXRot;
    private static CameraType savedCamera = CameraType.FIRST_PERSON;
    private static int zoom = 1;
    private static int age;
    private static int markedFor = -1;

    /** The vertical field of view the last frame was drawn with, in degrees. */
    private static double fov = 70.0D;

    private BombsightView() {}

    public static boolean active() {
        return sight != null;
    }

    /** Puts the player's eye to the sight at this block. */
    public static void enter(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || active()) {
            return;
        }
        Vec3 at = lens(mc.level, pos);
        if (mc.player.distanceToSqr(at) > REACH * REACH) {
            return;
        }
        sight = pos.immutable();
        eye = new Marker(EntityType.MARKER, mc.level);
        savedYRot = mc.player.getYRot();
        savedXRot = mc.player.getXRot();
        savedCamera = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        // Lined up along the carrier's track, looking down and a little ahead.
        BombsightSolutionPayload s = BombsightClient.latest(pos);
        if (s != null && Math.hypot(s.velocity().x, s.velocity().z) > 0.05D) {
            float heading = (float) (Mth.atan2(-s.velocity().x, s.velocity().z) * Mth.RAD_TO_DEG);
            mc.player.setYRot(heading);
            mc.player.yRotO = heading;
        }
        mc.player.setXRot(70.0f);
        mc.player.xRotO = 70.0f;
        place(mc, 1.0f);
        mc.setCameraEntity(eye);
        zoom = 0;
        age = 0;
        markedFor = -1;
        mc.player.playSound(SoundEvents.SPYGLASS_USE, 1.0f, 0.8f);
    }

    /** Takes the player's eye from the sight, back to where they were looking before. */
    public static void exit() {
        if (!active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        sight = null;
        eye = null;
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
            mc.player.setYRot(savedYRot);
            mc.player.yRotO = savedYRot;
            mc.player.setXRot(savedXRot);
            mc.player.xRotO = savedXRot;
            mc.player.playSound(SoundEvents.SPYGLASS_STOP_USING, 1.0f, 0.8f);
        }
        mc.options.setCameraType(savedCamera);
    }

    /**
     * Where the optic looks out from. On a ship, from the first open cell under the hull
     * below the sight, so a sight set on the deck still sees the ground and not the planks;
     * otherwise, the sight's own cell. Uses the ship's interpolated pose, so the view glides
     * with the ship rather than stepping with each server tick.
     */
    private static Vec3 lens(Level level, BlockPos pos) {
        var hull = TripwireGeometry.hull(level, pos);
        BlockPos cell = pos;
        if (hull != null) {
            BlockPos cursor = pos.below();
            boolean solid = false;
            for (int i = 0; i < 10; i++) {
                BlockState state = level.getBlockState(cursor);
                boolean open = state.isAir() || state.getBlock() instanceof BombsightBlock;
                if (!open) {
                    solid = true;
                } else if (solid) {
                    cell = cursor;
                    break;
                }
                cursor = cursor.below();
            }
            if (hull instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel client && !hull.isRemoved()) {
                return client.renderPose().transformPosition(cell.getCenter());
            }
        }
        return cell.getCenter();
    }

    private static void place(Minecraft mc, float partial) {
        if (eye == null || sight == null || mc.player == null || mc.level == null) {
            return;
        }
        Vec3 at = lens(mc.level, sight);
        eye.setPos(at);
        eye.xo = at.x;
        eye.yo = at.y;
        eye.zo = at.z;
        eye.xOld = at.x;
        eye.yOld = at.y;
        eye.zOld = at.z;
        float pitch = Mth.clamp(mc.player.getXRot(), MIN_PITCH, 90.0f);
        if (pitch != mc.player.getXRot()) {
            mc.player.setXRot(pitch);
        }
        mc.player.xRotO = Mth.clamp(mc.player.xRotO, MIN_PITCH, 90.0f);
        eye.setYRot(mc.player.getYRot());
        eye.yRotO = mc.player.yRotO;
        eye.setXRot(mc.player.getXRot());
        eye.xRotO = mc.player.xRotO;
    }

    private static void command(byte action, int x, int z) {
        if (sight != null) {
            PacketDistributor.sendToServer(new BombsightCommandPayload(sight, action, x, z));
        }
    }

    /** Marks the spot on the ground under the centre of the reticle. */
    private static void markUnderReticle(Minecraft mc) {
        if (mc.level == null || eye == null) {
            return;
        }
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 from = camera.getPosition();
        var look = camera.getLookVector();
        Vec3 to = from.add(look.x() * 1024.0D, look.y() * 1024.0D, look.z() * 1024.0D);
        var hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, eye));
        if (hit.getType() == HitResult.Type.MISS) {
            mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
            return;
        }
        command(BombsightCommandPayload.MARK, Mth.floor(hit.getLocation().x), Mth.floor(hit.getLocation().z));
        markedFor = 0;
    }

    @EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            if (!active()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            age++;
            if (markedFor >= 0) {
                markedFor++;
            }
            if (mc.player == null
                    || mc.level == null
                    || eye == null
                    || eye.level() != mc.level
                    || !mc.player.isAlive()
                    || !(mc.level.getBlockState(sight).getBlock() instanceof BombsightBlock)
                    || mc.player.distanceToSqr(lens(mc.level, sight)) > (REACH + 4) * (REACH + 4)
                    || (age > 5 && mc.options.keyShift.isDown())) {
                exit();
            }
        }

        @SubscribeEvent
        public static void frame(RenderFrameEvent.Pre event) {
            if (active()) {
                place(Minecraft.getInstance(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
            }
        }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            sight = null;
            eye = null;
        }

        /** Esc, the inventory and the like take the eye from the sight; chat does not. */
        @SubscribeEvent
        public static void screen(ScreenEvent.Opening event) {
            if (active() && !(event.getNewScreen() instanceof ChatScreen)) {
                exit();
            }
        }

        /** Standing at the sight: no walking off, no jumping, no crouching while aiming. */
        @SubscribeEvent
        public static void movement(MovementInputUpdateEvent event) {
            if (!active()) {
                return;
            }
            var input = event.getInput();
            input.forwardImpulse = 0;
            input.leftImpulse = 0;
            input.up = false;
            input.down = false;
            input.left = false;
            input.right = false;
            input.jumping = false;
            input.shiftKeyDown = false;
        }

        @SubscribeEvent
        public static void interact(InputEvent.InteractionKeyMappingTriggered event) {
            if (!active()) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            Minecraft mc = Minecraft.getInstance();
            if (event.isAttack()) {
                markUnderReticle(mc);
            } else if (event.isUseItem()) {
                command(BombsightCommandPayload.RELEASE, 0, 0);
            }
        }

        @SubscribeEvent
        public static void scroll(InputEvent.MouseScrollingEvent event) {
            if (!active() || Minecraft.getInstance().screen != null) {
                return;
            }
            int next = Mth.clamp(zoom + (event.getScrollDeltaY() > 0 ? 1 : -1), 0, ZOOMS.length - 1);
            if (next != zoom) {
                zoom = next;
                Minecraft.getInstance().player.playSound(SoundEvents.SPYGLASS_USE, 0.4f, 1.2f + zoom * 0.15f);
            }
            event.setCanceled(true);
        }

        @SubscribeEvent
        public static void key(InputEvent.Key event) {
            if (!active() || event.getAction() != GLFW.GLFW_PRESS || Minecraft.getInstance().screen != null) {
                return;
            }
            if (event.getKey() == GLFW.GLFW_KEY_R) {
                command(BombsightCommandPayload.TOGGLE_AUTO, 0, 0);
            } else if (event.getKey() == GLFW.GLFW_KEY_X) {
                command(BombsightCommandPayload.CLEAR, 0, 0);
            }
        }

        /** Through a magnifying optic the hand moves the view by less. */
        @SubscribeEvent
        public static void turn(CalculatePlayerTurnEvent event) {
            if (!active()) {
                return;
            }
            double d = event.getMouseSensitivity() * 0.6D + 0.2D;
            double scaled = d / Math.cbrt(ZOOMS[zoom]);
            event.setMouseSensitivity(Math.max(0.0D, (scaled - 0.2D) / 0.6D));
        }

        @SubscribeEvent
        public static void fov(ViewportEvent.ComputeFov event) {
            if (active()) {
                event.setFOV(event.getFOV() * 0.85D / ZOOMS[zoom]);
                fov = event.getFOV();
            }
        }

        @SubscribeEvent
        public static void hand(RenderHandEvent event) {
            if (active()) {
                event.setCanceled(true);
            }
        }

        /** Nothing of the ordinary HUD over the optic but chat and notices. */
        @SubscribeEvent
        public static void layer(RenderGuiLayerEvent.Pre event) {
            if (active()
                    && !event.getName().equals(LAYER)
                    && !event.getName().equals(NoticeHud.LAYER)
                    && !event.getName().equals(VanillaGuiLayers.CHAT)) {
                event.setCanceled(true);
            }
        }
    }

    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER, BombsightView::render);
    }

    // ---------------------------------------------------------------- the optic

    /**
     * A world point on screen, in GUI pixels, or null when behind the eye: a plain pinhole
     * through the camera's own axes and the field of view the frame was drawn with.
     */
    @Nullable
    private static float[] project(Vec3 p, int w, int h) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 d = p.subtract(camera.getPosition());
        var look = camera.getLookVector();
        var up = camera.getUpVector();
        var left = camera.getLeftVector();
        double depth = d.x * look.x() + d.y * look.y() + d.z * look.z();
        if (depth <= 0.05D) {
            return null;
        }
        double right = -(d.x * left.x() + d.y * left.y() + d.z * left.z());
        double rise = d.x * up.x() + d.y * up.y() + d.z * up.z();
        double focal = 1.0D / Math.tan(Math.toRadians(fov) / 2.0D);
        double aspect = w / (double) h;
        double ndcX = right * focal / (depth * aspect);
        double ndcY = rise * focal / depth;
        return new float[] {(float) ((ndcX * 0.5D + 0.5D) * w), (float) ((0.5D - ndcY * 0.5D) * h)};
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "textures/gui/bombsight/" + name + ".png");
    }

    private static final ResourceLocation EYEPIECE = texture("eyepiece");
    private static final ResourceLocation RETICLE = texture("reticle");
    private static final ResourceLocation PIP = texture("pip");
    private static final ResourceLocation TARGET = texture("target");
    private static final ResourceLocation INDEX = texture("index");
    private static final ResourceLocation LAMP_GLASS = texture("lamp_glass");
    private static final ResourceLocation LAMP_BEZEL = texture("lamp_bezel");
    private static final ResourceLocation GLOW = texture("glow");
    /** The glass's share of the eyepiece texture's half-width. */
    private static final float LENS = 0.94f;
    /** Ticks the controls stay written under the optic after the eye goes to it. */
    private static final int HINT_TICKS = 100;

    private static final int COUNTER_FACE = 0xFF0C0C0B;
    private static final int COUNTER_RIM = 0xFF33312B;
    private static final int COUNTER_DIGIT = 0xFFE4DECB;
    private static final int ENGRAVED = 0xFF7A7566;

    /** Draws a texture scaled into a box, smoothly filtered, tinted. */
    private static void sprite(GuiGraphics g, ResourceLocation tex, float x, float y, float size, int argb) {
        Minecraft.getInstance().getTextureManager().getTexture(tex).setFilter(true, false);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(
                (argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        float scale = size / 256f;
        g.pose().scale(scale, scale, 1);
        g.blit(tex, 0, 0, 256, 256, 0, 0, 256, 256, 256, 256);
        g.pose().popPose();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!active() || mc.player == null) {
            return;
        }
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float now = age + partial;
        Font font = mc.font;
        int w = g.guiWidth();
        int h = g.guiHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) * 0.43f;
        BombsightSolutionPayload s = BombsightClient.latest(sight);

        if (s != null) {
            marks(g, s, w, h, cx, cy, r, partial, now);
        }
        // The etched glass, then the eyepiece over it: vignette, rim and the dark round it.
        float span = 2 * r / LENS;
        sprite(g, RETICLE, cx - span / 2, cy - span / 2, span, 0xFFFFFFFF);
        sprite(g, EYEPIECE, cx - span / 2, cy - span / 2, span, 0xFFFFFFFF);
        // The black round the eyepiece overlaps its edge, so no seam of the world shows.
        int left = (int) Math.ceil(cx - span / 2) + 1;
        int right = (int) Math.floor(cx + span / 2) - 1;
        int top = (int) Math.ceil(cy - span / 2) + 1;
        int bottom = (int) Math.floor(cy + span / 2) - 1;
        g.fill(0, 0, w, Math.max(0, top), 0xFF000000);
        g.fill(0, Math.min(h, bottom), w, h, 0xFF000000);
        g.fill(0, top, Math.max(0, left), bottom, 0xFF000000);
        g.fill(Math.min(w, right), top, w, bottom, 0xFF000000);

        if (s != null) {
            offGlass(g, s, w, h, cx, cy, r, partial);
        }
        float side = cx - r;
        if (side >= 64) {
            instruments(g, font, s, (int) (side / 2), (int) cy);
            release(g, font, s, (int) (w - side / 2), (int) cy, now);
        } else {
            instruments(g, font, s, 34, (int) (cy + r * 0.55f));
            release(g, font, s, w - 34, (int) (cy + r * 0.55f), now);
        }

        Component warning = warning(s);
        if (warning != null) {
            engraved(g, font, warning, (int) cx, (int) (cy + r * 0.62f), s == null ? 0xFFB84A3A : 0xFFC9A04A, 1.0f);
        }
        if (age < HINT_TICKS) {
            float fade = Mth.clamp((HINT_TICKS - now) / 20f, 0, 1) * Mth.clamp(now / 6f, 0, 1);
            engraved(
                    g,
                    font,
                    Component.translatable("hud.cbc_more_content.bombsight.view.hints"),
                    (int) cx,
                    h - 12,
                    Ui.withAlpha(0xFFB9B3A2, fade),
                    0.75f);
        }
    }

    /** Small text, centred, as if stamped into the instrument. */
    private static void engraved(GuiGraphics g, Font font, Component text, int cx, int y, int colour, float scale) {
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1);
        int tw = font.width(text);
        g.drawString(font, text, -tw / 2, 0, colour, false);
        g.pose().popPose();
    }

    @Nullable
    private static Component warning(@Nullable BombsightSolutionPayload s) {
        if (s == null) {
            return Component.translatable("hud.cbc_more_content.bombsight.view.no_signal");
        }
        if (!s.rack()) {
            return Component.translatable("hud.cbc_more_content.bombsight.no_rack");
        }
        if (!s.landed()) {
            return Component.translatable("hud.cbc_more_content.bombsight.unloaded");
        }
        return null;
    }

    /**
     * Seen through the glass: a ring where a bomb let go now would land, faint dots along the
     * track it is drifting down, corner brackets on the target, and the rate index on the
     * track wire, coming down to the centre as the release comes up.
     */
    private static void marks(
            GuiGraphics g,
            BombsightSolutionPayload s,
            int w,
            int h,
            float cx,
            float cy,
            float r,
            float partial,
            float now) {
        double ahead = Math.min(BombsightClient.readingAge(sight) + partial, 4);
        Vec3 impact = s.impact().add(s.velocity().x * ahead, 0.05D, s.velocity().z * ahead);
        double speed = Math.hypot(s.velocity().x, s.velocity().z);
        if (speed > 0.05D) {
            Vec3 dir = new Vec3(s.velocity().x / speed, 0, s.velocity().z / speed);
            for (int i = 1; i <= 16; i++) {
                float[] dot = project(impact.add(dir.scale(i * 5.0D)), w, h);
                if (dot != null && inside(dot, cx, cy, r)) {
                    sprite(g, PIP, dot[0] - 1.5f, dot[1] - 1.5f, 3, Ui.withAlpha(0xFFE0B050, 0.5f * (1 - i / 17f)));
                }
            }
        }
        float[] pip = project(impact, w, h);
        if (pip != null && inside(pip, cx, cy, r)) {
            int colour = s.releasing() ? 0xFFE0503A : s.landed() ? 0xFFF0C060 : 0xFF8A8676;
            sprite(g, PIP, pip[0] - 8, pip[1] - 8, 16, colour);
        }
        if (s.hasTarget()) {
            Vec3 target = new Vec3(s.target().x, impact.y, s.target().z);
            float[] t = project(target, w, h);
            boolean onTrack = Math.abs(s.cross()) <= BombsightBlockEntity.CROSS_TOLERANCE;
            int colour = onTrack ? 0xFF8FD08A : 0xFFE06A50;
            float pulse = markedFor >= 0 && markedFor < 12 ? 1 - (markedFor + partial) / 12f : 0;
            if (t != null && inside(t, cx, cy, r)) {
                float size = 14 + pulse * 10;
                sprite(g, TARGET, t[0] - size / 2, t[1] - size / 2, size, colour);
            }
            if (s.untilDrop() >= 0 && speed > 0.05D) {
                float fill = Mth.clamp(s.untilDrop() / 200f, 0, 1);
                float y = cy - fill * (r * 0.8f);
                int c = s.untilDrop() <= 40 ? 0xFFE0503A : 0xFFF0C060;
                sprite(g, INDEX, cx - 4, y - 9, 8, c);
            }
        }
    }

    /** A target out of the field: a mark on the rim of the glass, on the side to turn toward. */
    private static void offGlass(
            GuiGraphics g, BombsightSolutionPayload s, int w, int h, float cx, float cy, float r, float partial) {
        if (!s.hasTarget()) {
            return;
        }
        double ahead = Math.min(BombsightClient.readingAge(sight) + partial, 4);
        Vec3 target = new Vec3(s.target().x, s.impact().y + 0.05D, s.target().z);
        float[] t = project(target, w, h);
        if (t != null && inside(t, cx, cy, r)) {
            return;
        }
        double a;
        if (t != null) {
            a = Math.atan2(t[1] - cy, t[0] - cx);
        } else {
            // Behind the eye: point the opposite way to where it projects.
            Vec3 from = s.impact().add(s.velocity().x * ahead, 0, s.velocity().z * ahead);
            float[] back = project(from.add(from.subtract(target)), w, h);
            if (back == null) {
                return;
            }
            a = Math.atan2(back[1] - cy, back[0] - cx);
        }
        boolean onTrack = Math.abs(s.cross()) <= BombsightBlockEntity.CROSS_TOLERANCE;
        int colour = onTrack ? 0xFF8FD08A : 0xFFE06A50;
        float x = cx + (float) Math.cos(a) * (r + 7);
        float y = cy + (float) Math.sin(a) * (r + 7);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotation((float) a - (float) Math.PI / 2));
        sprite(g, INDEX, -4, -4, 8, colour);
        g.pose().popPose();
    }

    private static boolean inside(float[] p, float cx, float cy, float r) {
        float dx = p[0] - cx;
        float dy = p[1] - cy;
        return dx * dx + dy * dy < (r - 4) * (r - 4);
    }

    // ---------------------------------------------------------------- instruments

    private static Component key(String name, Object... args) {
        return Component.translatable("hud.cbc_more_content.bombsight.view." + name, args);
    }

    /**
     * A drum counter: a dark window with the digits on drums, shaded top and bottom where
     * the drums turn away, its name stamped above and its unit beside.
     */
    private static void counter(GuiGraphics g, Font font, int cx, int y, String digits, Component name, String unit) {
        int cell = 7;
        int w = digits.length() * cell + 2;
        int x = cx - w / 2;
        int h = 12;
        engraved(g, font, name, cx, y - 8, ENGRAVED, 0.6f);
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF050505);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, COUNTER_RIM);
        g.fill(x, y, x + w, y + h, COUNTER_FACE);
        for (int i = 0; i < digits.length(); i++) {
            int dx = x + 1 + i * cell;
            String ch = digits.substring(i, i + 1);
            if (i > 0) {
                g.fill(dx, y, dx + 1, y + h, 0xFF000000);
            }
            if (!ch.equals(" ")) {
                g.drawString(font, ch, dx + (cell - font.width(ch)) / 2 + 1, y + 2, COUNTER_DIGIT, false);
            }
        }
        g.fillGradient(x, y, x + w, y + 4, 0xC0000000, 0x00000000);
        g.fillGradient(x, y + h - 4, x + w, y + h, 0x00000000, 0xC0000000);
        if (!unit.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(x + w + 4, y + 4, 0);
            g.pose().scale(0.6f, 0.6f, 1);
            g.drawString(font, unit, 0, 0, ENGRAVED, false);
            g.pose().popPose();
        }
    }

    private static String drums(double value, int whole) {
        long v = Math.max(0, Math.min(Math.round(value), (long) Math.pow(10, whole) - 1));
        return String.format(Locale.ROOT, "%0" + whole + "d", v);
    }

    private static String drumsTenths(double value) {
        double v = Mth.clamp(value, 0, 99.9);
        return String.format(Locale.ROOT, "%04.1f", v).replace('.', ',');
    }

    /** Left of the glass: height, ground speed, fall time, magnification. */
    private static void instruments(GuiGraphics g, Font font, @Nullable BombsightSolutionPayload s, int cx, int cy) {
        double height = s == null ? 0 : s.sightWorld().y - s.impact().y;
        double speed = s == null ? 0 : Math.hypot(s.velocity().x, s.velocity().z) * 20;
        double fall = s == null ? 0 : s.fallTicks() / 20.0D;
        int y = cy - 46;
        counter(g, font, cx, y, drums(height, 4), key("alt"), unit("meters"));
        counter(g, font, cx, y + 26, drums(speed, 3), key("speed"), unit("mps"));
        counter(g, font, cx, y + 52, drumsTenths(fall), key("fall"), unit("seconds"));
        counter(g, font, cx, y + 78, "×" + (int) ZOOMS[zoom], key("zoom"), "");
    }

    /** The unit alone, from the "%s m" style keys. */
    private static String unit(String name) {
        return Component.translatable("gui.cbc_more_content.unit." + name, "")
                .getString()
                .trim();
    }

    /**
     * Right of the glass: the release lamp, what the release is set to, the run to the drop,
     * and the drift needle: centred when the target is on the track.
     */
    private static void release(
            GuiGraphics g, Font font, @Nullable BombsightSolutionPayload s, int cx, int cy, float now) {
        boolean releasing = s != null && s.releasing();
        boolean armed = s != null && s.autoRelease() && s.hasTarget() && s.mode() == 1;
        int y = cy - 46;
        float size = 22;
        int glass = releasing ? 0xFFFF4A30 : armed ? 0xFFB07A28 : 0xFF3A1A14;
        if (releasing) {
            float pulse = 0.6f + 0.4f * Mth.sin(now * 0.9f);
            sprite(g, GLOW, cx - 22, y - 12, 44, Ui.withAlpha(0xFFFF5030, 0.55f * pulse));
        }
        sprite(g, LAMP_GLASS, cx - size / 2, y - size / 2 + 2, size, glass);
        sprite(g, LAMP_BEZEL, cx - size / 2, y - size / 2 + 2, size, 0xFFFFFFFF);
        engraved(
                g,
                font,
                key(releasing ? "release" : armed ? "ready" : "manual"),
                cx,
                y + 14,
                releasing ? 0xFFE0503A : armed ? 0xFFC9A04A : ENGRAVED,
                0.6f);
        if (s == null || !s.hasTarget()) {
            engraved(g, font, key("no_target_short"), cx, y + 40, ENGRAVED, 0.6f);
            return;
        }
        double speed = Math.hypot(s.velocity().x, s.velocity().z);
        String distance = s.untilDrop() >= 0 || speed < 0.05D ? drums(Math.abs(s.along()), 4) : "----";
        counter(g, font, cx, y + 40, distance, key("to_release"), unit("meters"));
        String time = s.untilDrop() >= 0 && speed >= 0.05D ? drumsTenths(s.untilDrop() / 20.0D) : "--,-";
        counter(g, font, cx, y + 66, time, key("time"), unit("seconds"));
        drift(g, font, cx, y + 92, s.cross(), speed >= 0.05D);
    }

    /** A needle over a small arc: left of centre, steer left; on the centre mark, on track. */
    private static void drift(GuiGraphics g, Font font, int cx, int y, float cross, boolean moving) {
        engraved(g, font, key("drift"), cx, y - 8, ENGRAVED, 0.6f);
        int half = 24;
        g.fill(cx - half - 2, y - 2, cx + half + 2, y + 10, 0xFF050505);
        g.fill(cx - half - 1, y - 1, cx + half + 1, y + 9, COUNTER_RIM);
        g.fill(cx - half, y, cx + half, y + 8, COUNTER_FACE);
        int band = Math.round(half * (float) BombsightBlockEntity.CROSS_TOLERANCE / 24f);
        g.fill(cx - band, y + 6, cx + band, y + 8, 0xFF2F4A2C);
        for (int i = -4; i <= 4; i++) {
            int tx = cx + i * half / 4;
            g.fill(tx, y + (i == 0 ? 0 : 5), tx + 1, y + 8, ENGRAVED);
        }
        if (!moving) {
            return;
        }
        int nx = cx + Math.round(Mth.clamp(cross / 24f, -1, 1) * (half - 2));
        boolean onTrack = Math.abs(cross) <= BombsightBlockEntity.CROSS_TOLERANCE;
        g.fill(nx, y, nx + 1, y + 8, onTrack ? 0xFF8FD08A : 0xFFF0C060);
    }
}
