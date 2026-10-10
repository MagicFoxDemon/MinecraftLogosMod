package dev.logos.item;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 术式载页 grades. A page caps how much mana a cast may inject, and decides how much of a spell's
 * entropic mana (left over when it is extinguished rather than ending on its own) returns as free
 * mana. Both numbers are defaults; the config file can change them.
 */
public enum PageTier {
    BASIC("basic", 50, 0.10),
    CRYSTAL("crystal", 150, 0.15),
    ECHO("echo", 400, 0.20),
    TRANSMUTED("transmuted", 800, 0.25),
    CHALK("chalk", 1600, 0.30),
    FIRMAMENT("firmament", 3200, 0.35),
    SAGE("sage", 6400, 0.40),
    /** 无相之理 when empty, 至高之理 once written. */
    PRINCIPLE("principle", 12800, 0.50);

    public final String id;
    public final int defaultCapacity;
    public final double defaultRecovery;

    PageTier(String id, int defaultCapacity, double defaultRecovery) {
        this.id = id;
        this.defaultCapacity = defaultCapacity;
        this.defaultRecovery = defaultRecovery;
    }

    /** Most mana a cast from this page may inject. */
    public int capacity() {
        return LogosConfig.pageCapacity(this);
    }

    /** Share of entropic mana that comes back as free mana. */
    public double recovery() {
        return LogosConfig.pageRecovery(this);
    }

    public Item blank() {
        return LogosMod.PAGES.get(this).blank().get();
    }

    public Item inscribed() {
        return LogosMod.PAGES.get(this).inscribed().get();
    }

    /** Registry names of the two items: the basic grade keeps its original ids. */
    public String blankId() {
        return switch (this) {
            case BASIC -> "spell_page";
            case PRINCIPLE -> "formless_principle";
            default -> id + "_spell_page";
        };
    }

    public String inscribedId() {
        return switch (this) {
            case BASIC -> "inscribed_page";
            case PRINCIPLE -> "supreme_principle";
            default -> id + "_inscribed_page";
        };
    }

    /** The lowest grade that holds {@code mana} (the highest if none does). */
    public static PageTier fitting(double mana) {
        for (PageTier t : values()) if (t.capacity() >= mana) return t;
        return PRINCIPLE;
    }

    /** The two items of one grade. */
    public record Items(DeferredItem<SpellPageItem> blank, DeferredItem<SpellPageItem> inscribed) {}
}
