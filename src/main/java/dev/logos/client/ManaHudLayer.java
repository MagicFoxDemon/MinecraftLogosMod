package dev.logos.client;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.net.ManaHudPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/**
 * Draws the mana HUD one line above the action bar, where another message can show at the same time. It looks like
 * an action-bar message and, once the wand is put away, leaves like one: 60 ticks, the last 20 fading.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class ManaHudLayer {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(LogosMod.MODID, "mana_hud");
    private static final int LINGER_TICKS = 60;
    private static final int FADE_TICKS = 20;
    /** A line of text with its backdrop, so ours never touches the action bar's. */
    private static final int LINE_HEIGHT = 13;

    private static Component spell = Component.empty();
    private static @Nullable Component text;
    private static boolean shown;
    private static int lingering;

    private ManaHudLayer() {}

    @SubscribeEvent
    static void onRegisterPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(ManaHudPayload.TYPE, (payload, ctx) -> receive(payload));
    }

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.OVERLAY_MESSAGE, ID, ManaHudLayer::render);
    }

    /** The line as it reads now, or null while no wand is held: for whoever reads the HUD out rather than looks at it. */
    public static @Nullable String line() {
        return shown && text != null ? text.getString() : null;
    }

    private static void receive(ManaHudPayload p) {
        shown = p.shown();
        lingering = LINGER_TICKS;
        if (!shown) return;
        p.spell().ifPresent(s -> spell = s);
        text = p.head().append(spell);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!shown && lingering > 0 && !Minecraft.getInstance().isPaused()) lingering--;
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        spell = Component.empty();
        text = null;
        shown = false;
        lingering = 0;
    }

    /** The line being shown, or null when the HUD is gone. */
    public static @Nullable Component text() {
        return shown || lingering > 0 ? text : null;
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Component line = text();
        if (line == null || mc.options.hideGui) return;
        int alpha = shown ? 255 : Math.min(255, (int) ((lingering - delta.getGameTimeDeltaPartialTick(false)) * 255 / FADE_TICKS));
        if (alpha <= 0) return;
        Font font = mc.font;
        int width = font.width(line);
        // The action bar's own height, which climbs with the health and armour rows beneath it.
        int actionBar = g.guiHeight() - Math.max(Math.max(mc.gui.leftHeight, mc.gui.rightHeight) + 9, 68) - 4;
        g.nextStratum();
        g.textWithBackdrop(font, line, g.guiWidth() / 2 - width / 2, actionBar - LINE_HEIGHT, width, ARGB.white(alpha));
    }
}
