package dev.logos.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Mercury / Binding: who and what a spell may reach. */
public final class Targeting {
    /** Block searches are cubic; this keeps one selector to at most ~4k blocks. */
    public static final int MAX_BLOCK_RADIUS = 8;
    /** The furthest any entity search reaches; {@code maxGroupRadius} may not go past it, since a group is priced by its radius. */
    public static final int MAX_RADIUS = 64;

    private Targeting() {}

    /**
     * Living, not the caster, not an ally, pet, familiar or armor stand. Other players only when the spell
     * was written to affect players ({@code :players true} on the invocation). Only what a spell asks for by
     * that name goes by this (the enemy selectors, {@code (kind enemies)}, a homing form's seeking); what a
     * form touches does not, see {@link #touches}.
     */
    public static boolean isEnemy(SpellInstance inst, Entity e) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || e == inst.caster || e.isSpectator()) return false;
        if (e instanceof ArmorStand) return false;
        if (e instanceof Player && !inst.spell.affectsPlayers()) return false;
        if (e instanceof OwnableEntity o && o.getOwner() == inst.caster) return false;
        // Nor the caster's own familiars.
        if (inst.caster.getUUID().equals(Familiars.masterOf(e))) return false;
        // A familiar casting (a mana lifeform), or a tamed creature (a maid), spares its master's whole side: them, their familiars, their pets.
        UUID master = Familiars.sideOf(inst.caster);
        if (master != null && (Familiars.friendly(master, e)
                || e instanceof OwnableEntity o && o.getOwner() != null && master.equals(o.getOwner().getUUID()))) return false;
        return !inst.caster.isAlliedTo(e);
    }

    /** Whether a spell effect may touch {@code e} at all: the caster always, other players only if allowed. */
    public static boolean mayAffect(SpellInstance inst, Entity e) {
        // A lifeform's (or a maid's) spells may always touch its master, as a player's may touch themselves: that is how it heals and wards them.
        return e == inst.caster || !(e instanceof Player) || inst.spell.affectsPlayers() || e.getUUID().equals(Familiars.sideOf(inst.caster));
    }

    /** Whether {@code player} may change this block (spawn protection, claims, adventure mode…): asked of a spell's caster, and of whoever edits a block from a screen. */
    public static boolean mayEdit(ServerPlayer player, Level level, BlockPos pos) {
        return level.mayInteract(player, pos) && !player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer());
    }

    /**
     * What a field takes in: every creature standing in it that the spell may affect, its own caster included.
     * A form asks nothing about sides, nor about what its handlers do; whom the spell helps or harms is for
     * the spell to say, with {@code (is target (kind …))}.
     */
    public static boolean touches(SpellInstance inst, Entity e) {
        return e instanceof LivingEntity le && le.isAlive() && !e.isSpectator() && !(e instanceof ArmorStand) && mayAffect(inst, e);
    }

    /**
     * What a projectile, ray, beam or vein strikes: as {@link #touches}, but never the one who sent it out,
     * nor what they ride or who rides with them, as an arrow does not hit its archer's horse on the way out.
     */
    public static boolean strikes(SpellInstance inst, Entity e) {
        return e != inst.caster && !inst.caster.isPassengerOfSameVehicle(e) && touches(inst, e);
    }

    public static List<LivingEntity> enemiesNear(SpellInstance inst, ServerLevel level, Vec3 at, double radius, boolean fresh) {
        return near(inst, level, at, radius, fresh, e -> isEnemy(inst, e)).stream().map(e -> (LivingEntity) e).toList();
    }

    public static List<Entity> near(SpellInstance inst, ServerLevel level, Vec3 at, double radius, boolean fresh, Predicate<Entity> filter) {
        double r = Math.max(0, Math.min(radius, MAX_RADIUS));
        // Distance first: most of what the box catches stops there, before the costlier filter (isEnemy) is asked.
        List<Entity> found = level.getEntities(inst.caster, new AABB(at, at).inflate(r), e -> center(e).distanceTo(at) <= r + e.getBbWidth() / 2
                && (!fresh || !inst.affected.contains(e.getUUID())) && filter.test(e));
        found.sort(Comparator.comparingDouble(e -> center(e).distanceToSqr(at)));
        return found;
    }

    /**
     * {@code found} with the caster added in its place by distance, when they are in reach and {@code admits}
     * takes them in: {@link #near} leaves the caster out, while a field and the {@code entities} selector
     * count them like anyone else.
     */
    public static <E extends Entity> List<E> withCaster(SpellInstance inst, List<E> found, Place at, double radius, Predicate<Entity> admits) {
        Entity self = inst.caster;
        if (!self.isAlive() || self.isSpectator() || self.level() != at.level() || found.contains(self)) return found;
        double r = Math.max(0, Math.min(radius, MAX_RADIUS));
        if (center(self).distanceTo(at.pos()) > r + self.getBbWidth() / 2 || !admits.test(self)) return found;
        List<E> out = new ArrayList<>(found);
        @SuppressWarnings("unchecked") E e = (E) self;
        out.add(e);
        out.sort(Comparator.comparingDouble(x -> center(x).distanceToSqr(at.pos())));
        return out;
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

    private static <T> boolean matches(String filter, Holder<T> holder, net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<T>> registry, ResourceLocation id) {
        String f = filter.trim();
        if (f.startsWith("#")) {
            ResourceLocation tag = ResourceLocation.tryParse(f.substring(1));
            return tag != null && holder.is(TagKey.create(registry, tag));
        }
        ResourceLocation want = ResourceLocation.tryParse(f);
        return want != null && want.equals(id);
    }

    /** Non-air blocks within a sphere, nearest first. */
    public static List<Place> blocksNear(ServerLevel level, Vec3 at, double radius, Filter filter) {
        int r = (int) Math.ceil(Math.max(0, Math.min(radius, MAX_BLOCK_RADIUS)));
        BlockPos c = BlockPos.containing(at);
        List<Place> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
            if (Vec3.atCenterOf(p).distanceTo(at) > radius + 0.5 || !SpellWorld.loaded(level, p)) continue;
            BlockState s = level.getBlockState(p);
            if (s.isAir() || !filter.testBlock(s)) continue;
            out.add(Place.center(level, p.immutable()));
        }
        out.sort(Comparator.comparingDouble(pl -> pl.pos().distanceToSqr(at)));
        return out;
    }
}
