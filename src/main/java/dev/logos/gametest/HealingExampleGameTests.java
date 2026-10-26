package dev.logos.gametest;

import dev.logos.lang.Examples;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
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
        ServerPlayer caster = player(h, 1.5);
        var near = patient(h, EntityType.VILLAGER, 4.5, 6.5);
        var distant = patient(h, EntityType.IRON_GOLEM, 4.5, 16.5);
        var outside = patient(h, EntityType.VILLAGER, 4.5, 20.5);
        var spell = Casting.compile(Examples.ALL.get("npc-group-heal"));
        var inst = Casting.cast(caster, spell, null, null);
        h.assertTrue(inst != null && inst.endedNaturally(), "group heal must complete");
        h.assertTrue(near.getHealth() == 10 && distant.getHealth() == 10, "both NPCs within 16 blocks must receive 6 healing");
        h.assertTrue(outside.getHealth() == 4, "NPC outside the radius must not be healed");
        h.assertTrue(caster.getHealth() == 4, "group heal must not heal players");

        var npcCaster = patient(h, EntityType.VILLAGER, 4.5, 1.5);
        var npcCast = Casting.castAs(npcCaster, spell, spell.mana(), 0, null, h.absoluteVec(new Vec3(4.5, 2, 40.5)));
        h.assertTrue(npcCast.endedNaturally() && near.getHealth() == 16 && distant.getHealth() == 16,
                "NPC cast must centre the heal on the caster, even with a different event position");
        h.assertTrue(outside.getHealth() == 4 && caster.getHealth() == 4, "range and player exclusions must hold for NPC casters");
        h.succeed();
    }
}
