package dev.logos.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.logos.graph.Dimensions;
import dev.logos.graph.EnumValues.Kind;
import dev.logos.lang.ConstructShape;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import dev.logos.lang.TargetKind;
import dev.logos.lang.Weather;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A small index for one open editor; entries come from the running game, including installed mods. */
final class EnumCatalog {
    record Entry(Kind kind, String id, String name, boolean usable) {
        boolean matches(String query) { return (name + " " + id).toLowerCase(Locale.ROOT).contains(query); }
        @Override public boolean usable() { return usable && (kind != Kind.CREATURE || EnumPreviews.summonable(id)); }
    }

    private final Map<Kind, List<Entry>> entries = new EnumMap<>(Kind.class);

    List<Entry> entries(Kind kind) { return entries.computeIfAbsent(kind, this::read); }

    static Component name(Kind kind, String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) return Component.literal(id);
        return switch (kind) {
            case CREATURE -> BuiltInRegistries.ENTITY_TYPE.getOptional(key).map(t -> t.getDescription()).orElse(Component.literal(id));
            case ITEM -> BuiltInRegistries.ITEM.getOptional(key).map(i -> new ItemStack(i).getHoverName()).orElse(Component.literal(id));
            case BLOCK -> BuiltInRegistries.BLOCK.getOptional(key).map(b -> b.getName()).orElse(Component.literal(id));
            case EFFECT -> BuiltInRegistries.MOB_EFFECT.getOptional(key).map(e -> e.getDisplayName()).orElse(Component.literal(id));
            case DIMENSION -> Component.translatableWithFallback("logos.enum.dimension." + id.replace(':', '.'), id);
            case PARTICLE -> Component.translatableWithFallback("logos.enum.particle." + id.replace(':', '.'), id);
            default -> Component.translatableWithFallback(kind.key() + "." + id, id);
        };
    }

    private List<Entry> read(Kind kind) {
        List<String> ids = new ArrayList<>();
        switch (kind) {
            case CREATURE -> BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> ids.add(id.toString()));
            case ITEM -> BuiltInRegistries.ITEM.keySet().stream().filter(id -> !id.toString().equals("minecraft:air")).forEach(id -> ids.add(id.toString()));
            case BLOCK -> BuiltInRegistries.BLOCK.stream().filter(b -> !b.defaultBlockState().isAir()).forEach(b -> ids.add(BuiltInRegistries.BLOCK.getKey(b).toString()));
            case PARTICLE -> BuiltInRegistries.PARTICLE_TYPE.keySet().forEach(id -> ids.add(id.toString()));
            case EFFECT -> BuiltInRegistries.MOB_EFFECT.keySet().forEach(id -> ids.add(id.toString()));
            case DIMENSION -> ids.addAll(Dimensions.known.get());
            case ELEMENT -> { for (Element e : Element.values()) if (Element.parse(e.name().toLowerCase(Locale.ROOT)) != null) ids.add(e.name().toLowerCase(Locale.ROOT)); }
            case MOTION -> { for (Motion m : Motion.values()) ids.add(m.name().toLowerCase(Locale.ROOT)); }
            case SHAPE -> { for (ConstructShape s : ConstructShape.values()) ids.add(s.word()); }
            case WEATHER -> { for (Weather w : Weather.values()) ids.add(w.word()); }
            case KIND -> { for (TargetKind k : TargetKind.values()) ids.add(k.word); }
        }
        // Do not instantiate thousands of modded mobs just to sort a catalogue. Check only its visible page.
        Comparator<Entry> order = kind == Kind.CREATURE ? Comparator.comparing(Entry::id)
                : Comparator.comparing((Entry e) -> !e.usable()).thenComparing(Entry::id);
        return ids.stream().map(id -> new Entry(kind, id, name(kind, id).getString(), usable(kind, id))).sorted(order).toList();
    }

    static boolean usable(Kind kind, String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) return false;
        return switch (kind) {
            case CREATURE -> BuiltInRegistries.ENTITY_TYPE.containsKey(key);
            case ITEM -> BuiltInRegistries.ITEM.containsKey(key);
            case BLOCK -> BuiltInRegistries.BLOCK.getOptional(key).map(b -> b.asItem() != Items.AIR).orElse(false);
            case PARTICLE -> BuiltInRegistries.PARTICLE_TYPE.getOptional(key).map(p -> p instanceof SimpleParticleType).orElse(false);
            case EFFECT -> BuiltInRegistries.MOB_EFFECT.containsKey(key);
            case DIMENSION -> Dimensions.known.get().contains(id);
            default -> true;
        };
    }
}
