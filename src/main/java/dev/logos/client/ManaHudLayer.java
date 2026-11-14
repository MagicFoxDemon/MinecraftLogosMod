package dev.logos.client;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.net.ManaHudPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/**
 * The mana HUD: a crystal and a brass-framed bar of ten cells in a corner of the screen (bottom left unless the
 * client config says otherwise), with the pool's numbers, what a held staff stores, and the selected spell under it.
 * It slides in from the screen's edge while mana matters and slides back out when it does not:
 * <ul>
 * <li>shown while a wand or staff is in hand (charging and casting happen there), while the pool is not full, and for
 * {@link #LINGER_TICKS} after the pool last changed (a potion, an elixir, a spell from a codex);</li>
 * <li>gone when the pool is full and has been still that long with no wand in hand, and always with F1.</li>
 * </ul>
 * The bar moves smoothly between the server's updates (two a second at most), a gold mark shows what the selected
 * spell injects (red when the pool or staff cannot pay it), and what a charge has drawn so far shows pale beyond
 * the mana it came from. Everything is laid in whole GUI pixels by {@link ManaHudArt}; no texture is used.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class ManaHudLayer {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(LogosMod.MODID, "mana_hud");
    /** How long the HUD stays after the pool last changed. */
    static final int LINGER_TICKS = 60;
    /** Ticks to slide all the way in, and all the way out. */
    static final int IN_TICKS = 6, OUT_TICKS = 9;
    /** From the screen's edge, and inside the plate. */
    private static final int MARGIN = 3, PAD = 3;
    /** Where the bar starts, right of the crystal; where the numbers start, right of the bar. */
    private static final int BAR_X = ManaHudArt.GEM_W + 3, TEXT_X = BAR_X + ManaHudArt.BAR_W + 4;

    private static @Nullable ManaHudPayload last;
    private static Component spell = Component.empty();
    /** The bar's mana as drawn, easing towards the last number heard, and where it stood a tick ago. */
    private static float shownMana, shownManaBefore;
    private static int sinceChange = LINGER_TICKS;
    private static float slide, slideBefore;
    private static int age;
    private static @Nullable Float heldSlide;
    private static final Map<Long, Manuscript.Plan> PLANS = new HashMap<>();

    private ManaHudLayer() {}

    @SubscribeEvent
    static void onRegisterPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(ManaHudPayload.TYPE, (payload, ctx) -> receive(payload));
    }

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.CHAT, ID, ManaHudLayer::render);
    }

    static void receive(ManaHudPayload p) {
        if (last == null) shownMana = shownManaBefore = p.mana();
        else if (last.mana() != p.mana()) sinceChange = 0;
        if (!p.wand()) spell = Component.empty();
        else p.spell().ifPresent(s -> spell = s);
        last = p;
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (last == null || Minecraft.getInstance().isPaused()) return;
        age++;
        if (sinceChange < LINGER_TICKS) sinceChange++;
        shownManaBefore = shownMana;
        float gap = last.mana() - shownMana;
        shownMana = Math.abs(gap) < 0.5f ? last.mana() : shownMana + gap * 0.35f;
        slideBefore = slide;
        slide = wanted() ? Math.min(1, slide + 1f / IN_TICKS) : Math.max(0, slide - 1f / OUT_TICKS);
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        last = null;
        spell = Component.empty();
        sinceChange = LINGER_TICKS;
        slide = slideBefore = 0;
        PLANS.clear();
    }

    /** Whether mana matters now (see the class comment); the HUD slides towards showing while it does. */
    static boolean wanted() {
        ManaHudPayload p = last;
        if (p == null) return false;
        return p.wand() || p.mana() < p.maxMana() || p.drawing() > 0 || sinceChange < LINGER_TICKS;
    }

    /** How far in the HUD is: 0 gone, 1 all the way in. */
    public static float slide() {
        return heldSlide != null ? heldSlide : slide;
    }

    /** Holds the HUD at {@code at} of the way in, for screenshots of it mid-slide; null lets it move again. */
    public static void holdSlide(@Nullable Float at) {
        heldSlide = at;
    }

    /** What the HUD says while it is on screen, or null when it is gone: for whoever reads it out rather than looks at it. */
    public static @Nullable Component text() {
        ManaHudPayload p = last;
        if (p == null || slide() <= 0) return null;
        return spell.getString().isEmpty() ? p.head() : p.head().append(" ").append(spell);
    }

    /** {@link #text} as plain text. */
    public static @Nullable String line() {
        Component t = text();
        return t == null ? null : t.getString();
    }

    private static Manuscript.Plan plan(long key, java.util.function.Supplier<Manuscript.Plan> make) {
        if (PLANS.size() > 256) PLANS.clear();
        return PLANS.computeIfAbsent(key, k -> make.get());
    }

    private static int cells(double amount, int max) {
        return max <= 0 ? 0 : Mth.clamp((int) Math.round(ManaHudArt.INNER * amount / max), 0, ManaHudArt.INNER);
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        ManaHudPayload p = last;
        if (p == null || mc.options.hideGui) return;
        float t = delta.getGameTimeDeltaPartialTick(false);
        float s = heldSlide != null ? heldSlide : Mth.lerp(t, slideBefore, slide);
        if (s <= 0) return;
        float eased = s * s * (3 - 2 * s);
        Font font = mc.font;
        boolean staff = p.capacity() > 0;
        String numbers = p.mana() + "/" + p.maxMana();
        String held = staff ? p.stored() + "/" + p.capacity() : "";
        int width = TEXT_X + Math.max(font.width(numbers), font.width(held));
        int rows = ManaHudArt.GEM_H + (staff ? 9 : 0);
        boolean spelled = !spell.getString().isEmpty();
        if (spelled) width = Math.max(width, font.width(spell));
        int height = rows + (spelled ? 10 : 0);
        int w = width + PAD * 2, h = height + PAD * 2;

        LogosConfig.Corner corner = LogosConfig.manaHudCorner();
        int dx = LogosConfig.manaHudOffsetX(), dy = LogosConfig.manaHudOffsetY();
        int home = corner.right() ? g.guiWidth() - MARGIN - dx - w : MARGIN + dx;
        int y = corner.bottom() ? g.guiHeight() - MARGIN - dy - h : MARGIN + dy;
        // Out past the edge it is on: as far as it is wide, plus the gap it keeps from that edge.
        int away = Math.round((1 - eased) * (w + MARGIN + dx));
        int x = corner.right() ? home + away : home - away;

        g.nextStratum();
        plan(((long) w << 16) | h, () -> ManaHudArt.plate(w, h)).draw(g, x, y);
        int cx = x + PAD, cy = y + PAD;
        int source = staff ? p.stored() : p.mana();
        boolean enough = p.cost() <= 0 || source + p.drawing() >= p.cost();
        plan(enough ? 1 : 2, () -> ManaHudArt.gem(enough)).draw(g, cx, cy);

        // The pool.
        int bx = cx + BAR_X, by = cy + 2;
        plan(3, () -> ManaHudArt.frame(true, ManaHudArt.BAR_H)).draw(g, bx, by);
        int filled = cells(Mth.lerp(t, shownManaBefore, shownMana), p.maxMana());
        plan(1000 + filled, () -> ManaHudArt.fill(filled, ManaHudArt.BAR_H, false)).draw(g, bx + 1, by + 1);
        int drawn = Math.min(cells(p.drawing(), p.maxMana()), ManaHudArt.INNER - filled);
        if (drawn > 0) g.fill(bx + 1 + filled, by + 1, bx + 1 + filled + drawn, by + ManaHudArt.BAR_H - 1, 0x80BFE6FF);
        if (filled > 4) {
            // A glint that runs along the top of the mana now and then.
            int run = (age * 2) % (filled + 60);
            if (run < filled - 2) g.fill(bx + 1 + run, by + 1, bx + 3 + run, by + 2, 0xD0EAF8FF);
        }
        if (p.cost() > 0 && !staff) {
            int at = cells(Math.min(p.cost(), p.maxMana()), p.maxMana());
            plan(enough ? 4 : 5, () -> ManaHudArt.mark(ManaHudArt.BAR_H, enough)).draw(g, bx + at, by);
        }
        g.text(font, numbers, cx + TEXT_X, cy + 1, p.mana() >= p.maxMana() ? 0xFFBFE6FF : 0xFF79BDFF, true);

        // What a staff holds, and casts from.
        if (staff) {
            int gy = cy + ManaHudArt.GEM_H + 2;
            plan(6, () -> ManaHudArt.frame(false, ManaHudArt.GAUGE_H)).draw(g, bx, gy);
            int stored = cells(p.stored(), p.capacity());
            plan(2000 + stored, () -> ManaHudArt.fill(stored, ManaHudArt.GAUGE_H, true)).draw(g, bx + 1, gy + 1);
            if (p.cost() > 0) {
                int at = cells(Math.min(p.cost(), p.capacity()), p.capacity());
                plan(enough ? 7 : 8, () -> ManaHudArt.mark(ManaHudArt.GAUGE_H, enough)).draw(g, bx + at, gy);
            }
            g.text(font, held, cx + TEXT_X, gy - 2, ManaHudArt.AMBER_LIT, true);
        }
        if (spelled) g.text(font, spell, cx, cy + rows + 1, 0xFFFFFFFF, true);
    }
}
