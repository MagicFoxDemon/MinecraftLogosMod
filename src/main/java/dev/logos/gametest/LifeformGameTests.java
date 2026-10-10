package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.item.SpellPages;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.ManaLifeform;
import dev.logos.runtime.SpellInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** The mana lifeform: summoning by level and mana, the per-player limit, its attack page, going dormant and being fed. */
final class LifeformGameTests {
    private LifeformGameTests() {}

    private static List<ManaLifeform> lifeforms(ServerPlayer p) {
        return Familiars.of(p).stream().filter(m -> m instanceof ManaLifeform).map(m -> (ManaLifeform) m).toList();
    }

    private static ManaLifeform summon(GameTestHelper h, ServerPlayer p, String src) {
        SpellInstance inst = Casting.cast(p, Casting.compile(src), null, null);
        h.assertTrue(inst != null && !inst.dead, "summon failed: " + (inst == null ? "refused" : inst.endReason()));
        ManaLifeform lf = (ManaLifeform) Familiars.latest(p, null);
        h.assertTrue(lf != null, "no lifeform after summoning");
        return lf;
    }

    private static ItemStack page(String src) {
        return SpellPages.write(new ItemStack(LogosMod.SPELL_PAGE.get()), src, null);
    }

    /**
     * Below 50 × level^1.5 the summoning fails; above, everything the spell has left (up to the pool)
     * becomes its mana, health is 30 + 20 × level; a second one past the limit of one unmakes the first.
     */
    static void summonRules(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        SpellInstance poor = Casting.cast(p, Casting.compile("(spell :mana 2000 (summon mana_lifeform :level 2 :mana 100 :at (pos-of caster)))"), null, null);
        h.assertTrue(poor != null && poor.dead && lifeforms(p).isEmpty(), "100 mana is below a level-2 lifeform's 141: the spell should fail");

        ManaLifeform first = summon(h, p, "(spell :mana 500 (summon mana_lifeform :level 2 :at (pos-of caster) :name \"灵\"))");
        h.assertTrue(first.lifeLevel() == 2, "level 2 expected, got " + first.lifeLevel());
        h.assertTrue(first.mana() > 400 && first.mana() <= 500, "it should take what the spell had left, got " + first.mana());
        h.assertTrue(first.getMaxHealth() == 70 && first.getHealth() == 70, "level 2: 30 + 20 × 2 = 70 health, got " + first.getMaxHealth());
        h.assertTrue(Math.abs(first.maxMana() - 1000 * Math.pow(2, 1.5)) < 1e-6, "its pool is 1000 × 2^1.5");

        ManaLifeform second = summon(h, p, "(spell :mana 200 (summon mana_lifeform :at (pos-of caster)))");
        h.assertTrue(first.isRemoved() && !second.isRemoved() && lifeforms(p).size() == 1, "with a limit of one, the new lifeform unmakes the old");
        h.assertTrue(second.lifeLevel() == 1, "no :level means level 1");
        h.succeed();
    }

    /** Ordered onto a husk, it casts its attack page at it, paying from its own pool; the core takes only the right pages. */
    static void castsAttackPage(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        ManaLifeform lf = summon(h, p, "(spell :mana 300 (summon mana_lifeform :at (pos-of caster)))");
        ItemStack attack = page("(spell :name \"魔弹\" :mana 20 (damage (foe) 4))");
        ItemStack passive = page("(spell :name \"护主\" :trigger hurt :mana 10 (heal (master) 2))");
        h.assertTrue(!lf.core.accepts(0, passive) && lf.core.accepts(0, attack), "the attack slot takes active pages only");
        h.assertTrue(!lf.core.accepts(1, passive), "a level-1 lifeform has no passive slot");
        lf.core.setItem(0, attack);
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(6.5, 2, 6.5));
        husk.setNoAi(true);
        double before = lf.mana();
        h.assertTrue(Familiars.order(lf, husk), "it should take the order");
        h.succeedWhen(() -> {
            h.assertTrue(husk.getHealth() < husk.getMaxHealth(), "its attack page should have hurt the husk");
            h.assertTrue(lf.mana() < before, "and the cast should be paid from its own mana");
        });
    }

    /** Run dry it goes dormant and falls; fed amethyst past its minimum (50 at level 1) it wakes. */
    static void dormantAndFed(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        FamiliarGameTests.forceAround(h, 1);
        ManaLifeform lf = summon(h, p, "(spell :mana 100 (summon mana_lifeform :at (pos-of caster)))");
        lf.setMana(0.01);
        p.setGameMode(GameType.SURVIVAL);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.AMETHYST_SHARD, 3));
        boolean[] fed = {false};
        h.succeedWhen(() -> {
            if (!fed[0]) {
                h.assertTrue(lf.isDormant(), "out of mana it should go dormant");
                h.assertTrue(!lf.isNoGravity(), "and fall");
                lf.interact(p, InteractionHand.MAIN_HAND, lf.position());
                h.assertTrue(lf.isDormant(), "25 mana is below its minimum of 50: still dormant");
                lf.interact(p, InteractionHand.MAIN_HAND, lf.position());
                h.assertTrue(!lf.isDormant() && lf.isNoGravity(), "fed to 50 it should wake and rise");
                h.assertTrue(p.getMainHandItem().getCount() == 1, "each feeding uses one shard");
                fed[0] = true;
            }
        });
    }
}
