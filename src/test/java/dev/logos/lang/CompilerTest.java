package dev.logos.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CompilerTest {
    @Test
    void equipRequiresItemAndEntityTypes() {
        Spell single = Compiler.compile("(spell :mana 20 (equip caster &minecraft:stone))");
        assertEquals(8, single.profile().estimate(), 1e-6);
        assertEquals(Intent.UTILITY, single.profile().primary());
        Compiler.compile("(spell :mana 100 (equip (enemies) &minecraft:iron_sword))");
        assertThrows(LogosError.class, () -> Compiler.compile("(spell (equip caster))"));
        assertThrows(LogosError.class, () -> Compiler.compile("(spell (equip here &minecraft:stone))"));
        assertThrows(LogosError.class, () -> Compiler.compile("(spell (equip caster \"minecraft:stone\"))"));
        assertThrows(LogosError.class, () -> Compiler.compile("(spell (equip caster %flame))"));
    }

    @Test
    void allExamplesCompile() {
        for (Map.Entry<String, String> e : Examples.ALL.entrySet()) {
            try {
                Spell s = Compiler.compile(e.getValue());
                System.out.printf("%-16s %-10s mana=%-5s est=%-6s intents=%-14s range=%-4s stability=%-6s notes=%s%n", e.getKey(), s.trigger(), Node.fmt(s.mana()),
                        Node.fmt(s.profile().estimate()), s.profile().intents(), Math.round(s.profile().range()), Node.fmt(s.stability()), s.notes());
                assertTrue(s.stability() <= 80, e.getKey() + " exceeds default stability: " + s.stability());
            } catch (LogosError err) {
                throw new AssertionError(e.getKey() + ": " + err.describe(), err);
            }
        }
    }

    /** Intents are read off the runes unless declared; the estimate prices the runes that run. */
    /** offset takes forward, then optional up and right, all numbers, and is a position. */
    @Test
    void offsetIsAPosition() {
        Compiler.compile("(spell :mana 20 (infuse-block (offset 3 1 -2) 5))");
        Compiler.compile("(spell :mana 20 (infuse-block (offset 3) 5))");
        Compiler.compile("(spell :mana 20 (infuse-block (offset 3 0 0 :from (pos-of caster)) 5))");
        assertThrows(LogosError.class, () -> Compiler.compile("(spell :mana 20 (damage (offset 3 1 0) 5))"));
        assertThrows(LogosError.class, () -> Compiler.compile("(spell :mana 20 (infuse-block (offset caster) 5))"));
    }

    @Test
    void intentProfile() {
        assertEquals(Intent.STRIKE, Compiler.compile("(spell :mana 20 (damage (foe) 6))").profile().primary());
        assertEquals(Intent.HEAL, Compiler.compile("(spell :trigger hurt :mana 20 (heal (master) 4))").profile().primary());
        assertEquals(Intent.AREA, Compiler.compile("(spell :mana 40 (damage-all (enemies) 5 fire))").profile().primary());
        Spell declared = Compiler.compile("(spell :intent ward+heal :mana 20 (heal (master) 4))");
        assertEquals(List.of(Intent.WARD, Intent.HEAL), declared.profile().intents());
        assertTrue(declared.profile().declared());
        // heal 4 = 6 mana; damage 6 physical = 4.8
        assertEquals(6, Compiler.compile("(spell :mana 20 (heal (master) 4))").profile().estimate(), 1e-6);
        assertEquals(4.8, Compiler.compile("(spell :mana 20 (damage (foe) 6))").profile().estimate(), 1e-6);
        assertThrows(LogosError.class, () -> Compiler.compile("(spell :intent dance (damage (foe) 6))"));
    }

    @Test
    void groupEstimateGrowsWithRadius() {
        // enemies 2 + 1 physical (0.8) × 3 × max(1, (r/5)^1.5); past 64 counts as 64
        assertEquals(4.4, Compiler.compile("(spell :mana 200 (damage-all (enemies) 1))").profile().estimate(), 1e-6);
        assertEquals(21.2, Compiler.compile("(spell :mana 200 (damage-all (enemies :radius 20) 1))").profile().estimate(), 1e-6);
        assertEquals(2 + 2.4 * Math.pow(64 / 5.0, 1.5),
                Compiler.compile("(spell :mana 200 (damage-all (enemies :radius 300) 1))").profile().estimate(), 0.05); // shown to one decimal
    }

    @Test
    void elementInferredForVisuals() {
        Spell s = Compiler.compile(Examples.ALL.get("fireball"));
        assertEquals(Element.FIRE, s.body().getFirst().element);
    }

    @Test
    void proofIsAttachedToExecute() {
        Spell s = Compiler.compile(Examples.ALL.get("sever"));
        Node ray = s.body().getFirst();
        Node ifNode = ray.events.get("on-hit").getFirst();
        Node exec = ifNode.arg(1);
        assertEquals("execute", exec.rune.name);
        @SuppressWarnings("unchecked")
        List<Fact> proof = (List<Fact>) exec.meta;
        assertEquals(4, proof.size());
    }

    @Test
    void factsDoNotLeakIntoRebindingScopes() {
        Spell s = Compiler.compile("""
                (spell :trigger attack
                  (if (< (hp-ratio target) 0.2)
                    (each (enemies) (execute target))))""");
        Node each = s.body().getFirst().arg(1);
        Node exec = each.body.getFirst();
        assertTrue(((List<?>) exec.meta).isEmpty(), "target was rebound by each; old proof must not apply");
    }

    @Test
    void trivialConditionsGiveNoDiscount() {
        Spell s = Compiler.compile("(ray (on-hit (if (> (hp target) 0) (execute target))))");
        Node exec = s.body().getFirst().events.get("on-hit").getFirst().arg(1);
        assertTrue(((List<?>) exec.meta).isEmpty());
    }

    @Test
    void rejectsIllTypedPrograms() {
        assertError("(damage here 5)", "插槽形状不符");
        assertError("(damage target 5)", "没有 target");
        assertError("(projectile (on-hit (damage target fire)))", "插槽形状不符");
        assertError("(projectile :speed)", "缺少值");
        assertError("(projectil)", "projectile");
        assertError("(hp caster)", "需要一个");
        assertError("(mark caster burning)", "自定义印记");
        assertError("(repeat 100 (heal caster 1))", "最多");
        assertError("(on-hit (heal caster 1))", "只能写在");
        assertError("(projectile (on-hit (damage target 1))", "括号未闭合");
        assertError("(let $x 3 (heal caster $y))", "尚未定义");
    }

    @Test
    void manaInjectionGoesUpToTheLargestInteger() {
        assertEquals(Integer.MAX_VALUE, Compiler.compile("(spell :mana 2147483647 (heal caster 1))").mana());
        assertError("(spell :mana 2147483648 (heal caster 1))", "2147483647");
        assertError("(spell :mana 0 (heal caster 1))", ":mana");
    }

    @Test
    void playersNeedThePlayersSwitch() {
        assertError("(heal (players :radius 8) 2)", ":players true");
        Spell s = Compiler.compile("(spell :players true (heal (players :radius 8) 2))");
        assertTrue(s.affectsPlayers());
        assertFalse(Compiler.compile("(heal caster 1)").affectsPlayers());
        // nearest-player is no error without the switch: it just finds nobody.
        assertFalse(Compiler.compile("(heal (nearest-player :radius 8 :fresh) 2)").affectsPlayers());
        assertEquals(Type.ENTITY, Compiler.compile("(spell :players true (heal (nearest-player) 2))").body().getFirst().arg(0).type);
    }

    @Test
    void blockSetsFlowIntoBlockEffects() {
        Compiler.compile("(toggle-state (blocks :radius 2 :filter \"#minecraft:logs\"))");
        Compiler.compile("(grow (look-block) 2)");
        Compiler.compile("(each-block (blocks :radius 1) (set-fire here))");
        assertError("(toggle-state (enemies :radius 2))", "◇位置 或 ▦方块群");
        assertError("(blink (blocks :radius 2))", "插槽形状不符");
    }

    @Test
    void dimensionsAreLiterals() {
        Spell s = Compiler.compile("(blink (in-dimension @the_nether here))");
        Node dim = s.body().getFirst().arg(0).arg(0);
        assertEquals(Type.DIMENSION, dim.type);
        assertEquals("minecraft:the_nether", dim.value);
        assertError("(in-dimension here here)", "插槽形状不符");
    }

    @Test
    void looksAreCosmeticItemLiterals() {
        Node p = Compiler.compile("(projectile :look &mace :spin 2)").body().getFirst();
        Node look = p.kw.get("look");
        assertEquals(Type.LOOK, look.type);
        assertEquals("minecraft:mace", look.value);
        assertEquals("minecraft:fire_charge", Compiler.compile("(projectile :look &minecraft:fire_charge)").body().getFirst().kw.get("look").value);
        // Appearance never adds stability: same program with and without a look.
        assertEquals(Compiler.compile("(projectile)").stability(), Compiler.compile("(projectile :look &trident :spin 1)").stability());
        assertFalse(Compiler.compile("(projectile)").body().getFirst().kw.containsKey("look"));
        assertError("(projectile :look &Bad_Id)", "不是物品 id");
        assertError("(projectile :look fire)", "插槽形状不符");
        assertError("(damage &mace 5)", "插槽形状不符");
    }

    @Test
    void looksMustNameRealItems() {
        var saved = Compiler.itemExists;
        try {
            Compiler.itemExists = id -> id.equals("minecraft:mace");
            Compiler.compile("(projectile :look &mace)");
            assertError("(projectile :look &no_such_thing)", "没有叫 minecraft:no_such_thing 的物品");
        } finally {
            Compiler.itemExists = saved;
        }
    }

    @Test
    void particlesAreCosmeticLiterals() {
        Node p = Compiler.compile("(projectile :trail %portal)").body().getFirst();
        assertEquals(Type.PARTICLE, p.kw.get("trail").type);
        assertEquals("minecraft:portal", p.kw.get("trail").value);
        assertEquals(Compiler.compile("(projectile)").stability(), Compiler.compile("(projectile :trail %end_rod)").stability());

        Node ph = Compiler.compile("(phantasm %minecraft:gust_emitter_large (above (pos-of caster) 2) :count 3 :spread 1)").body().getFirst();
        assertEquals("phantasm", ph.rune.name);
        assertEquals("minecraft:gust_emitter_large", ph.arg(0).value);
        assertEquals(0, Compiler.compile("(phantasm %portal)").stability());
        assertError("(phantasm)", "phantasm");
        assertError("(phantasm &mace)", "插槽形状不符");
        assertError("(projectile :trail &mace)", "插槽形状不符");
        assertError("(projectile :look %portal)", "插槽形状不符");
        assertError("(phantasm %Portal)", "不是注册名");

        var saved = Compiler.particleExists;
        try {
            Compiler.particleExists = id -> !id.equals("minecraft:dust");
            assertError("(phantasm %dust)", "额外参数");
        } finally {
            Compiler.particleExists = saved;
        }
    }

    @Test
    void filtersAcceptStringsKindsAndCombinations() {
        Compiler.compile("(glow (enemies :filter \"#minecraft:undead\") 1)");
        Node k = Compiler.compile("(push (entities :filter (kind projectiles)) 1)").body().getFirst().arg(0).kw.get("filter");
        assertEquals("kind", k.rune.name);
        assertEquals(TargetKind.PROJECTILES, k.arg(0).value);
        assertEquals("(kind not-caster)", k.show().replace("projectiles", "not-caster"));
        Compiler.compile("(glow (enemies :filter (except (kind enemies) (any-of \"minecraft:zombie\" \"#minecraft:skeletons\"))) 1)");
        Compiler.compile("(break-block (blocks :filter (all-of (kind all) \"#minecraft:logs\")))");
        // Defaults to every kind of entity.
        assertEquals("kind", Compiler.compile("(glow (entities) 1)").body().getFirst().arg(0).kw.get("filter").rune.name);

        assertError("(enemies :filter (kind zombies))", "kind 需要一个种类");
        assertError("(enemies :filter 3)", "插槽形状不符");
        assertError("(damage (kind enemies) 3)", "插槽形状不符");
        assertError("(blocks :filter (kind enemies))", "方块筛选不能用实体种类");
        assertError("(if (block-is here (except (kind all) (kind items))) (heal caster 1))", "方块筛选不能用实体种类");
        assertError("(glow (entities :filter (kind players)) 1)", ":players true");
        Compiler.compile("(spell :players true (glow (entities :filter (kind players)) 1))");
    }

    @Test
    void constructsTakeShapesAndFilters() {
        Node c = Compiler.compile("(construct :shape dome :radius 4 :solid (except (kind all) (kind not-caster)) :one-way)").body().getFirst();
        assertEquals(ConstructShape.DOME, c.kw.get("shape").value);
        assertTrue(c.flags.contains("one-way"));
        assertEquals(ConstructShape.WALL, Compiler.compile("(construct)").body().getFirst().kw.get("shape").value);
        Compiler.compile("(construct :shape plane :snap :look &glass (on-stand (launch target 1)) (on-block (glow target 1)))");
        Compiler.compile("(construct :solid (kind none) (on-pass (if (is target (kind projectiles)) (do (ignite target 2) (copy target 2 :spread 5)))))");
        assertError("(copy here 2)", "插槽形状不符");
        // ⚓ anchors: ^name fits any position slot; anchor/unanchor take the name itself.
        Node blink = Compiler.compile("(blink ^家)").body().getFirst();
        assertEquals(Type.ANCHOR, blink.arg(0).type);
        assertEquals("家", blink.arg(0).value);
        Compiler.compile("(do (anchor ^二楼 (above here 5)) (unanchor ^旧) (construct :at ^城门))");
        assertError("(anchor (pos-of caster) here)", "插槽形状不符");
        assertError("(let $a ^家 (anchor $a here))", "直接写成 ^名字");
        assertError("(damage ^家 3)", "插槽形状不符");
        assertError("(blink ^)", "锚点名");
        // The one moved defaults to the caster; a context target or a group may take its place.
        assertEquals("caster", Compiler.compile("(blink ^家)").body().getFirst().arg(1).show());
        Compiler.compile("(ray (on-hit (blink (pos-of caster) target)))");
        assertEquals(Type.ENTITIES, Compiler.compile("(blink here (enemies))").body().getFirst().arg(1).type);
        // ❦ summon: creature names are written bare.
        assertEquals("minecraft:zombie", Compiler.compile("(summon zombie)").body().getFirst().arg(0).value);
        Compiler.compile("(do (summon minecraft:wither :at (above here 3)) (dismiss (entities :filter (kind living))))");
        assertError("(summon)", "summon");
        assertError("(summon (pos-of caster))", "生物名");
        Compiler.compile("(do (sic (nearest)) (sic (nearest) :familiar (familiar \"小黑\")))");
        Compiler.compile("(do (summon zombie :name \"小黑\") (gather) (gather (familiar \"小黑\") :to ^家) (dismiss) (dismiss (familiar \"小黑\")))");
        assertError("(move self here)", "这里没有 self");
        assertError("(projectile (on-hit (move self here)))", "这里没有 self");
        Compiler.compile("(construct :follow :to (above here 3) (on-arrive (halt self)) (on-stand (move self (pos-of caster) :speed 0.1)))");
        assertError("(if (is here (kind all)) (heal caster 1))", "插槽形状不符");
        assertError("(construct :shape fire)", "插槽形状不符");
        assertError("(construct :shape cube)", "未知的词");
        assertError("(construct (on-tick (glow target 1)))", "没有 target");
    }

    @Test
    void broadcastSlotsAcceptEntitySets() {
        Compiler.compile("(damage (enemies :radius 4) 3 fire)");
        Compiler.compile("(each caster (heal target 1))");
    }

    @Test
    void disarmAndPotionCompile() {
        Compiler.compile("(disarm (enemies :radius 5))");
        Compiler.compile("(potion caster speed 10 2)");
        Compiler.compile("(potion (enemies) minecraft:slowness 5)");
        assertError("(potion caster speed)", "seconds");
        assertError("(disarm here)", "插槽形状不符");
    }

    @Test
    void immolationSwitchDefaultsOn() {
        assertTrue(Compiler.compile("(spell :mana 80 (heal caster 1))").immolate());
        assertFalse(Compiler.compile("(spell :mana 80 :immolate false (heal caster 1))").immolate());
        assertError("(spell :immolate maybe (heal caster 1))", ":immolate 需要 true 或 false");
    }

    @Test
    void groupEffectsTakeOneOrManyEntities() {
        Compiler.compile("(damage-all (enemies :radius 6) 4 frost (on-kill (ignite target 2)))");
        Compiler.compile("(damage-all caster 1)");
        Compiler.compile("(ignite-all (entities :filter (kind projectiles)) 3)");
        Compiler.compile("(freeze-all (enemies) 2)");
        assertError("(damage-all here 5)", "插槽形状不符");
    }

    @Test
    void timeStopIsControlPricedPerSecond() {
        Spell s = Compiler.compile("(spell :mana 60 (time-stop (foe) 3))");
        assertEquals(Intent.CONTROL, s.profile().primary());
        assertEquals(12, s.profile().estimate(), 1e-9);
        // The rune holds at most 10 seconds, and so does the estimate.
        assertEquals(40, Compiler.compile("(spell :mana 60 (time-stop caster 30))").profile().estimate(), 1e-9);
        assertError("(time-stop here 2)", "插槽形状不符");
    }

    @Test
    void timeSkipIsPricedPerSecondPushed() {
        Spell s = Compiler.compile("(spell :mana 300 (time-skip 60))");
        assertEquals(240, s.profile().estimate(), 1e-9);
        assertEquals(Intent.UTILITY, s.profile().primary());
        // The rune pushes at most 36000 seconds, and so does the estimate.
        assertEquals(144000, Compiler.compile("(spell :mana 300 (time-skip 99999))").profile().estimate(), 1e-9);
    }

    @Test
    void worldWeatherIsPricedPerChange() {
        assertEquals(100, Compiler.compile("(spell :mana 300 (world-weather rain))").profile().estimate(), 1e-9);
        assertEquals(100, Compiler.compile("(spell :mana 300 (world-weather clear 1200))").profile().estimate(), 1e-9);
        assertEquals(200, Compiler.compile("(spell :mana 300 (world-weather thunder 30))").profile().estimate(), 1e-9);
        assertError("(world-weather here)", "插槽形状不符");
    }

    @Test
    void areaWeatherIsPricedByAreaAndTime() {
        // 10 to raise, then 0.02 × r² a second (a storm twice that) for its life.
        assertEquals(10 + 0.02 * 64 * 30, Compiler.compile("(spell :mana 300 (weather rain))").profile().estimate(), 1e-9);
        assertEquals(10 + 0.02 * 256 * 2 * 10, Compiler.compile("(spell :mana 300 (weather thunder :radius 16 :life 10))").profile().estimate(), 1e-9);
        assertEquals(10 + 0.02 * 48 * 48 * 600, Compiler.compile("(spell :mana 300 (weather snow :radius 99 :life 9999 :follow))").profile().estimate(), 1e-9);
        assertError("(weather 3)", "插槽形状不符");
    }

    @Test
    void infusingIsUtilityPricedAtOnePointOneAPoint() {
        Spell s = Compiler.compile("(spell :mana 30 (infuse caster 20))");
        assertEquals(Intent.UTILITY, s.profile().primary());
        assertEquals(22, s.profile().estimate(), 1e-9);
        Spell block = Compiler.compile("(spell :mana 30 (infuse-block (look-block) 10))");
        assertEquals(11 + 1, block.profile().estimate(), 1e-9); // look-block itself is 1
        assertError("(infuse-block caster 10)", "插槽形状不符");
        assertError("(infuse (look-block) 10)", "插槽形状不符");
    }

    @Test
    void lightningIsAStrikePricedPerBolt() {
        Spell s = Compiler.compile("(spell :mana 50 (lightning (pos-of (foe))))");
        assertEquals(Intent.STRIKE, s.profile().primary());
        // Estimated at the dry-sky price; rain (40) and storms (20) only lower it.
        assertEquals(50, s.profile().estimate(), 1e-9);
        assertTrue(s.profile().assumptions().stream().anyMatch(a -> a.contains("雷暴中 20")));
        assertError("(lightning 3)", "插槽形状不符");
    }

    @Test
    void recursionCostsStability() {
        Spell plain = Compiler.compile("(ray :to (nearest) (on-hit (damage target 1)))");
        Spell rec = Compiler.compile("(ray :to (nearest) (on-hit (damage target 1) (recurse)))");
        assertTrue(rec.stability() > plain.stability() + 10);
        assertFalse(rec.notes().isEmpty());
    }

    private static void assertError(String src, String fragment) {
        LogosError e = assertThrows(LogosError.class, () -> Compiler.compile(src), src);
        System.out.println(src + "  →  " + e.describe());
        assertTrue(e.getMessage().contains(fragment), "expected '" + fragment + "' in: " + e.getMessage());
    }
}
