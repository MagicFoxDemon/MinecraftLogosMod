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
    void prismaticVolleyKeepsSixExclusiveHomingBranches() {
        Spell s = Compiler.compile(Examples.ALL.get("prismatic-volley"));
        assertEquals(59, s.mana());
        Node split = s.body().getFirst();
        assertEquals("split", split.rune.name);
        assertEquals(3.0, split.arg(0).value);
        Node choice = split.body.getFirst();
        assertEquals("switch", choice.rune.name);
        assertEquals(7, choice.args.size());
        assertTrue(choice.arg(0).flags.contains("whole"));
        assertEquals(5.0, choice.arg(0).arg(0).value);
        List<Element> elements = List.of(Element.ARCANE, Element.FIRE, Element.LIGHTNING, Element.VOID, Element.PHYSICAL, Element.FROST);
        for (int i = 0; i < elements.size(); i++) {
            Node projectile = choice.arg(i + 1);
            assertEquals("projectile", projectile.rune.name);
            assertEquals(Motion.HOMING, projectile.kw.get("motion").value);
            assertEquals(elements.get(i), projectile.element);
            assertEquals(elements.get(i), projectile.events.get("on-hit").getFirst().arg(2).value);
            double cost = (double) projectile.events.get("on-hit").getFirst().arg(1).value * elements.get(i).costFactor;
            assertTrue(cost <= 10 && cost >= 9.99 - 1e-9, "each hit should use at most 10 mana, rounded down to two damage decimals: " + cost);
        }
    }

    @Test
    void speciesProducesAKindThatCanBeFiltered() {
        Spell s = Compiler.compile(Examples.ALL.get("npc-group-heal"));
        Node kind = s.body().getFirst().arg(0).kw.get("filter");
        assertEquals(Type.FILTER, kind.type);
        assertEquals("species", kind.arg(0).rune.name);
        assertEquals(Type.KIND, kind.arg(0).type);
        assertEquals(Intent.HEAL, s.profile().primary());
        assertEquals(100, s.mana());
        Compiler.compile("(spell :trigger attack (heal (entities :filter (kind (species target))) 6))");
        Compiler.compile("(let $k (species caster) (heal (entities :filter (kind $k)) 6))");
        assertThrows(LogosError.class, () -> Compiler.compile("(heal (entities :filter (species caster)) 6)"));
        assertThrows(LogosError.class, () -> Compiler.compile("(heal (entities :filter (kind (species here))) 6)"));
        assertThrows(LogosError.class, () -> Compiler.compile("(heal (entities :filter (kind (species (entities)))) 6)"));
        assertThrows(LogosError.class, () -> Compiler.compile("(heal (entities :filter (kind (species target))) 6)"));
    }

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
    void prismaticBarrageUsesSixExclusiveRaysWithCappedHitCosts() {
        Spell s = Compiler.compile(Examples.ALL.get("prismatic-barrage"));
        assertEquals(120, s.mana());
        Node construct = s.body().getFirst();
        assertEquals(ConstructShape.DISK, construct.kw.get("shape").value);
        assertEquals(1.0, construct.kw.get("radius").value);
        assertEquals(1.5, construct.kw.get("life").value);
        assertEquals(0.25, construct.kw.get("every").value);
        assertTrue(construct.flags.contains("sigil"));
        assertEquals(TargetKind.NONE, construct.kw.get("solid").arg(0).value);
        Node choice = construct.events.get("on-tick").getFirst();
        assertEquals(7, choice.args.size());
        assertEquals(5.0, choice.arg(0).arg(0).value);
        assertTrue(choice.arg(0).flags.contains("whole"));
        List<Element> elements = List.of(Element.ARCANE, Element.FIRE, Element.PHYSICAL, Element.FROST, Element.VOID, Element.LIGHTNING);
        for (int i = 0; i < elements.size(); i++) {
            Node ray = choice.arg(i + 1);
            assertEquals("ray", ray.rune.name);
            assertEquals(24.0, ray.kw.get("range").value);
            assertEquals("pos-of", ray.kw.get("from").rune.name);
            assertEquals("form", ray.kw.get("from").arg(0).value);
            assertEquals("dir-of", ray.kw.get("dir").rune.name);
            assertEquals("form", ray.kw.get("dir").arg(0).value);
            assertEquals(elements.get(i), ray.element);
            Node damage = ray.events.get("on-hit").getFirst();
            double cost = (double) damage.arg(1).value * elements.get(i).costFactor;
            assertTrue(cost <= 15 && cost >= 14.99, "each hit must stay within its 15 mana budget: " + cost);
        }
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
    void arcaneAndLifeDamageCompile() {
        Spell s = Compiler.compile("(projectile (on-hit (damage target 2 arcane)))");
        assertEquals(Element.ARCANE, s.body().getFirst().element);
        // Whether a player may write life is the unlocks' business (UnlocksTest), no longer the compiler's.
        assertEquals(Element.LIFE, Compiler.compile("(projectile (on-hit (damage target 2 life)))").body().getFirst().element);
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
    void randomTakesBoundsAndWholeMode() {
        Node old = Compiler.compile("(let $r (random 5) (do))").body().getFirst().arg(1);
        assertEquals("5", old.arg(0).show());
        Node die = Compiler.compile("(let $r (random 6 :min 1 :whole) (do))").body().getFirst().arg(1);
        assertEquals("1", die.kw.get("min").show());
        assertTrue(die.flags.contains("whole"));
    }

    @Test
    void mixTakesNumbersOrPlaces() {
        assertEquals(Type.NUM, Compiler.compile("(let $m (mix 0 10 (random)) (do))").body().getFirst().arg(1).type);
        assertEquals(Type.POS, Compiler.compile("(blink (mix-pos here (look-point) 0.5))").body().getFirst().arg(0).type);
        assertError("(blink (mix-pos here 3 0.5))", "");
    }

    @Test
    void choiceKeywordsTakeOneOfTheirWords() {
        Node plain = Compiler.compile("(let $r (round 3) (do))").body().getFirst().arg(1);
        assertEquals("nearest", plain.kw.get("mode").value);
        assertEquals("(round 3)", plain.show());
        Node up = Compiler.compile("(let $r (round 3 :mode up) (do))").body().getFirst().arg(1);
        assertEquals("(round 3 :mode up)", up.show());
        assertError("(let $r (round 2.5 :mode sideways) (do))", "nearest / up / down");
        assertError("(let $r (round 2.5 :mode (random)) (do))", ":mode");
        assertTrue(Runes.get("round").signature().contains("[:mode nearest|up|down]"));
    }

    @Test
    void waveTakesAShapeAndScales() {
        Node w = Compiler.compile("(let $w (wave i :shape saw :period 20 :amp 2) (do))").body().getFirst().arg(1);
        assertEquals("saw", w.kw.get("shape").value);
        assertEquals("sine", Compiler.compile("(let $w (wave 1) (do))").body().getFirst().arg(1).kw.get("shape").value);
        assertError("(let $w (wave 1 :shape zigzag) (do))", "sine / triangle / saw / square");
    }

    @Test
    void switchAndChoose() {
        Node sw = Compiler.compile("(switch (random 2 :whole) (heal caster 1) (do) (on-other (heal caster 2)))").body().getFirst();
        assertEquals(3, sw.args.size());
        assertTrue(sw.events.containsKey("on-other"));
        assertEquals(Type.POS, Compiler.compile("(blink (choose 1 here (look-point)))").body().getFirst().arg(0).type);
        assertEquals(Type.NUM, Compiler.compile("(heal caster (choose i 1 2 3 :wrap))").body().getFirst().arg(1).type);
        assertError("(heal caster (choose 0 1 here))", "同一种");
        assertError("(heal caster (choose 0 fire fire))", "只能在");
        assertError("(blink (choose 0 1 2))", "插槽形状不符");
    }

    @Test
    void formsAndSummonHandBackWhatTheyMade() {
        assertError("(heal caster (life-of form))", "此形");
        Compiler.compile("(projectile (on-tick (heal caster (life-of form))))");
        // Nested events that make no body still know the outer one.
        Compiler.compile("(field (on-enter (each (enemies) (heal caster (life-of form)))))");
        Node let = Compiler.compile("(let $f (field :life 2) (delay 1 (heal caster (life-of $f))))").body().getFirst();
        assertEquals(Type.ACTION, let.arg(1).type);
        assertEquals(Type.FORM, let.body.getFirst().body.getFirst().arg(1).arg(0).type);
        assertEquals(Type.ENTITY, Compiler.compile("(let $m (summon zombie) (glow $m 5))").body().getFirst().body.getFirst().arg(0).type);
        assertError("(let $x (heal caster 1) (do))", "不能记住行动");
        assertError("(blink (life-of (projectile)))", "");
    }

    @Test
    void spotSamplesAroundAPlaceOrInAForm() {
        assertEquals(Type.POS, Compiler.compile("(blink (spot :radius 6 :shape disk :ground))").body().getFirst().arg(0).type);
        Compiler.compile("(field (on-tick (lightning (spot :in form :where surface))))");
        Compiler.compile("(let $w (construct) (delay 1 (lightning (spot :in $w))))");
        assertError("(blink (spot :in caster))", "插槽形状不符");
        assertError("(blink (spot :shape pyramid))", "ball / disk / cube");
    }

    @Test
    void projectilesMayWearACreature() {
        Node zombie = Compiler.compile("(projectile :look zombie)").body().getFirst().kw.get("look");
        assertEquals(Type.CREATURE, zombie.type);
        assertEquals("minecraft:zombie", zombie.value);
        assertEquals(Type.LOOK, Compiler.compile("(projectile :look &mace)").body().getFirst().kw.get("look").type);
        assertError("(construct :look zombie)", "只能是方块");
    }

    @Test
    void formsCanBeReadMovedTurnedAndDispersed() {
        // Inside its events and through its kept output alike.
        Compiler.compile("(projectile (on-tick (if (> (life-of form) 0.5) (turn form (dir-of caster)))))");
        Compiler.compile("(let $f (field :life 4) (delay 1 (move $f (pos-of caster)) (delay 2 (halt $f) (disperse $f))))");
        // A construct's self still works where only constructs did before.
        Compiler.compile("(construct (on-tick (move self (above here 1)) (halt self)))");
        assertEquals(Type.POS, Compiler.compile("(blink (pos-of (nearest)))").body().getFirst().arg(0).type);
        assertEquals(Type.DIR, Compiler.compile("(projectile (on-tick (turn form (dir-of form))))").body().getFirst()
                .events.get("on-tick").getFirst().arg(1).type);
        assertError("(disperse here)", "插槽形状不符");
    }

    @Test
    void endAllTakesAPlaceAndARadius() {
        Node n = Compiler.compile("(end-all :at (look-point) :radius 10)").body().getFirst();
        assertEquals(Type.POS, n.kw.get("at").type);
        // It runs without :players; reaching other players' spells is decided when it runs.
        assertFalse(Compiler.compile("(end-all)").affectsPlayers());
        assertTrue(Compiler.compile("(end-all)").profile().assumptions().stream().anyMatch(a -> a.contains("万咒皆终")));
    }

    @Test
    void useBlockTakesABlockAndAnOptionalItem() {
        assertEquals(2, Compiler.compile("(use-block here)").profile().estimate(), 1e-6);
        Node n = Compiler.compile("(use-block here :item &minecraft:bone_meal)").body().getFirst();
        assertEquals(Type.LOOK, n.kw.get("item").type);
        assertError("(use-block caster)", "◇位置 或 ▦方块群");
    }

    @Test
    void resizeCostsTheSameToGrowOrShrink() {
        assertEquals(8, Compiler.compile("(resize caster 2 30)").profile().estimate(), 1e-6);
        assertEquals(8, Compiler.compile("(resize caster 0.5)").profile().estimate(), 1e-6);
        assertEquals(2, Compiler.compile("(resize caster 1 600)").profile().estimate(), 1e-6);
        assertError("(resize here 2)", "实体");
    }

    @Test
    void possessCostsHealthOverFiveTimesRootSeconds() {
        // Estimated on 20 health: 4 × √seconds, seconds 1–600, 30 by default.
        assertEquals(20, Compiler.compile("(possess caster 25)").profile().estimate(), 1e-6);
        assertEquals(4 * Math.sqrt(30), Compiler.compile("(possess caster)").profile().estimate(), 0.051);
        assertEquals(4 * Math.sqrt(600), Compiler.compile("(possess caster 5000)").profile().estimate(), 0.051);
        assertError("(possess here)", "实体");
    }

    @Test
    void incarnateCostsMaxHealthOverFiveTimesRootSeconds() {
        // Estimated on 20 max health: 4 × √seconds, seconds 1–600, 30 by default.
        assertEquals(20, Compiler.compile("(incarnate zombie 25)").profile().estimate(), 1e-6);
        assertEquals(4 * Math.sqrt(30), Compiler.compile("(incarnate bat)").profile().estimate(), 0.051);
        assertEquals(4 * Math.sqrt(600), Compiler.compile("(incarnate pig 5000)").profile().estimate(), 0.051);
        assertError("(incarnate caster)", "生物");
    }

    @Test
    void waterWalkCostsPerSecond() {
        // 0.3 a second, 60 seconds by default, at most 600.
        assertEquals(18, Compiler.compile("(water-walk caster)").profile().estimate(), 1e-6);
        assertEquals(3, Compiler.compile("(water-walk caster 10)").profile().estimate(), 1e-6);
        assertEquals(180, Compiler.compile("(water-walk caster 5000)").profile().estimate(), 1e-6);
        assertError("(water-walk here)", "实体");
    }

    @Test
    void revertIsFreeOnOneselfAndTakesTargets() {
        // Undoing one's own form is free; another's price is only known when the spell runs.
        assertEquals(0, Compiler.compile("(revert)").profile().estimate(), 1e-6);
        Compiler.compile("(spell :players true (revert (nearest-player :radius 8)))");
        assertError("(revert here)", "实体");
    }

    @Test
    void imbueIsKeptUpLikeAField() {
        // 6 to wind, 0.5 a pulse (every 0.5 s by default): 30 seconds is 6 + 60 × 0.5.
        assertEquals(36, Compiler.compile("(imbue caster :life 30)").profile().estimate(), 0.051);
        assertEquals(36, Compiler.compile("(imbue :life 30)").profile().estimate(), 0.051);
        // Its blows are reckoned one every 2 seconds: 10 seconds is 5 blows.
        double blow = Compiler.compile("(damage caster 4 fire)").profile().estimate();
        assertEquals(6 + 20 * 0.5 + 5 * blow, Compiler.compile("(imbue caster :life 10 (on-hit (damage target 4 fire)))").profile().estimate(), 0.051);
        // self names the holder in its events, and only there.
        Compiler.compile("(imbue caster (on-swing (mark self #swung)) (on-kill (heal self 2)))");
        assertError("(mark self #x)", "self");
        assertError("(imbue here)", "实体");
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
        // A bare id is a creature to wear, so an unknown one is refused as no such creature.
        assertError("(projectile :look fire)", "没有叫 minecraft:fire 的生物");
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
    void phantasmIsPricedByTheParticle() {
        assertEquals(1.3, SpellProfile.of(Compiler.compile("(phantasm %portal :count 64)").body(), List.of()).estimate());
        assertEquals(1.3, SpellProfile.of(Compiler.compile("(phantasm %portal :count 1000)").body(), List.of()).estimate());
        assertEquals(0.2, SpellProfile.of(Compiler.compile("(phantasm %portal :count 10)").body(), List.of()).estimate());
        assertTrue(Runes.get("phantasm").doc.contains("0.02"), "its doc should give the price");
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
    void veinTakesTheMotionsOfTheGroundAndPricesThem() {
        Node vein = Compiler.compile("(vein :motion ring :range 8 (on-step (lightning here)) (on-hit (damage target 2)))").body().getFirst();
        assertEquals(Motion.RING, vein.kw.get("motion").value);
        assertEquals("snake", Compiler.compile("(vein :motion snake)").body().getFirst().kw.get("motion").show());
        // Forward, homing and wander suit both forms; a vein keeps to the ground, a projectile to the air.
        for (String shared : List.of("forward", "homing", "wander")) {
            Compiler.compile("(vein :motion " + shared + ")");
            Compiler.compile("(projectile :motion " + shared + ")");
        }
        for (String air : List.of("arc", "orbit", "boomerang")) assertError("(vein :motion " + air + ")", "游脉贴着地面爬");
        for (String ground : List.of("snake", "ring", "sweep")) assertError("(projectile :motion " + ground + ")", "魔弹不能");
        // 4 + 0.05 × width × height × range, plus the motion's surcharge; a ring's round is π times as long.
        assertEquals(4.8, Compiler.compile("(spell :mana 20 (vein))").profile().estimate(), 1e-6);
        assertEquals(8, Compiler.compile("(spell :mana 20 (vein :motion homing :width 2 :range 10))").profile().estimate(), 1e-6);
        // The estimate is shown to a tenth.
        assertEquals(6 + 0.8 * Math.PI, Compiler.compile("(spell :mana 20 (vein :motion ring))").profile().estimate(), 0.05);
        // Part of a ring: its round shrinks with the angle, never below one line.
        assertEquals(6 + 0.8 * Math.PI / 3, Compiler.compile("(spell :mana 20 (vein :motion ring :spread 120))").profile().estimate(), 0.05);
        assertEquals(6.8, Compiler.compile("(spell :mana 20 (vein :motion ring :spread 30))").profile().estimate(), 0.05);
        // A sweep crawls its arc, radius × angle, whatever its range.
        Node sweep = Compiler.compile("(vein :motion sweep :spread 90 :radius 4 :ccw)").body().getFirst();
        assertEquals(Motion.SWEEP, sweep.kw.get("motion").value);
        assertTrue(sweep.flags.contains("ccw"));
        assertEquals(5 + 0.1 * Math.PI, Compiler.compile("(spell :mana 20 (vein :motion sweep :spread 90 :radius 4 :range 60))").profile().estimate(), 0.05);
        // Climbing goes with any motion and costs nothing more.
        Node climbing = Compiler.compile("(vein :motion snake :climb)").body().getFirst();
        assertTrue(climbing.flags.contains("climb"));
        assertEquals(5.8, Compiler.compile("(spell :mana 20 (vein :motion snake :climb))").profile().estimate(), 0.05);
        // Leaving what it crawled over behind costs 0.02 × width × height × length × seconds, at most 30 seconds.
        assertEquals(4.8 + 0.02 * 16 * 5, Compiler.compile("(spell :mana 20 (vein :linger 5))").profile().estimate(), 0.05);
        assertEquals(4.8 + 0.02 * 16 * 30, Compiler.compile("(spell :mana 20 (vein :linger 99))").profile().estimate(), 0.05);
    }

    @Test
    void recursionCostsStability() {
        Spell plain = Compiler.compile("(ray :to (nearest) (on-hit (damage target 1)))");
        Spell rec = Compiler.compile("(ray :to (nearest) (on-hit (damage target 1) (recurse)))");
        assertTrue(rec.stability() > plain.stability() + 10);
        assertFalse(rec.notes().isEmpty());
    }

    /** {@code levels} nested sums: a value expression that deep. */
    private static String sums(int levels) {
        return "(+ 1 ".repeat(levels) + "1" + ")".repeat(levels);
    }

    /** {@code levels} nested branches around a heal, itself the deepest list: a flow that deep. */
    private static String branches(int levels) {
        return "(if true ".repeat(levels) + "(heal caster 1)" + ")".repeat(levels);
    }

    /** {@code levels} forms each firing the next from its hit event, two brackets a level. */
    private static String rays(int levels) {
        return "(ray :to (nearest) (on-hit ".repeat(levels) + "(heal caster 1)" + "))".repeat(levels);
    }

    @Test
    void nestingPastTheLimitIsAnOrdinarySyntaxError() {
        // As long as a page may be and all brackets: it stops at the limit, well before any thread's stack does.
        assertThrowsDepth("(".repeat(32_000));
        assertThrowsDepth("[".repeat(32_000));
        assertThrowsDepth("(spell :mana 5 (heal caster " + sums(Reader.MAX_DEPTH - 1) + "))");
        assertThrowsDepth("(spell :mana 5 " + branches(Reader.MAX_DEPTH - 1) + ")");
        // Depth is how far in, not how many: siblings do not add up.
        Compiler.compile("(spell :mana 5" + " (heal caster 1)".repeat(Reader.MAX_DEPTH * 2) + ")");
    }

    private static void assertThrowsDepth(String src) {
        LogosError e = assertThrows(LogosError.class, () -> Compiler.compile(src));
        assertTrue(e.getMessage().contains("嵌套太深"), e.getMessage());
    }

    /** On half a server thread's stack, and interpreted rather than compiled: real use has more room than this. */
    @Test
    void nestingAtTheLimitCompilesOnAThreadStack() throws InterruptedException {
        List<String> sources = List.of(
                "(spell :mana 5 (heal caster " + sums(Reader.MAX_DEPTH - 2) + "))",
                "(spell :mana 5 " + branches(Reader.MAX_DEPTH - 2) + ")",
                "(spell :mana 5 " + rays((Reader.MAX_DEPTH - 2) / 2) + ")");
        Throwable[] failed = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                for (String src : sources) {
                    Spell spell = Compiler.compile(src);
                    assertTrue(spell.profile() != null);
                }
            } catch (Throwable e) {
                failed[0] = e;
            }
        }, "deep-spell", 512 * 1024);
        t.start();
        t.join();
        if (failed[0] != null) throw new AssertionError("a spell at the nesting limit did not compile", failed[0]);
    }

    private static Node form(String src) {
        return Compiler.compile("(spell :mana 50 " + src + ")").body().getFirst();
    }

    @Test
    void formsKnowFromCompilingWhetherTheirHitsOnlyHelp() {
        assertTrue(form("(projectile (on-hit (heal target 2)))").helpsOnHit);
        assertTrue(form("(beam (on-enter (shield target 2)))").helpsOnHit);
        assertTrue(form("(field (on-enter (heal target 2)) (on-tick (damage (nearest) 2 fire)))").helpsOnHit, "only on-hit and on-enter decide");
        assertFalse(form("(projectile (on-hit (damage target 2)))").helpsOnHit);
        assertFalse(form("(projectile (on-hit (heal target 2) (damage target 2)))").helpsOnHit);
        assertFalse(form("(projectile)").helpsOnHit, "no hit handlers help nobody");
        assertFalse(form("(ray (on-hit (damage target 2)))").helpsOnHit);
        assertFalse(Compiler.compile("(spell :mana 50 :intent heal (projectile (on-hit (damage target 2))))").body().getFirst().helpsOnHit, "what it does decides, not what it declares");
        // A form inside another's handlers knows for itself.
        Node outer = form("(projectile (on-hit (heal target 2) (projectile (on-hit (damage target 2)))))");
        assertFalse(outer.helpsOnHit);
        assertFalse(outer.events.get("on-hit").getLast().helpsOnHit);
    }

    @Test
    void formsKnowFromCompilingWhatOneHitDeals() {
        assertEquals(5, form("(projectile (on-hit (damage target 3 fire) (drain target 2)))").hitDamage);
        assertEquals(3, form("(projectile (on-hit (damage-all (enemies) 3)))").hitDamage);
        assertEquals(3, form("(projectile (on-hit (repeat 4 (damage target 3))))").hitDamage, "written once, counted once");
        assertEquals(0, form("(projectile (on-hit (damage target (hp target))))").hitDamage, "a computed amount is not known yet");
        assertEquals(0, form("(beam (on-enter (damage target 4)))").hitDamage, "only on-hit counts");
        Node outer = form("(projectile (on-hit (projectile (on-hit (damage target 9)))))");
        assertEquals(0, outer.hitDamage, "the forms it launches are not its own hit");
        assertEquals(9, outer.events.get("on-hit").getFirst().hitDamage);
    }

    @Test
    void aSpellKnowsFromCompilingHowItLooks() {
        assertEquals(Element.FIRE, Compiler.compile("(spell :mana 50 (damage (nearest) 3 fire))").element());
        assertEquals(Element.ARCANE, Compiler.compile("(spell :mana 50 (mark caster #seen))").element());
        for (String src : Examples.ALL.values()) {
            Spell s = Compiler.compile(src);
            assertEquals(Compiler.inferElement(s.body()), s.element(), s.name());
        }
    }

    private static void assertError(String src, String fragment) {
        LogosError e = assertThrows(LogosError.class, () -> Compiler.compile(src), src);
        System.out.println(src + "  →  " + e.describe());
        assertTrue(e.getMessage().contains(fragment), "expected '" + fragment + "' in: " + e.getMessage());
    }
}
