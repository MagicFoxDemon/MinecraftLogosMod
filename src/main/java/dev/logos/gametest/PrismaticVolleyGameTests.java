package dev.logos.gametest;

import java.util.ArrayList;
import java.util.List;

import dev.logos.lang.Examples;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** All six real on-hit branches must share the launch budget without starving another missile. */
final class PrismaticVolleyGameTests {
    private PrismaticVolleyGameTests() {}

    static void budget(GameTestHelper h) {
        FamiliarGameTests.forceAround(h, 1);
        @SuppressWarnings("removal")
        var caster = h.makeMockServerPlayerInLevel();
        Vec3 at = h.absoluteVec(new Vec3(4.5, 4, 1.5));
        caster.snapTo(at.x, at.y, at.z, 0, 0);
        caster.setYHeadRot(0);
        PlayerMagic.of(caster).maxMana = PlayerMagic.of(caster).mana = 10000;
        PlayerMagic.of(caster).trace = false;
        var target = h.spawn(EntityType.IRON_GOLEM, new Vec3(4.5, 4, 8.5));
        target.setNoAi(true);
        target.setNoGravity(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10000);
        target.setHealth(10000);
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        String source = Examples.ALL.get("prismatic-volley");
        List<SpellInstance> casts = new ArrayList<>();
        // Fix only the random choice, so every element is exercised without relying on lucky rolls.
        for (int i = 0; i < 6; i++) {
            var spell = Casting.compile(source.replace("(random 5 :whole)", Integer.toString(i)));
            var inst = Casting.cast(caster, spell, null, null);
            h.assertTrue(inst != null && !inst.dead && inst.objects == 3, "branch " + i + " must launch exactly three missiles");
            h.assertTrue(Math.abs(inst.mana - 21.5) < 1e-6, "branch " + i + " must pay only the chosen launch path: " + inst.mana);
            casts.add(inst);
        }
        h.succeedWhen(() -> {
            for (int i = 0; i < casts.size(); i++) {
                var inst = casts.get(i);
                h.assertTrue(inst.finished(), "branch " + i + " is still flying");
                h.assertTrue(inst.endedNaturally(), "branch " + i + " exhausted mana: " + inst.endReason());
                h.assertTrue(Math.abs(inst.mana) < 1e-6, "branch " + i + " must spend its full damage budget: " + inst.mana);
            }
            h.assertTrue(target.getHealth() < target.getMaxHealth(), "the homing missiles must deal real damage");
        });
    }
}
