package dev.logos.gametest;

import java.util.Map;
import java.util.UUID;

import dev.logos.LogosMod;
import dev.logos.block.SpellEmitterBlock;
import dev.logos.block.SpellEmitterBlockEntity;
import dev.logos.runtime.Casting;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.Incarnation;
import dev.logos.runtime.Lifeforms;
import dev.logos.runtime.ManaWrap;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.Possession;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.SpellRuntime;
import dev.logos.runtime.WrapObj;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** What may not outlive its world or its player: the runtime at server stop, a possession's ticket, a caster's spells. */
final class StateLifecycleGameTests {
    private static final String FIELD = "(spell :mana 200 (field :life 10))";
    private static final Vec3 STANDS = new Vec3(4.5, 2, 4.5);

    private StateLifecycleGameTests() {}

    private static ServerPlayer caster(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        return stranger(h);
    }

    @SuppressWarnings("removal")
    private static ServerPlayer stranger(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        p.connection.markClientLoaded();
        Vec3 pos = h.absoluteVec(STANDS);
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 1e9;
        m.trace = false;
        return p;
    }

    private static <T extends Mob> T mob(GameTestHelper h, EntityType<T> type, double x, double z) {
        T e = h.spawn(type, new Vec3(x, 2, z));
        e.setNoAi(true);
        return e;
    }

    private static SpellInstance cast(GameTestHelper h, ServerPlayer p, String src, Vec3 here) {
        SpellInstance inst = Casting.cast(p, Casting.compile(src), null, here);
        h.assertTrue(inst != null && !inst.dead, src + " should start, got " + (inst == null ? "nothing" : inst.endReason()));
        return inst;
    }

    /** A logout as a closing connection brings it about. */
    private static void logOut(GameTestHelper h, ServerPlayer p) {
        h.getLevel().getServer().getPlayerList().remove(p);
    }

