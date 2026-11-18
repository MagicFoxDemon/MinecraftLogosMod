package dev.logos.client;

import dev.logos.LogosMod;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import dev.logos.client.port.GuiGraphicsExtractor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * The note in the bottom-right corner when the sage finishes while the player is away from its
 * page: a slip of parchment that slides in, stays a few seconds and slides out. It is drawn over
 * the world, and over any screen that is open.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class SageToast {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(LogosMod.MODID, "sage_toast");
    private static final long SHOW_MS = 6000, SLIDE_MS = 250;
    private static final int PAPER = 0xF2E9DCC0, EDGE = 0xFF6B4E31, INK = 0xFF392719, FADED = 0xFF7A6248;

    private static Component title = Component.empty(), line = Component.empty();
    private static long shownAt = -SHOW_MS;

    private SageToast() {}

    static void show(boolean ok, String chatTitle) {
        title = Component.translatable(ok ? "logos.sage.toast" : "logos.sage.toast.failed");
        line = chatTitle == null || chatTitle.isEmpty() ? Component.translatable("logos.sage.toast.hint") : Component.literal(chatTitle);
        shownAt = System.currentTimeMillis();
    }

    /** The player is on the sage's page: the note has done its job. */
    static void dismiss() {
        shownAt = -SHOW_MS;
    }

    /** Whether a note is on screen; client scenarios read it. */
    public static boolean showing() {
        return System.currentTimeMillis() - shownAt < SHOW_MS;
    }


    @SubscribeEvent
    static void onScreen(ScreenEvent.Render.Post event) {
        draw(new dev.logos.client.port.GuiGraphicsExtractor(event.getGuiGraphics()));
    }

    private static void draw(GuiGraphicsExtractor g) {
        long t = System.currentTimeMillis() - shownAt;
        if (t < 0 || t >= SHOW_MS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui && mc.screen == null) return;
        Font font = mc.font;
        int w = Math.max(font.width(title), font.width(line)) + 14, h = 27;
        float in = Math.min(1, t / (float) SLIDE_MS), out = Math.min(1, (SHOW_MS - t) / (float) SLIDE_MS);
        float shown = Math.min(in, out);
        shown = 1 - (1 - shown) * (1 - shown);
        int x = g.guiWidth() - Math.round((w + 4) * shown), y = g.guiHeight() - h - 4;
        g.fill(x, y, x + w, y + h, PAPER);
        g.outline(x, y, w, h, EDGE);
        g.text(font, title, x + 7, y + 5, INK, false);
        g.text(font, line, x + 7, y + 15, FADED, false);
    }

    /** Its listeners on the mod bus: on 1.21.1 a subscriber listens on one bus. */
    @EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        static void onRegisterLayers(RegisterGuiLayersEvent event) {
            event.registerAboveAll(ID, (graphics, delta) -> {
                GuiGraphicsExtractor g = new GuiGraphicsExtractor(graphics);
                if (Minecraft.getInstance().screen == null) draw(g);
            });
        }
    }
}
