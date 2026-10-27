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

/** The 1.5-second disk fires six real rays and pays one upkeep, within its 120 mana budget. */
final class PrismaticBarrageGameTests {
    private PrismaticBarrageGameTests() {}

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
        String source = Examples.ALL.get("prismatic-barrage");
        List<SpellInstance> casts = new ArrayList<>();
        // Fix just the random selection to exercise each element deterministically.
        for (int i = 0; i < 6; i++) {
            var spell = Casting.compile(source.replace("(random 5 :whole)", Integer.toString(i)));
            var inst = Casting.cast(caster, spell, null, null);
            h.assertTrue(inst != null && !inst.dead && inst.objects == 1, "branch " + i + " must create one disk");
            casts.add(inst);
        }
        double[] hitCosts = {15, 14.994, 15, 15, 15, 14.993};
        h.succeedWhen(() -> {
            for (int i = 0; i < casts.size(); i++) {
                var inst = casts.get(i);
                h.assertTrue(inst.finished(), "branch " + i + " is still firing");
                h.assertTrue(inst.endedNaturally(), "branch " + i + " exhausted its mana: " + inst.endReason());
                double expected = 120 - (4 + 0.3 * Math.PI) - (0.2 + 0.02 * Math.PI) - 6 * (0.5 + 3 + hitCosts[i]);
                h.assertTrue(Math.abs(inst.mana - expected) < 1e-6,
                        "branch " + i + " must pay for six hits and one upkeep; mana=" + inst.mana + ", expected=" + expected);
                h.assertTrue(inst.mana > 3.79, "each element must retain the intended reserve");
            }
            h.assertTrue(target.getHealth() < target.getMaxHealth(), "the rays must deal real damage");
        });
    }
}
