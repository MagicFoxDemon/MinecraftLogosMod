package dev.logos.client;

import java.util.List;

import dev.logos.item.SpellBag;
import dev.logos.item.SpellPages;
import dev.logos.lang.Node;
import dev.logos.lang.Spell;
import dev.logos.net.SelectSpellPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The spell wheel: hold the wheel key, point at a spell, release (or click) to select it for the
 * wand. Up to {@link #PER_PAGE} spells sit on the ring; with more, the scroll wheel pages through
 * them, so a codex full of pages stays usable.
 */
public class SpellWheelScreen extends Screen {
    static final int PER_PAGE = 10;
    private static final int BOX = 24;

    private final List<SpellBag.Entry> entries;
    private final int selected;
    private int page;
    private int hovered = -1;

    public SpellWheelScreen(List<SpellBag.Entry> entries, int selected) {
        super(Component.literal("术式轮盘"));
        this.entries = entries;
        this.selected = Math.floorMod(selected, Math.max(1, entries.size()));
        this.page = this.selected / PER_PAGE;
    }

    private int pages() {
        return Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
    }

    private int onPage() {
        return Math.min(PER_PAGE, entries.size() - page * PER_PAGE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        int cx = width / 2;
        int cy = height / 2;
        int n = onPage();
        int radius = Math.max(60, Math.min(width, height) / 3);
        hovered = hoveredIndex(mouseX - cx, mouseY - cy, n);

        for (int k = 0; k < n; k++) {
            int global = page * PER_PAGE + k;
            SpellBag.Entry e = entries.get(global);
            double ang = -Math.PI / 2 + Math.PI * 2 * k / n;
            int x = cx + (int) (Math.cos(ang) * radius);
            int y = cy + (int) (Math.sin(ang) * radius);
            int bg = k == hovered ? 0xE0B060FF : global == selected ? 0xC0E8B84A : 0xB0181822;
            int border = e.spell().isPassive() ? 0xFF6FD3E0 : e.fromCodex() ? 0xFFE08A3F : 0xFFE6E2D3;
            g.fill(x - BOX / 2, y - BOX / 2, x + BOX / 2, y + BOX / 2, bg);
            g.outline(x - BOX / 2, y - BOX / 2, BOX, BOX, border);
            g.item(e.page(), x - 8, y - 8);
            String name = SpellPages.name(e.page(), e.spell()).getString();
            if (name.length() > 8) name = name.substring(0, 8) + "…";
            g.centeredText(font, (e.spell().isPassive() ? "⚑ " : "") + name, x, y + BOX / 2 + 3, k == hovered ? 0xFFFFFFFF : 0xFFCFCFCF);
        }

        // Centre: details of the spell under the pointer (or the selected one).
        int show = hovered >= 0 ? page * PER_PAGE + hovered : selected;
        if (!entries.isEmpty() && show < entries.size()) {
            Spell s = entries.get(show).spell();
            g.fill(cx - 70, cy - 26, cx + 70, cy + 26, 0xC0101018);
            g.outline(cx - 70, cy - 26, 140, 52, 0xFFB57FE0);
            g.centeredText(font, "《" + SpellPages.name(entries.get(show).page(), s).getString() + "》", cx, cy - 20, 0xFFE8B84A);
            g.centeredText(font, Spell.triggerZh(s.trigger()) + " · 注入 " + Node.fmt(s.mana()), cx, cy - 8, 0xFFCFCFCF);
            g.centeredText(font, (entries.get(show).fromCodex() ? "来自法式典籍" : "挂在法杖上") + " · 稳定度 " + Node.fmt(s.stability()), cx, cy + 4, 0xFF9F9FAF);
            if (pages() > 1) g.centeredText(font, "◀ " + (page + 1) + " / " + pages() + " ▶  滚轮翻页", cx, cy + 15, 0xFF6FD3E0);
        }
    }

    /** Which ring entry the pointer points at: by angle, once it leaves the centre. */
    private static int hoveredIndex(double dx, double dy, int n) {
        if (n == 0 || dx * dx + dy * dy < 30 * 30) return -1;
        double ang = Math.atan2(dy, dx) + Math.PI / 2; // 0 at the top, clockwise
        double step = Math.PI * 2 / n;
        return (int) Math.floorMod(Math.round(ang / step), n);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (pages() > 1 && scrollY != 0) page = Math.floorMod(page + (scrollY < 0 ? 1 : -1), pages());
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        confirm();
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (LogosClient.SPELL_WHEEL.matches(event)) {
            confirm();
            return true;
        }
        return super.keyReleased(event);
    }

    private void confirm() {
        if (hovered >= 0) ClientPacketDistributor.sendToServer(new SelectSpellPayload(page * PER_PAGE + hovered));
        onClose();
    }

    /** Opens the wheel for the wand the player is holding, if it has any spells. */
    public static void tryOpen() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        ItemStack wand = SpellBag.heldWand(mc.player);
        if (wand == null) return;
        var entries = SpellBag.entries(mc.player, wand);
        if (entries.isEmpty()) {
            mc.player.sendOverlayMessage(Component.literal("法杖上没有术式：潜行右键挂上术式载页，或在副手拿法式典籍"));
            return;
        }
        mc.setScreen(new SpellWheelScreen(entries, wand.getOrDefault(dev.logos.LogosMod.SELECTED.get(), 0)));
    }
}
