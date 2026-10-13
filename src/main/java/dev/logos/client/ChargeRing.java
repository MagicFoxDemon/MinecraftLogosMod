package dev.logos.client;

import dev.logos.LogosMod;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * The ring round the crosshair while the player charges a wand: it fills clockwise from the top in
 * the spell's element colour, grows a pixel wider per charge tier, and once full glows steadily
 * with a pulse after a brief outward flash.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class ChargeRing {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(LogosMod.MODID, "charge_ring");
    /** Dots round the ring; each lights once its share of the charge is in. */
    private static final int DOTS = 40;
    private static final int FLASH_TICKS = 8;

    private ChargeRing() {}

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, ID, ChargeRing::render);
    }

    static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        ClientCharges.State s = ClientCharges.of(mc.player);
        if (s == null) return;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        float now = ClientCharges.ticks() + pt;
        float progress = s.progress(pt);
        int rgb = ClientCharges.color(s.element);
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        double r = 10 + s.tier;
        float glow = s.full ? 0.8f + 0.2f * Mth.sin(now * 0.35f) : 1;
        for (int i = 0; i < DOTS; i++) {
            double a = -Math.PI / 2 + 2 * Math.PI * i / DOTS;
            int x = cx + (int) Math.round(Math.cos(a) * r), y = cy + (int) Math.round(Math.sin(a) * r);
            boolean lit = (i + 0.5f) / DOTS <= progress;
            g.fill(x - 1, y - 1, x + 1, y + 1, lit ? argb(0xFF, shade(rgb, glow)) : 0x70202020);
        }
        if (s.full && s.fullSince >= 0 && now - s.fullSince < FLASH_TICKS) {
            float f = (now - s.fullSince) / FLASH_TICKS;
            double fr = r + 2 + f * 10;
            int alpha = (int) (0xFF * (1 - f));
            for (int i = 0; i < DOTS; i++) {
                double a = 2 * Math.PI * i / DOTS;
                int x = cx + (int) Math.round(Math.cos(a) * fr), y = cy + (int) Math.round(Math.sin(a) * fr);
                g.fill(x, y, x + 1, y + 1, argb(alpha, shade(rgb, 1.2f)));
            }
        }
    }

    private static int argb(int alpha, int rgb) {
        return Mth.clamp(alpha, 0, 255) << 24 | rgb & 0xFFFFFF;
    }

    /** Scales an RGB colour's brightness, clamping each channel. */
    private static int shade(int rgb, float k) {
        int r = Mth.clamp((int) ((rgb >> 16 & 0xFF) * k), 0, 255);
        int gr = Mth.clamp((int) ((rgb >> 8 & 0xFF) * k), 0, 255);
        int b = Mth.clamp((int) ((rgb & 0xFF) * k), 0, 255);
        return r << 16 | gr << 8 | b;
    }
}