    private static boolean hasBody(GameTestHelper h, SpellInstance inst, Vec3 near) {
        return SpellRuntime.spellsNear(h.getLevel(), near, 32).contains(inst);
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<UUID, V> incarnationTable(String name) {
        try {
            var field = Incarnation.class.getDeclaredField(name);
            field.setAccessible(true);
            return (Map<UUID, V>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Clearing the runtime, as the server does when it stops, ends every body, those still waiting to join as well, and
     * forgets every wrap; their spells set nothing free. A wrap wound on afterwards never touches the old spell: its
     * on-expire does not run. Runs in a batch of its own, as it ends every spell on the server.
     */
    static void clearEndsWraps(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var joined = mob(h, EntityType.HUSK, 2.5, 2.5);
        var waiting = mob(h, EntityType.HUSK, 2.5, 6.5);
        String wrap = "(spell :name \"lifecycle-wrap\" :mana 200 (imbue (nearest :radius 2) :life 30 (on-expire (damage self 4 arcane))))";
        Vec3 onJoined = h.absoluteVec(new Vec3(2.5, 2.5, 2.5)), onWaiting = h.absoluteVec(new Vec3(2.5, 2.5, 6.5));
        SpellInstance old = cast(h, p, wrap, onJoined);
        h.assertTrue(WrapObj.on(joined) != null && ManaWrap.of(joined) != null, "the husk's hand should be wrapped");
        h.runAfterDelay(3, () -> {
            SpellInstance late = cast(h, p, wrap, onWaiting);
            double left = old.mana;
            SpellRuntime.clear();
            h.assertTrue(WrapObj.on(joined) == null && ManaWrap.of(joined) == null, "clearing should take the wrap off the hand");
            h.assertTrue(WrapObj.on(waiting) == null && ManaWrap.of(waiting) == null, "clearing should reach a body that has not joined yet");
            h.assertTrue(old.dead && old.objects == 0 && late.dead && late.objects == 0, "their spells should have no bodies left");
            var free = h.getLevel().getEntitiesOfClass(FreeManaEntity.class, new AABB(onJoined, onJoined).inflate(14));
            h.assertTrue(free.isEmpty(), "a cleared spell should set nothing free, found " + free.size());

            SpellInstance next = cast(h, p, wrap, onJoined);
            WrapObj worn = WrapObj.on(joined);
            h.assertTrue(worn != null && !next.dead, "the new wrap should stand, got " + next.endReason());
            h.assertTrue(joined.getHealth() == joined.getMaxHealth(), "the old spell's on-expire should not run, health " + joined.getHealth());
            h.assertTrue(old.mana == left, "the old spell should not be touched, mana " + left + " → " + old.mana);
            worn.disperse();
            h.succeed();
        });
    }

    /** A body pushed over a chunk border leaves no ticket behind: the one from the start goes, whichever chunk it ends in. */
    static void possessTicket(GameTestHelper h) {
        ServerPlayer p = caster(h);
        FamiliarGameTests.forceAround(h, 1);
        ServerLevel level = h.getLevel();
        Vec3 start = p.position();
        var pig = h.spawn(EntityType.PIG, new BlockPos(6, 2, 6));
        h.assertTrue(Possession.start(p, pig, 30), "the possession should start");
        Mannequin body = level.getEntitiesOfClass(Mannequin.class, new AABB(start, start).inflate(1)).getFirst();
        ChunkPos from = body.chunkPosition();
        body.snapTo(body.getX() + 16, body.getY(), body.getZ(), body.getYRot(), 0);
        ChunkPos to = body.chunkPosition();
        h.assertTrue(!to.equals(from), "the body should be in another chunk now");
        Possession.end(p, false);
        // Taking a ticket away answers whether there was one.
        boolean heldFrom = Possession.TICKETS.forceChunk(level, p.getUUID(), from.x(), from.z(), false, true);
        boolean heldTo = Possession.TICKETS.forceChunk(level, p.getUUID(), to.x(), to.z(), false, true);
        h.assertTrue(!heldFrom, "the chunk it started in should not stay forced");
        h.assertTrue(!heldTo, "the chunk it was pushed to was never forced");
        h.succeed();
    }

    /**
     * Logging out extinguishes the player's spells, bodies and all, and no one else's; what is kept by player goes as well.
     */
    static void logoutEndsSpells(GameTestHelper h) {
        ServerPlayer p = caster(h), other = stranger(h);
        Vec3 at = p.position();
        var pig = mob(h, EntityType.PIG, 6.5, 6.5);
        SpellInstance mine = cast(h, p, FIELD, null), theirs = cast(h, other, FIELD, null);
        Lifeforms.opening(p, pig.getId());
        h.assertTrue(Lifeforms.openingEntity(p) == pig, "the player should be opening the pig's panel");
        Map<UUID, Integer> dryAir = incarnationTable("DRY_AIR");
        Map<UUID, Long> nextSkill = incarnationTable("NEXT_SKILL");
        dryAir.put(p.getUUID(), 5);
        nextSkill.put(p.getUUID(), 5L);

        logOut(h, p);

        h.assertTrue(mine.dead && !mine.endedNaturally(), "logging out should extinguish the field, got " + mine.endReason());
        h.assertTrue(!theirs.dead, "another player's field should go on, got " + theirs.endReason());
        h.assertTrue(Lifeforms.openingEntity(p) == null, "what they were opening should be forgotten");
        h.assertTrue(!dryAir.containsKey(p.getUUID()) && !nextSkill.containsKey(p.getUUID()), "what the form kept for them should be forgotten");
        h.succeedWhen(() -> {
            h.assertTrue(mine.objects == 0 && !hasBody(h, mine, at), "the field should be gone");
            h.assertTrue(!theirs.dead && hasBody(h, theirs, at), "the other field should still be there");
        });
    }

    /** A caster removed from the world ends its spells, whatever their form: no form has to look for itself. */
    static void removedCasterEndsSpells(GameTestHelper h) {
        var husk = mob(h, EntityType.HUSK, 4.5, 4.5);
        Vec3 at = husk.position();
        SpellInstance field = Casting.castAs(husk, Casting.compile(FIELD), 200, 0.5, null, null);
        SpellInstance shot = Casting.castAs(husk, Casting.compile("(spell :mana 200 (projectile :speed 0.1 :motion orbit :life 10))"), 200, 0.5, null, null);
        h.assertTrue(!field.dead && !shot.dead, "they should start, got " + field.endReason() + ", " + shot.endReason());
        husk.discard();
        h.succeedWhen(() -> {
            h.assertTrue(field.dead && field.objects == 0 && !hasBody(h, field, at), "the field should end with its caster");
            h.assertTrue(shot.dead && shot.objects == 0 && !hasBody(h, shot, at), "the orbiting shot should end with its caster");
        });
    }

    /**
     * Dead, a player is removed but on the list still, and their spell waits. Respawned, the new body is its caster: it
     * follows them, and goes when they log out.
     */
    static void respawnKeepsSpells(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ServerLevel level = h.getLevel();
        SpellInstance inst = cast(h, p, "(spell :mana 200 (field :follow :life 20))", null);
        p.kill(level);
        h.assertTrue(p.isDeadOrDying(), "the player should have died");
        // Mock players do not tick their death; twenty ticks on, this is what it does.
        p.remove(Entity.RemovalReason.KILLED);
        h.runAfterDelay(5, () -> {
            h.assertTrue(!inst.dead && inst.caster == p, "the spell should wait for the respawn, got " + inst.endReason());
            ServerPlayer back = level.getServer().getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
            h.assertTrue(inst.caster == back, "the new body should be the caster");
            h.assertTrue(back.level() == level, "the test expects to respawn in its own world");
            h.succeedWhen(() -> {
                h.assertTrue(!inst.dead && SpellRuntime.spellsNear(level, back.position(), 2).contains(inst), "the field should follow the new body, got " + inst.endReason());
                logOut(h, back);
                h.assertTrue(inst.dead, "logging out of the new body should extinguish it");
            });
        });
    }

    /** An emitter casts through a stand-in wearing its owner's UUID, which never was in a world: its spells outlast its owner's logout. */
    static void emitterOutlivesOwner(GameTestHelper h) {
        ServerPlayer owner = caster(h);
        ServerLevel level = h.getLevel();
        BlockPos pos = new BlockPos(1, 2, 1);
        BlockState state = LogosMod.SPELL_EMITTER.get().defaultBlockState().setValue(SpellEmitterBlock.FACING, Direction.EAST);
        h.setBlock(pos, state);
        state.getBlock().setPlacedBy(level, h.absolutePos(pos), state, owner, ItemStack.EMPTY);
        var emitter = (SpellEmitterBlockEntity) h.getBlockEntity(pos, SpellEmitterBlockEntity.class);
        var shadow = emitter.caster(level);
        h.assertTrue(shadow.getUUID().equals(owner.getUUID()), "the stand-in should wear its owner's UUID");
        Vec3 centre = h.absolutePos(pos).getCenter();
        SpellInstance inst = Casting.castAs(shadow, Casting.compile(FIELD), 200, 0.5, null, centre);
        logOut(h, owner);
        h.runAfterDelay(5, () -> {
            h.assertTrue(!inst.dead && hasBody(h, inst, centre), "the emitter's field should go on, got " + inst.endReason());
            h.succeed();
        });
    }
}
