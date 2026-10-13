package dev.logos.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import dev.logos.lang.Intent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Mercury / Binding: who and what a spell may reach. */
public final class Targeting {
    /** Block searches are cubic; this keeps one selector to at most ~4k blocks. */
    public static final int MAX_BLOCK_RADIUS = 8;

    private Targeting() {}

    /**
     * Living, not the caster, not an ally, pet, familiar or armor stand. Other players only when the spell
     * was written to affect players ({@code :players true} on the invocation).
     */
    public static boolean isEnemy(SpellInstance inst, Entity e) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || e == inst.caster || e.isSpectator()) return false;
        if (e instanceof ArmorStand) return false;
        if (e instanceof Player && !inst.spell.affectsPlayers()) return false;
        if (e instanceof OwnableEntity o && o.getOwner() == inst.caster) return false;
        // Nor the caster's own familiars.
        if (inst.caster.getUUID().equals(Familiars.masterOf(e))) return false;
        // A familiar casting (a mana lifeform) spares its master's whole side: them, their familiars, their pets.
        UUID master = Familiars.masterOf(inst.caster);
        if (master != null && (Familiars.friendly(master, e)
                || e instanceof OwnableEntity o && o.getOwner() != null && master.equals(o.getOwner().getUUID()))) return false;
        return !inst.caster.isAlliedTo(e);
    }

    /** Whether a spell effect may touch {@code e} at all: the caster always, other players only if allowed. */
    public static boolean mayAffect(SpellInstance inst, Entity e) {
        // A lifeform's spells may always touch its master, as a player's may touch themselves: that is how it heals and wards them.
        return e == inst.caster || !(e instanceof Player) || inst.spell.affectsPlayers() || e.getUUID().equals(Familiars.masterOf(inst.caster));
    }

    /**
     * What a projectile, ray or beam strikes: enemies, and for a page filed under HEAL or WARD also anyone
     * the spell may help — so a lifeform's healing bolt lands on its master instead of passing through them.
     */
    public static boolean mayHit(SpellInstance inst, Entity e) {
        if (isEnemy(inst, e)) return true;
        Intent intent = inst.spell.profile().primary();
        return (intent == Intent.HEAL || intent == Intent.WARD) && e instanceof LivingEntity le && le.isAlive() && e != inst.caster
                && !e.isSpectator() && !(e instanceof ArmorStand) && mayAffect(inst, e);
    }

    public static List<LivingEntity> enemiesNear(SpellInstance inst, ServerLevel level, Vec3 at, double radius, boolean fresh) {
        return near(inst, level, at, radius, fresh, e -> isEnemy(inst, e)).stream().map(e -> (LivingEntity) e).toList();
    }

    public static List<Entity> near(SpellInstance inst, ServerLevel level, Vec3 at, double radius, boolean fresh, Predicate<Entity> filter) {
        double r = Math.max(0, Math.min(radius, 64));
        return level.getEntities(inst.caster, new AABB(at, at).inflate(r), filter::test).stream()
                .filter(e -> center(e).distanceTo(at) <= r + e.getBbWidth() / 2)
                .filter(e -> !fresh || !inst.affected.contains(e.getUUID()))
                .sorted(Comparator.comparingDouble(e -> center(e).distanceToSqr(at)))
                .toList();
    }

    public static Vec3 center(Entity e) {
        return e.getBoundingBox().getCenter();
    }

    // ---- filters: "minecraft:zombie" (an id) or "#minecraft:undead" (a tag); empty matches all ----

    public static boolean entityMatches(Entity e, String filter) {
        if (filter == null || filter.isBlank()) return true;
        Holder<EntityType<?>> type = e.getType().builtInRegistryHolder();
        return matches(filter, type, Registries.ENTITY_TYPE, BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
    }

    public static boolean itemMatches(ItemEntity e, String filter) {
        if (filter == null || filter.isBlank()) return true;
        Item item = e.getItem().getItem();
        return matches(filter, item.builtInRegistryHolder(), Registries.ITEM, BuiltInRegistries.ITEM.getKey(item));
    }

    public static boolean blockMatches(BlockState state, String filter) {
        if (filter == null || filter.isBlank()) return true;
        Block block = state.getBlock();
        return matches(filter, block.builtInRegistryHolder(), Registries.BLOCK, BuiltInRegistries.BLOCK.getKey(block));
    }

    private static <T> boolean matches(String filter, Holder<T> holder, net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<T>> registry, Identifier id) {
        String f = filter.trim();
        if (f.startsWith("#")) {
            Identifier tag = Identifier.tryParse(f.substring(1));
            return tag != null && holder.is(TagKey.create(registry, tag));
        }
        Identifier want = Identifier.tryParse(f);
        return want != null && want.equals(id);
    }

    /** Non-air blocks within a sphere, nearest first. */
    public static List<Place> blocksNear(ServerLevel level, Vec3 at, double radius, Filter filter) {
        int r = (int) Math.ceil(Math.max(0, Math.min(radius, MAX_BLOCK_RADIUS)));
        BlockPos c = BlockPos.containing(at);
        List<Place> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
            if (Vec3.atCenterOf(p).distanceTo(at) > radius + 0.5) continue;
            BlockState s = level.getBlockState(p);
            if (s.isAir() || !filter.testBlock(s)) continue;
            out.add(Place.center(level, p.immutable()));
        }
        out.sort(Comparator.comparingDouble(pl -> pl.pos().distanceToSqr(at)));
        return out;
    }
}
