package dev.logos.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import dev.logos.lang.Examples;
import dev.logos.lang.Intent;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Every attack example of the first two course groups, cast once by a player at a husk in front of them, hurts it
 * and ends without running out of mana: a player trying an example should see it work. Grand spells reach too far
 * for a test area and are left out.
 */
final class ExampleHitGameTests {
    /** How far ahead the husk stands for examples that only reach that close; the rest have it 6 blocks away. */
    private static final Map<String, Double> DISTANCE = Map.of("frost-nova", 3.0, "blood-aura", 2.5, "orbit", 2.5);
    /** Examples that strike where the gaze meets the ground, so the caster looks at the husk's feet. */
    private static final Set<String> AT_GROUND = Set.of("meteor");

    private ExampleHitGameTests() {}

    /** Registers one test per example of the basic and advanced groups; returns their names. */
    static List<String> register(TestFunctions functions) {
        List<String> names = new ArrayList<>();
        for (String id : Examples.ALL.keySet()) {
            String group = Examples.group(id);
            if (!group.equals(Examples.BASICS) && !group.equals(Examples.ADVANCED)) continue;
            String name = "example_hits_" + id.replace('-', '_');
            functions.register(name, () -> h -> hits(h, id));
            names.add(name);
        }
        return names;
    }

    private static void hits(GameTestHelper h, String id) {
        Spell spell = Casting.compile(Examples.ALL.get(id));
        // Healing and movement examples have nothing to hurt; theirs are other tests.
        if (spell.profile().primary() == Intent.HEAL || spell.profile().primary() == Intent.MOVE) {
            h.succeed();
            return;
        }
        for (int x = 0; x < 9; x++) for (int z = 0; z < 20; z++) h.setBlock(x, 1, z, Blocks.STONE);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(4.5, 2, 1.5));
        p.moveTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.baseMaxMana = m.mana = 10000;
        m.trace = false;
        var husk = h.spawn(EntityType.HUSK, new Vec3(4.5, 2, 1.5 + DISTANCE.getOrDefault(id, 6.0)));
        husk.setNoAi(true);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, AT_GROUND.contains(id) ? husk.position() : husk.position().add(0, husk.getBbHeight() / 2, 0));
        p.setYHeadRot(p.getYRot());
        SpellInstance inst = Casting.cast(p, spell, null, null);
        h.assertTrue(inst != null, id + " was refused");
        h.succeedWhen(() -> {
            h.assertTrue(husk.getHealth() < husk.getMaxHealth() || !husk.isAlive(), id + " did not hurt the husk in front of its caster");
            h.assertTrue(inst.finished(), id + " is still running");
            h.assertTrue(!"logos.spell.end.out_of_mana".equals(inst.endKey()), id + " ran out of mana: " + inst.endReason());
        });
    }
}
