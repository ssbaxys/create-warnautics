package com.cbc_more_content.client.hud;

import com.cbc_more_content.CBCMoreContent;
import com.cbc_more_content.client.gui.kit.Ui;
import com.cbc_more_content.network.NoticePayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * The notice strip: Warnautics' replacement for action-bar text.
 * <p>
 * A small dark plate above the hotbar with a lamp in the notice's tone. A new notice
 * rises into place and older ones step up above it, at most three at a time; the same
 * notice arriving again does not stack a copy, it refreshes the one already shown and
 * counts the repeats. Long notices stay up longer. With the narrator on, each one is
 * read aloud, as the action bar would have been.
 */
@OnlyIn(Dist.CLIENT)
public final class NoticeHud {
    public static final ResourceLocation LAYER =
            ResourceLocation.fromNamespaceAndPath(CBCMoreContent.MOD_ID, "notices");

    private static final int MAX_SHOWN = 3;
    private static final float RISE_TICKS = 4;
    private static final float FADE_TICKS = 8;
    private static final int PLATE_H = 15;
    private static final int SPACING = 3;

    private static final List<Notice> NOTICES = new ArrayList<>();

    private NoticeHud() {}

    private static final class Notice {
        final NoticePayload payload;
        final int life;
        float age;
        int repeats = 1;
        /** Eased slot position, so the stack slides rather than jumps when one leaves. */
        float slot = -1;

        Notice(NoticePayload payload) {
            this.payload = payload;
            String text = payload.message().getString();
            this.life = Mth.clamp(55 + text.length(), 60, 120);
        }

        float opacity(float partial) {
            float t = this.age + partial;
            float in = Mth.clamp(t / RISE_TICKS, 0, 1);
            float out = Mth.clamp((this.life - t) / FADE_TICKS, 0, 1);
            return Math.min(in, out);
        }
    }

    /** Entry point for the network handler and for client-side callers. */
    public static void push(NoticePayload payload) {
        for (Notice existing : NOTICES) {
            if (existing.payload.tone() == payload.tone()
                    && existing.payload.message().equals(payload.message())) {
                // Both sides of an interaction can report the same thing in the same tick;
                // only a later repeat counts as a repeat.
                if (existing.age > 3) {
                    existing.repeats++;
                }
                existing.age = Math.min(existing.age, RISE_TICKS);
                return;
            }
        }
        NOTICES.add(new Notice(payload));
        while (NOTICES.size() > MAX_SHOWN) {
            NOTICES.removeFirst();
        }
        var narrator = Minecraft.getInstance().getNarrator();
        if (narrator.isActive()) {
            narrator.sayNow(payload.message());
        }
    }

    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.OVERLAY_MESSAGE, LAYER, NoticeHud::render);
    }

    @EventBusSubscriber(modid = CBCMoreContent.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            if (Minecraft.getInstance().isPaused()) {
                return;
            }
            NOTICES.removeIf(notice -> ++notice.age >= notice.life);
        }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            NOTICES.clear();
        }
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        var mc = Minecraft.getInstance();
        if (NOTICES.isEmpty() || mc.options.hideGui || mc.player == null) {
            return;
        }
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int centre = g.guiWidth() / 2;
        // Where vanilla puts action-bar text, so it lands where players already look.
        int base = g.guiHeight() - 72 - (mc.player.isCreative() ? 0 : 6);
        for (int i = NOTICES.size() - 1, slot = 0; i >= 0; i--, slot++) {
            Notice notice = NOTICES.get(i);
            notice.slot = notice.slot < 0 ? slot : Mth.lerp(0.35f, notice.slot, slot);
            float alpha = notice.opacity(partial);
            if (alpha < 0.03f) {
                continue;
            }
            float rise = (1 - Ui.easeOut((notice.age + partial) / RISE_TICKS)) * 6;
            int y = Math.round(base - notice.slot * (PLATE_H + SPACING) + rise);
            plate(g, mc, notice, centre, y, alpha, notice.age + partial);
        }
    }

    private static void plate(GuiGraphics g, Minecraft mc, Notice notice, int centre, int y, float alpha, float age) {
        Component text = notice.payload.message();
        if (notice.repeats > 1) {
            text = Component.empty()
                    .append(text)
                    .append(Component.literal("  ×" + notice.repeats)
                            .withStyle(notice.payload.tone().style()));
        }
        // The lamp flares as the notice arrives, then settles.
        float flare = Mth.clamp(1 - age / 10, 0, 1);
        Ui.hudPlate(g, mc.font, centre, y, text, notice.payload.tone().argb(), alpha, -1, flare, g.guiWidth() - 60);
    }
}
