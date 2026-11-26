package dev.logos.gametest;

import dev.logos.lang.Element;
import dev.logos.lang.Rune;
import dev.logos.lang.Runes;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.HostedSpells;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 寄托形体: a spell written around another mod's form is carried by its thing (a round, an arrow), which sets off the
 * events where it strikes ({@link HostedSpells}). Cast on its own the form only ends the spell.
 */
final class HostedSpellGameTests {
    private HostedSpellGameTests() {}

    static final String WORD = "gametest-shell";

    static {
        if (Runes.get(WORD) == null) {
            Runes.hosted(WORD, "世界内测试用的寄托形体。",
                    new Rune.EventSpec("on-hit", true, "命中生物时"),
                    new Rune.EventSpec("on-expire", false, "什么都没打中时"));
        }
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(1.5, 2, 1.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.baseMaxMana = m.mana = 1e9;
        m.trace = false;
        return p;
    }

    /** A target with no armour and plenty of health, so each hit shows in full. */
    private static IronGolem golem(GameTestHelper h) {
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) h.setBlock(x, 1, z, Blocks.STONE);
        IronGolem golem = EntityType.IRON_GOLEM.create(h.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 at = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        golem.setNoAi(true);
        golem.snapTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(golem);
        return golem;
    }

    /** Its events go off where the host says, on the target it struck, paid from the one pool, until the host is done. */
    static void firesOnItsHost(GameTestHelper h) {
        ServerPlayer p = player(h);
        IronGolem golem = golem(h);
        double cost = 6 * Element.PHYSICAL.costFactor;
        Spell spell = Casting.compile("(spell :name \"测试弹\" :mana 30 (" + WORD + " (on-hit (damage target 6))))");
        h.assertTrue(HostedSpells.form(spell) != null, "the spell is written around the hosted form");
        double before = PlayerMagic.of(p).mana;
        var carried = HostedSpells.carry(spell, p, 2.5 * cost, 0.1);
        h.assertTrue(carried.word().equals(WORD), "it carries " + WORD);
        float full = golem.getHealth();
        h.assertTrue(carried.fire("on-hit", h.getLevel(), golem.position(), new Vec3(0, 0, 1), golem), "still going after one hit");
        h.assertTrue(full - golem.getHealth() >= 5.9f, "on-hit dealt 6 to the target it was given, dealt " + (full - golem.getHealth()));
        SpellInstance inst = carried.instance();
        h.assertTrue(Math.abs(inst.mana - 1.5 * cost) < 1e-6, "paid from its own pool, left " + inst.mana);
        h.assertTrue(PlayerMagic.of(p).mana == before, "the shooter's own pool is untouched");
        // An event it does not write does nothing; one it cannot pay for puts it out.
        carried.fire("on-expire", h.getLevel(), golem.position(), new Vec3(0, 0, 1), null);
        h.assertTrue(Math.abs(inst.mana - 1.5 * cost) < 1e-6, "an unwritten event costs nothing");
        h.assertTrue(carried.fire("on-hit", h.getLevel(), golem.position(), new Vec3(0, 0, 1), golem), "a second hit is paid for");
        h.assertTrue(!carried.fire("on-hit", h.getLevel(), golem.position(), new Vec3(0, 0, 1), golem) && inst.dead, "the third hit finds the pool dry");
        carried.end();
        h.assertTrue(inst.endReason() != null, "ended");
        h.succeed();
    }

    /** The host ends it: then nothing more goes off, and what it did not spend goes free. */
    static void endsWhenTheHostIsDone(GameTestHelper h) {
        ServerPlayer p = player(h);
        IronGolem golem = golem(h);
        Spell spell = Casting.compile("(spell :mana 30 (" + WORD + " (on-hit (damage target 6))))");
        var carried = HostedSpells.carry(spell, p, 30, 0.1);
        SpellInstance inst = carried.instance();
        h.assertTrue(inst.endReason() == null, "going until the host is done");
        carried.end();
        h.assertTrue(inst.endReason() != null && !inst.dead, "ended on its own, not put out");
        float full = golem.getHealth();
        h.assertTrue(!carried.fire("on-hit", h.getLevel(), golem.position(), new Vec3(0, 0, 1), golem) && golem.getHealth() == full,
                "nothing goes off after the end");
        h.succeed();
    }

    /** Cast by hand from a page, the form has nothing to carry it: the spell ends at once and its events never run. */
    static void castByHandEnds(GameTestHelper h) {
        ServerPlayer p = player(h);
        IronGolem golem = golem(h);
        float full = golem.getHealth();
        SpellInstance inst = Casting.cast(p, Casting.compile("(spell :mana 30 (" + WORD + " (on-hit (damage target 6))) (damage (foe) 1))"),
                golem, golem.position());
        h.assertTrue(inst != null && inst.dead, "the spell is put out");
        h.assertTrue(golem.getHealth() == full, "nothing after it ran either");
        h.assertTrue(inst.endReason() != null && !inst.endReason().isEmpty(), "the caster is told why");
        h.assertTrue(HostedSpells.form(inst.spell) == null, "two statements: not a spell a host can carry");
        h.succeed();
    }
}
