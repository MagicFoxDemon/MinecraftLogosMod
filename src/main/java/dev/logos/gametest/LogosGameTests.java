package dev.logos.gametest;

import java.util.List;
import java.util.function.Consumer;

import dev.logos.LogosMod;
import dev.logos.lang.Examples;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * In-world tests of the spell runtime: a mock player casts real spells at real mobs.
 * Run with {@code ./gradlew runGameTestServer}, or {@code /test runall} in a dev client.
 */
public final class LogosGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosMod.MODID);

    private static final List<String> TESTS = List.of(
            "projectile_impact_expiry", "projectile_impact_wall", "projectile_impact_piercing",
            "fireball_hits_and_bursts", "chain_lightning_bounces", "execute_with_proof_is_cheap", "execute_without_proof_starves",
            "paradox_backfires", "recursion_is_bounded", "runaway_spawning_is_bounded", "attack_passive_fires", "marks_link_spells", "graph_roundtrip", "graph_rejects_bad_wires", "graph_errors_point_at_nodes", "config_limits_apply",
            "world_toggle_state", "world_grow_and_decay", "world_players_flag", "world_items_magnet", "world_nether_step",
            "world_wand_casts_selected_page", "world_table_inscribes", "beam_follows_gaze", "world_filter_kinds",
            "construct_stops_movement", "construct_one_way_arrows", "construct_shields_explosion", "construct_platform_stands", "construct_stops_other_spells",
            "construct_pass_ignites_copies", "construct_fire_wall", "construct_carries_rider", "construct_follow_turns", "world_anchors",
            "familiar_unlock_summon", "familiar_loyal_targeting", "familiar_friendly_fire_credit", "familiar_drops", "familiar_creeper_no_grief",
            "familiar_dragon_circles", "familiar_dragon_throne",
            "passive_codex_without_wand", "rune_disarm_order", "rune_potion_effects",
            "page_capacity_caps_injection", "page_immolation_burns", "free_mana_from_leftovers", "free_mana_gathered", "copy_table_copies", "alchemy_brews_raw_mash", "alchemy_crafts_gunpowder", "alchemy_crafts_table", "alchemy_bottles_drinkable",
            "process_ferments", "process_designates_and_routes", "process_dissolves_water_bottles", "process_dissolves_from_tank", "process_chains", "process_needs_mana", "process_shows_working", "process_cards_keep_and_remove", "process_pours_bottles_first", "process_says_what_is_missing", "alchemy_table_switch", "powder_settles", "powder_is_eaten", "powder_dissolves_back", "brews_from_base", "brews_from_water", "process_designates_exactly", "process_products_exact", "status_follows_designations", "status_says_outputs_full",
            "alchemy_table_halves", "alchemy_table_automation", "alchemy_table_tank", "alchemy_table_mana", "alchemy_table_bottles", "alchemy_table_free_mana",
            "familiar_ride_ground", "familiar_dragon_two_seats", "familiar_age_frozen", "familiar_names_dismiss", "familiar_gather", "familiar_warden_fights", "familiar_warden_vs_warden", "familiar_sic", "familiar_stance_hunts",
            "lifeform_spawn_egg_levels", "lifeform_spawn_egg_dispenser", "lifeform_spawn_egg_disabled", "lifeform_spawn_egg_water",
            "lifeform_summon_rules", "lifeform_casts_attack_page", "lifeform_dormant_and_fed", "lifeform_falls_when_dry", "lifeform_executes_itself",
            "lifeform_hovers", "lifeform_combo", "lifeform_heals_master", "lifeform_forages", "lifeform_role",
            "colossus_spawn_egg_player", "colossus_spawn_egg_dispenser", "colossus_spawn_egg_disabled", "colossus_spawn_egg_water",
            "colossus_armour", "colossus_execute_drains", "colossus_heavy_and_finish", "colossus_grab_finishes", "colossus_grab_slams",
            "colossus_grabs_big", "colossus_grabs_from_reach", "colossus_defends_itself", "colossus_orders_before_self_defense", "colossus_stomps_small_foes", "colossus_grabs_rider", "colossus_lets_go_when_killed", "colossus_punches_crowd", "colossus_punches_dragon", "colossus_cannon", "colossus_cannon_unbroken", "colossus_cannon_strikes_at_once", "colossus_frenzy", "colossus_board", "colossus_drive_controls", "colossus_carries_master_still", "colossus_flower_cooldown", "colossus_rider_sheltered", "colossus_jumps_off",
            "leech_hunts_richest", "leech_clings_and_drains", "leech_knocked_off", "leech_drains_familiar_health", "leech_splits", "leech_offspring_do_not_split", "leech_bane_and_death", "leech_familiar_obeys");

    private LogosGameTests() {}

    public static void register(IEventBus modBus) {
        FUNCTIONS.register("projectile_impact_expiry", () -> ProjectileImpactGameTests::expiry);
        FUNCTIONS.register("projectile_impact_wall", () -> ProjectileImpactGameTests::block);
        FUNCTIONS.register("projectile_impact_piercing", () -> ProjectileImpactGameTests::piercing);
        FUNCTIONS.register("fireball_hits_and_bursts", () -> LogosGameTests::fireball);
        FUNCTIONS.register("beam_follows_gaze", () -> LogosGameTests::beamFollowsGaze);
        FUNCTIONS.register("chain_lightning_bounces", () -> LogosGameTests::chainLightning);
        FUNCTIONS.register("execute_with_proof_is_cheap", () -> LogosGameTests::executeProof);
        FUNCTIONS.register("execute_without_proof_starves", () -> LogosGameTests::executeBrute);
        FUNCTIONS.register("paradox_backfires", () -> LogosGameTests::paradox);
        FUNCTIONS.register("recursion_is_bounded", () -> LogosGameTests::recursion);
        FUNCTIONS.register("runaway_spawning_is_bounded", () -> LogosGameTests::runaway);
        FUNCTIONS.register("attack_passive_fires", () -> LogosGameTests::attackPassive);
        FUNCTIONS.register("marks_link_spells", () -> LogosGameTests::marks);
        FUNCTIONS.register("graph_roundtrip", () -> GraphGameTests::roundtrip);
        FUNCTIONS.register("graph_rejects_bad_wires", () -> GraphGameTests::rejectsBadWires);
        FUNCTIONS.register("graph_errors_point_at_nodes", () -> GraphGameTests::errorsPointAtNodes);
        FUNCTIONS.register("config_limits_apply", () -> LogosGameTests::configLimits);
        FUNCTIONS.register("world_toggle_state", () -> WorldGameTests::toggleState);
        FUNCTIONS.register("world_grow_and_decay", () -> WorldGameTests::growAndDecay);
        FUNCTIONS.register("world_players_flag", () -> WorldGameTests::playersFlag);
        FUNCTIONS.register("world_items_magnet", () -> WorldGameTests::itemsMagnet);
        FUNCTIONS.register("world_filter_kinds", () -> WorldGameTests::filterKinds);
        FUNCTIONS.register("world_anchors", () -> WorldGameTests::anchors);
        FUNCTIONS.register("familiar_unlock_summon", () -> FamiliarGameTests::unlockAndSummon);
        FUNCTIONS.register("familiar_loyal_targeting", () -> FamiliarGameTests::loyalTargeting);
        FUNCTIONS.register("familiar_friendly_fire_credit", () -> FamiliarGameTests::friendlyFireAndCredit);
        FUNCTIONS.register("familiar_drops", () -> FamiliarGameTests::dropsOnlyPickedUp);
        FUNCTIONS.register("familiar_creeper_no_grief", () -> FamiliarGameTests::creeperBreaksNothing);
        FUNCTIONS.register("familiar_dragon_circles", () -> FamiliarGameTests::dragonCirclesMaster);
        FUNCTIONS.register("familiar_dragon_throne", () -> FamiliarGameTests::dragonThroneRules);
        FUNCTIONS.register("familiar_ride_ground", () -> FamiliarGameTests::rideGroundMount);
        FUNCTIONS.register("familiar_dragon_two_seats", () -> FamiliarGameTests::dragonTwoSeats);
        FUNCTIONS.register("familiar_age_frozen", () -> FamiliarGameTests::ageFrozenButSpellsWork);
        FUNCTIONS.register("familiar_names_dismiss", () -> FamiliarGameTests::namesAndDismiss);
        FUNCTIONS.register("familiar_gather", () -> FamiliarGameTests::gatherWalksOrGivesUp);
        FUNCTIONS.register("familiar_warden_fights", () -> FamiliarGameTests::wardenFightsAtOnce);
        FUNCTIONS.register("familiar_warden_vs_warden", () -> FamiliarGameTests::wardenFightsWildWarden);
        FUNCTIONS.register("familiar_sic", () -> FamiliarGameTests::sicOrdersAttack);
        FUNCTIONS.register("familiar_stance_hunts", () -> FamiliarGameTests::stanceHunts);
        FUNCTIONS.register("lifeform_summon_rules", () -> LifeformGameTests::summonRules);
        FUNCTIONS.register("lifeform_spawn_egg_levels", () -> LifeformSpawnEggGameTests::levels);
        FUNCTIONS.register("lifeform_spawn_egg_dispenser", () -> LifeformSpawnEggGameTests::dispenser);
        FUNCTIONS.register("lifeform_spawn_egg_disabled", () -> LifeformSpawnEggGameTests::disabled);
        FUNCTIONS.register("lifeform_spawn_egg_water", () -> LifeformSpawnEggGameTests::water);
        FUNCTIONS.register("lifeform_casts_attack_page", () -> LifeformGameTests::castsAttackPage);
        FUNCTIONS.register("lifeform_dormant_and_fed", () -> LifeformGameTests::dormantAndFed);
        FUNCTIONS.register("lifeform_falls_when_dry", () -> LifeformGameTests::fallsWhenDry);
        FUNCTIONS.register("lifeform_executes_itself", () -> LifeformGameTests::executesItself);
        FUNCTIONS.register("lifeform_hovers", () -> LifeformGameTests::hoversAtMaster);
        FUNCTIONS.register("lifeform_combo", () -> LifeformGameTests::comboWhenRich);
        FUNCTIONS.register("lifeform_heals_master", () -> LifeformGameTests::healsMaster);
        FUNCTIONS.register("lifeform_forages", () -> LifeformGameTests::foragesFreeMana);
        FUNCTIONS.register("lifeform_role", () -> LifeformGameTests::roleByStance);
        FUNCTIONS.register("leech_hunts_richest", () -> LeechGameTests::huntsRichest);
        FUNCTIONS.register("leech_clings_and_drains", () -> LeechGameTests::clingsAndDrains);
        FUNCTIONS.register("leech_knocked_off", () -> LeechGameTests::knockedOff);
        FUNCTIONS.register("leech_drains_familiar_health", () -> LeechGameTests::drainsFamiliarHealth);
        FUNCTIONS.register("leech_splits", () -> LeechGameTests::splits);
        FUNCTIONS.register("leech_offspring_do_not_split", () -> LeechGameTests::offspringDoNotSplit);
        FUNCTIONS.register("leech_bane_and_death", () -> LeechGameTests::baneAndDeath);
        FUNCTIONS.register("leech_familiar_obeys", () -> LeechGameTests::familiarObeys);
        FUNCTIONS.register("colossus_armour", () -> ColossusGameTests::armour);
        FUNCTIONS.register("colossus_spawn_egg_player", () -> ColossusSpawnEggGameTests::player);
        FUNCTIONS.register("colossus_spawn_egg_dispenser", () -> ColossusSpawnEggGameTests::dispenser);
        FUNCTIONS.register("colossus_spawn_egg_disabled", () -> ColossusSpawnEggGameTests::disabled);
        FUNCTIONS.register("colossus_spawn_egg_water", () -> ColossusSpawnEggGameTests::water);
        FUNCTIONS.register("colossus_execute_drains", () -> ColossusGameTests::executeDrains);
        FUNCTIONS.register("colossus_heavy_and_finish", () -> ColossusGameTests::heavyAndFinish);
        FUNCTIONS.register("colossus_grab_finishes", () -> ColossusGameTests::grabFinishes);
        FUNCTIONS.register("colossus_grab_slams", () -> ColossusGameTests::grabSlams);
        FUNCTIONS.register("colossus_grabs_big", () -> ColossusGameTests::grabsBig);
        FUNCTIONS.register("colossus_grabs_from_reach", () -> ColossusGameTests::grabsFromReach);
        FUNCTIONS.register("colossus_defends_itself", () -> ColossusGameTests::defendsItself);
        FUNCTIONS.register("colossus_orders_before_self_defense", () -> ColossusGameTests::ordersBeforeSelfDefense);
        FUNCTIONS.register("colossus_stomps_small_foes", () -> ColossusGameTests::stompsSmallFoes);
        FUNCTIONS.register("colossus_grabs_rider", () -> ColossusGameTests::grabsRider);
        FUNCTIONS.register("colossus_lets_go_when_killed", () -> ColossusGameTests::letsGoWhenKilled);
        FUNCTIONS.register("colossus_punches_crowd", () -> ColossusGameTests::punchesCrowd);
        FUNCTIONS.register("colossus_punches_dragon", () -> ColossusGameTests::punchesDragon);
        FUNCTIONS.register("colossus_cannon", () -> ColossusGameTests::cannonAtRange);
        FUNCTIONS.register("colossus_cannon_unbroken", () -> ColossusGameTests::cannonActsUnbroken);
        FUNCTIONS.register("colossus_cannon_strikes_at_once", () -> ColossusGameTests::cannonStrikesAtOnce);
        FUNCTIONS.register("colossus_frenzy", () -> ColossusGameTests::frenzyAndBurnout);
        FUNCTIONS.register("colossus_board", () -> ColossusGameTests::boardAndPutDown);
        FUNCTIONS.register("colossus_drive_controls", () -> ColossusGameTests::driveControls);
        FUNCTIONS.register("colossus_carries_master_still", () -> ColossusGameTests::carriesMasterStill);
        FUNCTIONS.register("colossus_flower_cooldown", () -> ColossusGameTests::flowerCooldown);
        FUNCTIONS.register("colossus_rider_sheltered", () -> ColossusGameTests::riderSheltered);
        FUNCTIONS.register("colossus_jumps_off", () -> ColossusGameTests::jumpsOff);
        FUNCTIONS.register("construct_stops_movement", () -> ConstructGameTests::wallStopsMovement);
        FUNCTIONS.register("construct_one_way_arrows", () -> ConstructGameTests::oneWayStopsArrows);
        FUNCTIONS.register("construct_shields_explosion", () -> ConstructGameTests::wallShieldsExplosion);
        FUNCTIONS.register("construct_platform_stands", () -> ConstructGameTests::platformHoldsAndStands);
        FUNCTIONS.register("construct_stops_other_spells", () -> ConstructGameTests::wallStopsOtherSpells);
        FUNCTIONS.register("construct_pass_ignites_copies", () -> ConstructGameTests::passIgnitesAndCopies);
        FUNCTIONS.register("construct_fire_wall", () -> ConstructGameTests::fireWallIgnitesWalkers);
        FUNCTIONS.register("construct_carries_rider", () -> ConstructGameTests::platformCarriesRider);
        FUNCTIONS.register("construct_follow_turns", () -> ConstructGameTests::followWallTurns);
        FUNCTIONS.register("world_nether_step", () -> WorldGameTests::netherStep);
        FUNCTIONS.register("world_wand_casts_selected_page", () -> WorldGameTests::wandCastsSelectedPage);
        FUNCTIONS.register("world_table_inscribes", () -> WorldGameTests::tableInscribes);
        FUNCTIONS.register("passive_codex_without_wand", () -> RuneGameTests::codexPassiveWithoutWand);
        FUNCTIONS.register("rune_disarm_order", () -> RuneGameTests::disarmOrder);
        FUNCTIONS.register("rune_potion_effects", () -> RuneGameTests::potionEffects);
        FUNCTIONS.register("page_capacity_caps_injection", () -> PageGameTests::capacityCapsInjection);
        FUNCTIONS.register("page_immolation_burns", () -> PageGameTests::immolationBurnsPage);
        FUNCTIONS.register("free_mana_from_leftovers", () -> PageGameTests::freeManaFromLeftovers);
        FUNCTIONS.register("free_mana_gathered", () -> PageGameTests::freeManaGathered);
        FUNCTIONS.register("copy_table_copies", () -> PageGameTests::copyTableCopies);
        FUNCTIONS.register("alchemy_brews_raw_mash", () -> AlchemyGameTests::brewsRawMash);
        FUNCTIONS.register("alchemy_crafts_gunpowder", () -> AlchemyGameTests::craftsGunpowder);
        FUNCTIONS.register("alchemy_crafts_table", () -> AlchemyGameTests::craftsAlchemyTable);
        FUNCTIONS.register("alchemy_bottles_drinkable", () -> AlchemyGameTests::bottlesAreDrinkable);
        FUNCTIONS.register("process_ferments", () -> AlchemyGameTests::processFerments);
        FUNCTIONS.register("process_designates_and_routes", () -> AlchemyGameTests::processDesignatesAndRoutes);
        FUNCTIONS.register("process_dissolves_water_bottles", () -> AlchemyGameTests::processDissolvesWaterBottles);
        FUNCTIONS.register("process_dissolves_from_tank", () -> AlchemyGameTests::processDissolvesFromTank);
        FUNCTIONS.register("process_chains", () -> AlchemyGameTests::processChains);
        FUNCTIONS.register("process_needs_mana", () -> AlchemyGameTests::processNeedsMana);
        FUNCTIONS.register("process_shows_working", () -> AlchemyGameTests::processShowsWorking);
        FUNCTIONS.register("process_cards_keep_and_remove", () -> AlchemyGameTests::processCardsKeepAndRemove);
        FUNCTIONS.register("process_pours_bottles_first", () -> AlchemyGameTests::processPoursBottlesFirst);
        FUNCTIONS.register("process_says_what_is_missing", () -> AlchemyGameTests::processSaysWhatIsMissing);
        FUNCTIONS.register("alchemy_table_switch", () -> AlchemyGameTests::tableSwitchAndRedstone);
        FUNCTIONS.register("powder_settles", () -> AlchemyGameTests::powderSettles);
        FUNCTIONS.register("powder_is_eaten", () -> AlchemyGameTests::powderIsEaten);
        FUNCTIONS.register("powder_dissolves_back", () -> AlchemyGameTests::powderDissolvesBack);
        FUNCTIONS.register("brews_from_base", () -> AlchemyGameTests::brewsFromBase);
        FUNCTIONS.register("brews_from_water", () -> AlchemyGameTests::brewsFromWater);
        FUNCTIONS.register("process_designates_exactly", () -> AlchemyGameTests::processDesignatesExactly);
        FUNCTIONS.register("process_products_exact", () -> AlchemyGameTests::processProductsExact);
        FUNCTIONS.register("status_follows_designations", () -> AlchemyGameTests::statusFollowsDesignations);
        FUNCTIONS.register("status_says_outputs_full", () -> AlchemyGameTests::statusSaysOutputsFull);
        FUNCTIONS.register("alchemy_table_halves", () -> AlchemyGameTests::tableHalvesBreakTogether);
        FUNCTIONS.register("alchemy_table_automation", () -> AlchemyGameTests::tableAutomationIsOneWay);
        FUNCTIONS.register("alchemy_table_tank", () -> AlchemyGameTests::tableTankHoldsOneFluid);
        FUNCTIONS.register("alchemy_table_mana", () -> AlchemyGameTests::tableManaSources);
        FUNCTIONS.register("alchemy_table_bottles", () -> AlchemyGameTests::tableBottles);
        FUNCTIONS.register("alchemy_table_free_mana", () -> AlchemyGameTests::tableGathersFreeMana);
        FUNCTIONS.register(modBus);
        modBus.addListener(LogosGameTests::onRegisterTests);
    }

    private static void onRegisterTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> env = event.registerEnvironment(id("default"), new TestEnvironmentDefinition.AllOf());
        for (String name : TESTS) {
            event.registerTest(id(name), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                    // 400 ticks: polled tests finish as soon as they pass; the headroom is for a loaded machine.
                    new TestData<>(env, Identifier.withDefaultNamespace("empty"), 400, 0, true, Rotation.NONE, false, 1, 1, false, 16)));
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(LogosMod.MODID, path);
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private static ServerPlayer caster(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 20; z++) h.setBlock(x, 1, z, Blocks.STONE);
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(4.5, 2, 1.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0); // yaw 0 faces +Z
        p.setYHeadRot(0);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 10000;
        m.trace = false;
        return p;
    }

    private static <T extends Mob> T mob(GameTestHelper h, EntityType<T> type, double x, double z) {
        T e = h.spawn(type, new Vec3(x, 2, z));
        e.setNoAi(true);
        return e;
    }

    private static SpellInstance cast(GameTestHelper h, ServerPlayer p, String src) {
        SpellInstance inst = Casting.cast(p, Casting.compile(src), null, null);
        h.assertTrue(inst != null, "cast was refused");
        return inst;
    }

    // ---- tests -------------------------------------------------------------------------------

    private static void fireball(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var husk = mob(h, EntityType.HUSK, 4.5, 9.5);
        SpellInstance inst = cast(h, p, Examples.ALL.get("fireball"));
        h.succeedWhen(() -> {
            h.assertTrue(inst.finished(), "spell still running");
            h.assertTrue(husk.getHealth() <= husk.getMaxHealth() - 7, "expected on-hit (5) + on-expire burst (2), health=" + husk.getHealth());
            h.assertTrue(husk.isOnFire(), "on-expire ignite did not apply");
        });
    }

    /** A beam without :from/:dir re-forms along the caster's gaze every tick: turning sweeps it onto a new target. */
    private static void beamFollowsGaze(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var ahead = mob(h, EntityType.HUSK, 4.5, 8.5);
        var side = mob(h, EntityType.HUSK, 8.5, 1.5);
        cast(h, p, "(spell :mana 200 (beam :radius 0.3 :life 2 :every 0.1 (on-hit (damage target 1))))");
        h.runAfterDelay(10, () -> {
            h.assertTrue(ahead.getHealth() < ahead.getMaxHealth(), "the beam did not reach the husk ahead");
            h.assertTrue(side.getHealth() == side.getMaxHealth(), "the beam touched the husk to the side before turning");
            p.setYRot(-90); // face +X
            p.setYHeadRot(-90);
        });
        h.succeedWhen(() -> {
            h.assertTrue(side.getHealth() < side.getMaxHealth(), "the beam did not follow the caster's turn");
        });
    }

    private static void chainLightning(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var a = mob(h, EntityType.HUSK, 4.5, 5.5);
        var b = mob(h, EntityType.HUSK, 7.5, 8.5);
        var c = mob(h, EntityType.HUSK, 1.5, 11.5);
        SpellInstance inst = cast(h, p, Examples.ALL.get("chain-lightning"));
        h.succeedWhen(() -> {
            h.assertTrue(inst.finished(), "spell still running");
            for (var e : List.of(a, b, c)) h.assertTrue(e.getHealth() < e.getMaxHealth(), "a husk was skipped by the chain");
            h.assertTrue(inst.affected.size() == 3, "expected exactly 3 targets, got " + inst.affected.size());
        });
    }

    private static void executeProof(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var husk = mob(h, EntityType.HUSK, 4.5, 8.5);
        husk.setHealth(5); // 25% ≤ 30%
        husk.igniteForSeconds(10);
        SpellInstance inst = cast(h, p, Examples.ALL.get("execute-proof"));
        // ray 3 + if 0.5 + execute 4×20×0.3×0.75 = 18
        h.succeedWhen(() -> {
            h.assertTrue(!husk.isAlive(), "husk survived");
            h.assertTrue(Math.abs(inst.mana - (60 - 21.5)) < 0.01, "expected 38.5 mana left, got " + inst.mana);
        });
    }

    private static void executeBrute(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var golem = mob(h, EntityType.IRON_GOLEM, 4.5, 8.5);
        SpellInstance inst = cast(h, p, Examples.ALL.get("execute-brute"));
        h.succeedWhen(() -> {
            h.assertTrue(golem.isAlive(), "unproven execute of a 100hp golem should cost 400 > 120 injected");
            h.assertTrue(inst.dead && inst.endReason().contains("魔力耗尽"), "expected mana exhaustion, got " + inst.endReason());
        });
    }

    private static void paradox(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var husk = mob(h, EntityType.HUSK, 4.5, 8.5);
        husk.setHealth(6);
        SpellInstance inst = cast(h, p, Examples.ALL.get("paradox"));
        h.succeedWhen(() -> {
            h.assertTrue(husk.isAlive(), "execute should not happen after the premise was broken");
            h.assertTrue(inst.dead && inst.endReason().contains("悖论"), "expected paradox, got " + inst.endReason());
        });
    }

    private static void recursion(GameTestHelper h) {
        ServerPlayer p = caster(h);
        SpellInstance inst = cast(h, p, "(spell :mana 1000 (recurse))");
        h.succeedWhen(() -> h.assertTrue(inst.dead && inst.endReason().contains("递归"), "expected recursion limit, got " + inst.endReason()));
    }

    private static void runaway(GameTestHelper h) {
        ServerPlayer p = caster(h);
        SpellInstance inst = cast(h, p, """
                (spell :mana 1000
                  (projectile :speed 0.05 :life 10
                    (on-tick (projectile :speed 0.05 :life 10))))""");
        h.succeedWhen(() -> h.assertTrue(inst.dead && inst.endReason().contains("形体"), "expected object limit, got " + inst.endReason()));
    }

    private static void attackPassive(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var husk = mob(h, EntityType.HUSK, 4.5, 3.5);
        // Passives work from pages hung on the held wand.
        ItemStack wand = new ItemStack(LogosMod.WAND.get());
        wand.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(
                List.of(dev.logos.item.SpellPages.inscribed("(spell :name \"t\" :trigger attack :mana 10 (mark target #hit))", null))));
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, wand);
        husk.hurtServer(h.getLevel(), husk.damageSources().playerAttack(p), 1);
        h.succeedWhen(() -> h.assertTrue(dev.logos.runtime.StatusData.stacks(husk, new dev.logos.lang.Status("#hit")) == 1,
                "attack passive did not run exactly once"));
    }

    /** Recursion and repeat limits come from the config file and take effect immediately. */
    private static void configLimits(GameTestHelper h) {
        int oldRecursion = dev.logos.LogosConfig.MAX_RECURSION.getAsInt();
        int oldRepeat = dev.logos.LogosConfig.MAX_REPEAT.getAsInt();
        try {
            dev.logos.LogosConfig.MAX_RECURSION.set(40);
            dev.logos.LogosConfig.MAX_REPEAT.set(500);
            ServerPlayer p = caster(h);
            PlayerMagic.of(p).maxMana = PlayerMagic.of(p).mana = 1e12;
            // A repeat above the default 32 now compiles and runs every iteration.
            SpellInstance loop = cast(h, p, "(spell :mana 10000 (repeat 200 (mark caster #tick)))");
            h.assertTrue(dev.logos.runtime.StatusData.stacks(p, new dev.logos.lang.Status("#tick")) == 200,
                    "expected 200 iterations, got " + dev.logos.runtime.StatusData.stacks(p, new dev.logos.lang.Status("#tick")) + " (" + loop.endReason() + ")");
            SpellInstance rec = cast(h, p, "(spell :mana 10000 (recurse))");
            h.assertTrue(rec.dead && rec.endReason().contains("40"), "expected the recursion limit of 40, got " + rec.endReason());
            // And the largest integer can be injected.
            PlayerMagic.of(p).mana = Integer.MAX_VALUE;
            SpellInstance big = cast(h, p, "(spell :mana 2147483647 (heal caster 1))");
            h.assertTrue(big.injected == Integer.MAX_VALUE, "injected " + big.injected);
        } finally {
            dev.logos.LogosConfig.MAX_RECURSION.set(oldRecursion);
            dev.logos.LogosConfig.MAX_REPEAT.set(oldRepeat);
        }
        h.succeed();
    }

    private static void marks(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var a = mob(h, EntityType.HUSK, 4.5, 6.5);
        var b = mob(h, EntityType.HUSK, 6.5, 12.5);
        b.setNoAi(false); // NoAi mobs ignore velocity, so pull could never move them
        // Spell 1 writes a mark; spell 2 (a different invocation) finds every marked entity and pulls them.
        SpellInstance s1 = cast(h, p, "(spell :mana 20 (each (enemies :radius 30) (mark target #link)))");
        double markedB = dev.logos.runtime.StatusData.stacks(b, new dev.logos.lang.Status("#link"));
        Vec3 before = b.position();
        cast(h, p, "(spell :mana 40 (each (marked #link :radius 30) (pull target 2 :to (pos-of caster)) (unmark target #link)))");
        h.succeedWhen(() -> {
            // Entities do not tick in the headless test world, so assert the impulse rather than displacement.
            h.assertTrue(b.position().distanceTo(before) > 0.5 || b.getDeltaMovement().length() > 0.5, "marked husk was not pulled; s1 affected=" + s1.affected.size() + " end=" + s1.endReason() + " markedB=" + markedB + " moved=" + b.position().distanceTo(before) + " noAi=" + b.isNoAi());
            h.assertTrue(dev.logos.runtime.StatusData.stacks(a, new dev.logos.lang.Status("#link")) == 0, "mark was not consumed");
        });
    }
}
