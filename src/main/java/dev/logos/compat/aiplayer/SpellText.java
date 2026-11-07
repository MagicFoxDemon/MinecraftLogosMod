package dev.logos.compat.aiplayer;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.logos.LogosMod;
import dev.logos.item.PageTier;
import dev.logos.lang.Intent;
import dev.logos.lang.Spell;

/** A spell as data an AI reads: what it does and what page it needs, with no game needed to work it out. */
final class SpellText {
    private SpellText() {}

    /** Name, mana, trigger, intents, reach and the lowest page grade that holds a whole cast. */
    static JsonObject summary(Spell spell) {
        JsonObject out = new JsonObject();
        out.addProperty("name", spell.name());
        out.addProperty("mana", spell.mana());
        out.addProperty("trigger", spell.trigger());
        out.addProperty("trigger_zh", Spell.triggerZh(spell.trigger()));
        JsonArray intents = new JsonArray();
        for (Intent i : spell.profile().intents()) intents.add(i.word);
        out.add("intents", intents);
        out.addProperty("intents_zh", spell.profile().intentsZh());
        out.addProperty("range", spell.profile().range());
        out.addProperty("estimated_cost", spell.profile().estimate());
        out.addProperty("affects_players", spell.affectsPlayers());
        PageTier tier = PageTier.fitting(spell.mana());
        out.addProperty("page_grade", tier.id);
        out.addProperty("page_item", LogosMod.MODID + ":" + tier.blankId());
        return out;
    }

    /** The comment lines a source opens with, which is how the examples say what they do; empty when there are none. */
    static String note(String source) {
        StringBuilder out = new StringBuilder();
        for (String line : source.split("\n")) {
            String s = line.strip();
            if (!s.startsWith(";")) break;
            if (!out.isEmpty()) out.append(' ');
            out.append(s.replaceFirst("^;+\\s*", ""));
        }
        return out.toString();
    }

    /** Of the grades carried, the lowest that holds {@code mana}, else the highest carried; null when none is. */
    static @Nullable PageTier fit(List<PageTier> carried, double mana) {
        PageTier best = null;
        for (PageTier t : carried) {
            if (t.capacity() >= mana && (best == null || best.capacity() < mana || t.capacity() < best.capacity())) best = t;
            else if (best == null || (best.capacity() < mana && t.capacity() > best.capacity())) best = t;
        }
        return best;
    }
}
