package dev.logos.compat.tlm.client;

import javax.annotation.Nullable;

import com.github.tartaricacid.touhoulittlemaid.api.event.client.MaidContainerGuiEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import dev.logos.LogosMod;
import dev.logos.runtime.CreatureMana;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * A tamed maid's mana in her screen: a slim vial standing at the right edge of her portrait, under a small mana
 * crystal, filled from the bottom; hovered, it gives the exact amount. The rows under the portrait are the maid mod's
 * own, with no room for one more. Registered on a client only, by {@link dev.logos.compat.tlm.LogosMaidExtension}.
 */
public final class MaidManaView {
    /** The vial's frame, relative to her screen's corner: inside the portrait's black, clear of its corner buttons. */
    private static final int VIAL_X = 66, VIAL_W = 5, VIAL_TOP = 35, VIAL_BOTTOM = 102;
    /** The crystal above it, 9 by 9. */
    private static final int GEM_X = 64, GEM_Y = 24;
    private static final int RIM = 0xFF3A4152, GLASS = 0xFF0E1322, MANA = 0xFF2C6BDB, MANA_LIT = 0xFF79BDFF,
            MANA_DEEP = 0xFF1A3C8E, SHINE = 0xFFD2F0FF;

    private MaidManaView() {}

    /** Her pool as the server last sent it, or null for a maid who holds none. */
    public static @Nullable CreatureMana shown(EntityMaid maid) {
        if (!maid.hasData(LogosMod.CREATURE_MANA)) return null;
        CreatureMana pool = maid.getData(LogosMod.CREATURE_MANA);
        return pool.maxMana() > 0 ? pool : null;
    }

    /** "Mana: mana / max", in whole points. */
    public static Component amount(CreatureMana pool) {
        return Component.translatable("logos.maid.mana", (long) pool.mana(), (long) pool.maxMana());
    }

    @SubscribeEvent
    static void onRender(MaidContainerGuiEvent.Render event) {
        CreatureMana pool = shown(event.getGui().getMaid());
        if (pool == null) return;
        GuiGraphics g = event.getGraphics();
        int x = event.getLeftPos(), y = event.getTopPos();
        gem(g, x + GEM_X, y + GEM_Y);
        int left = x + VIAL_X, right = left + VIAL_W, top = y + VIAL_TOP, bottom = y + VIAL_BOTTOM;
        g.fill(left, top, right, bottom, RIM);
        g.fill(left + 1, top + 1, right - 1, bottom - 1, GLASS);
        int inner = bottom - top - 2;
        int filled = (int) Math.round(inner * Math.min(1, pool.mana() / pool.maxMana()));
        if (filled <= 0) return;
        int surface = bottom - 1 - filled;
        g.fill(left + 1, surface, right - 1, bottom - 1, MANA);
        g.fill(right - 2, surface, right - 1, bottom - 1, MANA_DEEP);
        g.fill(left + 1, surface, right - 1, surface + 1, MANA_LIT);
    }

    @SubscribeEvent
    static void onTooltip(MaidContainerGuiEvent.Tooltip event) {
        CreatureMana pool = shown(event.getGui().getMaid());
        if (pool == null) return;
        int x = event.getMouseX() - event.getLeftPos(), y = event.getMouseY() - event.getTopPos();
        boolean onVial = x >= VIAL_X && x < VIAL_X + VIAL_W && y >= VIAL_TOP && y < VIAL_BOTTOM;
        boolean onGem = x >= GEM_X && x < GEM_X + 9 && y >= GEM_Y && y < GEM_Y + 9;
        if (onVial || onGem) event.getGraphics().renderTooltip(Minecraft.getInstance().font, amount(pool), event.getMouseX(), event.getMouseY());
    }

    /** A small mana crystal, 9 by 9. */
    private static void gem(GuiGraphics g, int x, int y) {
        for (int r = 0; r < 9; r++) {
            int half = r <= 4 ? r : 8 - r;
            g.fill(x + 4 - half, y + r, x + 5 + half, y + r + 1, r == 0 || r == 8 ? MANA_DEEP : r < 4 ? MANA_LIT : MANA);
        }
        g.fill(x + 3, y + 2, x + 4, y + 4, SHINE);
    }
}
