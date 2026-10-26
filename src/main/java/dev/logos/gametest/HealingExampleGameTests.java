package dev.logos.gametest;

import dev.logos.lang.Examples;
import dev.logos.lang.Status;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.StatusData;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Real example pages heal non-player creatures, including when an NPC casts them. */
final class HealingExampleGameTests {
    private HealingExampleGameTests() {}

    private static ServerPlayer player(GameTestHelper h, double z) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 at = h.absoluteVec(new Vec3(4.5, 2, z));
        p.snapTo(at.x, at.y, at.z, 0, 0);
        p.setYHeadRot(0);
        p.setHealth(4);
        PlayerMagic.of(p).maxMana = PlayerMagic.of(p).mana = 10000;
        PlayerMagic.of(p).trace = false;
        return p;
    }

    private static <T extends Mob> T patient(GameTestHelper h, EntityType<T> type, double x, double z) {
        T mob = h.spawn(type, new Vec3(x, 2, z));
        mob.setNoAi(true);
        mob.setHealth(4);
        return mob;
    }

    static void single(GameTestHelper h) {
        ServerPlayer caster = player(h, 1.5);
        ServerPlayer bystander = player(h, 3.5);
        var patient = patient(h, EntityType.VILLAGER, 4.5, 6.5);
        var behind = patient(h, EntityType.VILLAGER, 4.5, 9.5);
        var spell = Casting.compile(Examples.ALL.get("npc-heal"));
        var inst = Casting.cast(caster, spell, null, null);
        h.assertTrue(inst != null && inst.endedNaturally(), "single heal must complete");
        h.assertTrue(patient.getHealth() == 14, "the first NPC must receive exactly 10 healing");
        h.assertTrue(behind.getHealth() == 4, "single heal must not pierce to the next NPC");
        h.assertTrue(caster.getHealth() == 4 && bystander.getHealth() == 4, "players must not be healed");

        // The same source works through the NPC casting entry point and respects maximum health.
        var npcCaster = patient(h, EntityType.VILLAGER, 4.5, 1.5);
        npcCaster.setYRot(0);
        npcCaster.setXRot(0);
        var npcCast = Casting.castAs(npcCaster, spell, spell.mana(), 0, null, null);
        h.assertTrue(npcCast.endedNaturally() && patient.getHealth() == patient.getMaxHealth(), "NPC cast must heal up to maximum health");
        h.assertTrue(behind.getHealth() == 4, "NPC cast must still affect only one target");
        h.succeed();
    }

    static void group(GameTestHelper h) {
        ServerPlayer bystander = player(h, 3.5);
        var caster = patient(h, EntityType.VILLAGER, 4.5, 1.5);
        var near = patient(h, EntityType.VILLAGER, 4.5, 6.5);
        var distant = patient(h, EntityType.VILLAGER, 4.5, 16.5);
        var outside = patient(h, EntityType.VILLAGER, 4.5, 20.5);
        var stranger = patient(h, EntityType.IRON_GOLEM, 6.5, 6.5);
        var enemy = patient(h, EntityType.HUSK, 2.5, 6.5);
        var spell = Casting.compile(Examples.ALL.get("npc-group-heal"));
        var inst = Casting.castAs(caster, spell, spell.mana(), 0, null, h.absoluteVec(new Vec3(4.5, 2, 40.5)));
        h.assertTrue(inst.endedNaturally(), "group heal must complete with 100 mana");
        h.assertTrue(near.getHealth() == 10 && distant.getHealth() == 10,
                "both same-species NPCs within 16 blocks of the caster must receive 6 healing");
        h.assertTrue(outside.getHealth() == 4, "same-species NPC outside the radius must not be healed");
        h.assertTrue(stranger.getHealth() == 4 && enemy.getHealth() == 4, "other species, including enemies, must not be healed");
        h.assertTrue(bystander.getHealth() == 4 && caster.getHealth() == 4, "the selector must still exclude players and the caster");
        h.assertTrue(Math.abs(inst.mana - 80) < 1e-6, "only selection and two heals should spend mana: " + inst.mana);
        h.succeed();
    }

    /** Species follows an event target, supports modded types, and a missing entity never widens a filter. */
    static void species(GameTestHelper h) {
        ServerPlayer caster = player(h, 1.5);
        var target = patient(h, dev.logos.LogosMod.MANA_LIFEFORM.get(), 4.5, 4.5);
        var kin = patient(h, dev.logos.LogosMod.MANA_LIFEFORM.get(), 6.5, 4.5);
        var stranger = patient(h, EntityType.VILLAGER, 2.5, 4.5);
        var spell = Casting.compile("(spell :trigger attack :mana 20 (mark (entities :radius 8 :filter (kind (species target))) #kin))");
        var inst = Casting.cast(caster, spell, target, null);
        h.assertTrue(inst != null && inst.endedNaturally(), "target species filter must run");
        var mark = new Status("#kin");
        h.assertTrue(StatusData.has(target, mark) && StatusData.has(kin, mark), "modded entities must match their exact registry type");
        h.assertTrue(!StatusData.has(stranger, mark), "another species must not match");

        var empty = Casting.compile("(spell :mana 20 (mark (entities :radius 8 :filter (kind (species (foe)))) #missing))");
        var emptyCast = Casting.cast(caster, empty, null, null);
        h.assertTrue(emptyCast != null && emptyCast.endedNaturally(), "an absent species must be safe to filter");
        var missing = new Status("#missing");
        h.assertTrue(!StatusData.has(target, missing) && !StatusData.has(kin, missing) && !StatusData.has(stranger, missing),
                "an absent species must select nobody");
        h.succeed();
    }
}
