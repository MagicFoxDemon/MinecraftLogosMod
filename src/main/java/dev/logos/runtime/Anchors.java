package dev.logos.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.net.AnchorsPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * ⚓ A player's named anchors: places in the world a spell can name as {@code ^name}. They are
 * kept with the player (through death), set with an anchor nail or the {@code anchor} rune — both
 * spend one nail — and synced to the player's client for the editor's list and the markers.
 */
public final class Anchors {
    public static final int NAME_MAX = 16;

    public record Anchor(String name, ResourceKey<Level> dimension, Vec3 pos) {
        static final Codec<Anchor> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Anchor::name),
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Anchor::dimension),
                Vec3.CODEC.fieldOf("pos").forGetter(Anchor::pos)
        ).apply(i, Anchor::new));
    }

    public static final MapCodec<Anchors> CODEC = Anchor.CODEC.listOf().fieldOf("anchors").xmap(Anchors::new, a -> new ArrayList<>(a.byName.values()));

    private final Map<String, Anchor> byName = new LinkedHashMap<>();

    public Anchors() {}

    private Anchors(List<Anchor> list) {
        for (Anchor a : list) byName.put(a.name(), a);
    }

    public static Anchors of(Player p) {
        return p.getData(LogosMod.ANCHORS);
    }

    public @Nullable Anchor get(String name) {
        return byName.get(name);
    }

    public Collection<Anchor> all() {
        return byName.values();
    }

    /** A usable anchor name, or null: 1–16 characters, no spaces or brackets (it is written as ^name in source). */
    public static @Nullable String clean(String name) {
        if (name == null) return null;
        String n = name.strip();
        if (n.isEmpty() || n.length() > NAME_MAX || !n.matches("[^\\s()\\[\\]\";^]+")) return null;
        return n;
    }

    /**
     * Sets (or moves) anchor {@code name} to {@code pos}, spending one anchor nail from the
     * player's inventory unless they are in creative mode. Returns null on success, else why not.
     */
    public static @Nullable String set(ServerPlayer p, String name, ServerLevel level, Vec3 pos) {
        String n = clean(name);
        if (n == null) return "锚点名需要 1~" + NAME_MAX + " 个字，不能有空格或括号";
        Anchors a = of(p);
        if (!a.byName.containsKey(n) && a.byName.size() >= LogosConfig.maxAnchors()) {
            return "锚点已达上限 " + LogosConfig.maxAnchors() + " 个，先撤掉一个（配置 maxAnchors）";
        }
        if (!p.getAbilities().instabuild && !takeNail(p)) return "背包里没有锚钉";
        a.byName.put(n, new Anchor(n, level.dimension(), pos));
        sync(p);
        return null;
    }

    public static boolean remove(ServerPlayer p, String name) {
        boolean removed = of(p).byName.remove(name) != null;
        if (removed) sync(p);
        return removed;
    }

    private static boolean takeNail(ServerPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(LogosMod.ANCHOR_NAIL.get())) {
                s.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** Where {@code name} is, with its world; null if there is no such anchor or its world is gone. */
    public static @Nullable Place resolve(ServerPlayer p, String name) {
        Anchor a = of(p).get(name);
        if (a == null) return null;
        ServerLevel level = p.level().getServer().getLevel(a.dimension());
        return level == null ? null : new Place(level, a.pos());
    }

    public static void sync(ServerPlayer p) {
        if (p.connection != null && p.connection.hasChannel(AnchorsPayload.TYPE)) {
            PacketDistributor.sendToPlayer(p, AnchorsPayload.of(of(p).all()));
        }
    }
}
