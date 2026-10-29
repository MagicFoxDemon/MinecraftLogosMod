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
            "example_npc_heal", "example_npc_group_heal", "rune_species_filter", "example_prismatic_volley_budget",
            "fireball_hits_and_bursts", "chain_lightning_bounces", "execute_with_proof_is_cheap", "execute_without_proof_starves",
            "paradox_backfires", "recursion_is_bounded", "runaway_spawning_is_bounded", "attack_passive_fires", "marks_link_spells", "graph_roundtrip", "graph_rejects_bad_wires", "graph_errors_point_at_nodes", "context_wire_meanings", "config_limits_apply",
            "world_toggle_state", "world_resize", "world_use_block", "world_use_block_item", "world_grow_and_decay", "world_players_flag", "world_nearest_player", "end_all_others", "end_all_own", "form_ops_projectile", "form_ops_beam", "form_ops_field", "form_ops_spells", "value_random", "value_mix", "value_round_clamp", "value_wave", "value_choose", "value_spot_around", "value_spot_in", "world_switch", "world_form_refs", "world_blink_target", "world_items_magnet", "world_nether_step", "world_time_skip", "world_weather_global", "world_weather_area", "world_lightning",
            "world_wand_casts_selected_page", "world_wood_wands_craft", "world_crystal_page_needs_adhesive", "world_echo_page_upgrade_keeps_spell", "world_sage_page_upgrade_keeps_spell", "world_transmuted_page_upgrade_keeps_spell", "world_chalk_page_upgrade_keeps_spell", "world_firmament_page_upgrade_keeps_spell",
            "wand_charges_then_casts", "wand_early_release_refunds", "wand_refuses_what_it_cannot_fill", "wand_switching_spell_cancels", "wand_page_caps_charge", "wand_charge_looks", "world_table_inscribes", "beam_follows_gaze", "field_heals_caster", "infuse_gives_mana", "field_spares_caster_without_players", "field_shields_caster_on_enter", "world_filter_kinds",
            "construct_stops_movement", "construct_sigil", "construct_one_way_arrows", "construct_shields_explosion", "construct_platform_stands", "construct_stops_other_spells", "construct_wears_down_and_breaks", "construct_void_pierces_weak_wall",
            "construct_pass_ignites_copies", "construct_fire_wall", "construct_carries_rider", "construct_follow_turns", "world_anchors",
            "familiar_unlock_summon", "familiar_loyal_targeting", "familiar_friendly_fire_credit", "familiar_drops", "familiar_creeper_no_grief",
            "familiar_dragon_circles", "familiar_dragon_breath_spares_itself", "familiar_dragon_throne", "familiar_dragon_ridden_altitude", "familiar_dragon_ridden_altitude_forward", "familiar_dragon_ridden_hovers_then_flies", "familiar_dragon_rider_fireball", "familiar_dragon_rider_breath",
            "passive_codex_without_wand", "rune_disarm_order", "rune_equip_order", "rune_equip_materials", "rune_equip_groups_players", "rune_potion_effects", "rune_shield_marks_ward", "potion_burst_polarity", "rune_group_radius_cost", "rune_time_stop_mob", "rune_time_stop_player",
            "page_capacity_caps_injection", "page_immolation_burns", "free_mana_from_leftovers", "free_mana_gathered", "copy_table_copies", "page_source_command", "alchemy_brews_raw_mash", "alchemy_crafts_gunpowder", "alchemy_crafts_maintenance_kit", "alchemy_crafts_table", "alchemy_bottles_drinkable",
            "process_ferments", "process_designates_and_routes", "process_dissolves_water_bottles", "process_dissolves_from_tank", "process_chains", "process_needs_mana", "process_shows_working", "process_cards_keep_and_remove", "process_pours_bottles_first", "process_says_what_is_missing", "alchemy_table_switch", "powder_settles", "powder_is_eaten", "powder_dissolves_back", "brews_from_base", "brews_from_water", "process_designates_exactly", "process_products_exact", "status_follows_designations", "status_says_outputs_full", "process_calcines", "calcine_needs_blast_furnace", "redstone_distils_mercury", "tria_prima_dissolve", "philosophers_mercury_settles", "conjunction_joins_powders", "purify_cleans_tainted_shards", "purify_washes_like_a_cauldron", "mercury_is_poison", "blackstone_calcines_to_caput_mortuum", "sublimation_needs_lit_beacon", "gold_dissolves_into_amalgam", "chalk_and_rebis_calcine_white_stone", "firmament_liquid_dissolves", "firmament_liquid_settles", "caput_mortuum_rots_into_soul_humus", "soul_humus_washes_into_chalk", "echo_shard_dissolves_into_tincture", "sculk_veins_dissolve_into_tincture", "caput_mortuum_rots_into_raven_head", "soul_humus_drops_itself", "cultivation_jar_grows_crops", "cultivation_jar_medium", "cultivation_jar_grows_metal", "cultivation_jar_grows_fruiting_plants", "cultivation_jar_vital_mash", "reddening_runs_the_seven_processes", "infuse_block_fills_table", "field_settles_into_steps", "collectors_share_ambient", "spreader_feeds_table_through_collector", "storage_tank_stores_and_supplies", "storage_tank_condenses_and_keeps_mana", "caput_mortuum_insulates", "caput_mortuum_block_crafts", "survey_shows_field_and_flow", "free_mana_seeps_into_field",
            "emitter_pulse_casts_once", "emitter_full_casts_and_pays_extra", "emitter_spells_leave_through_itself", "emitter_edit_swaps_the_invocation", "offset_follows_the_facing", "dividers_measure_from_the_emitter",
            "alchemy_table_halves", "alchemy_table_automation", "alchemy_table_tank", "alchemy_table_mana", "alchemy_table_bottles", "alchemy_table_free_mana",
            "spell_modifier_element_power", "spell_modifier_resistance", "spell_modifier_fire_is_vanilla_fire", "spell_modifier_arcane_damage", "spell_modifier_creature_life_damage",
            "spell_modifier_node_cost", "spell_modifier_channel_speed", "spell_modifier_mana_regeneration", "spell_modifier_heal_power", "spell_modifier_vulnerability",
            "buff_potions_brew", "buff_potions_dissolve", "buff_potions_effects", "element_effects_every_element", "herbs_grow_in_jars",
            "familiar_ride_ground", "familiar_dragon_two_seats", "familiar_age_frozen", "familiar_names_dismiss", "familiar_gather", "familiar_warden_fights", "familiar_warden_vs_warden", "familiar_sic", "familiar_stance_hunts",
            "lifeform_spawn_egg_levels", "lifeform_spawn_egg_dispenser", "lifeform_spawn_egg_disabled", "lifeform_spawn_egg_water",
            "lifeform_summon_rules", "lifeform_casts_attack_page", "lifeform_dormant_and_fed", "lifeform_falls_when_dry", "lifeform_executes_itself",
            "lifeform_hovers", "lifeform_combo", "lifeform_charges_its_cast", "lifeform_charge_returns_when_target_gone", "lifeform_heals_master", "lifeform_heal_projectile_hits_master", "lifeform_heal_ray_hits_master", "lifeform_heal_beam_hits_master", "lifeform_unaimed_beam_heals_master", "lifeform_unaimed_beam_strikes_foe", "lifeform_declared_heal_spares_master", "lifeform_forages", "lifeform_role",
            "colossus_spawn_egg_player", "colossus_spawn_egg_dispenser", "colossus_spawn_egg_disabled", "colossus_spawn_egg_water",
            "colossus_armour", "colossus_execute_drains", "colossus_heavy_and_finish", "colossus_grab_finishes", "colossus_grab_slams",
            "colossus_grabs_big", "colossus_grabs_from_reach", "colossus_defends_itself", "colossus_orders_before_self_defense", "colossus_stomps_small_foes", "colossus_grabs_rider", "colossus_lets_go_when_killed", "colossus_punches_crowd", "colossus_punches_dragon", "colossus_cannon", "colossus_cannon_unbroken", "colossus_cannon_strikes_at_once", "colossus_frenzy", "colossus_board", "colossus_drive_controls", "colossus_carries_master_still", "colossus_flower_cooldown", "colossus_rider_sheltered", "colossus_jumps_off",
            "leech_hunts_richest", "leech_clings_and_drains", "leech_knocked_off", "leech_drains_familiar_health", "leech_splits", "leech_offspring_do_not_split", "leech_bane_and_death", "leech_familiar_obeys",
            "jar_crop_empty_substrates", "jar_crop_tagged_substrates",
            "example_prismatic_barrage_budget");

    private LogosGameTests() {}

    public static void register(IEventBus modBus) {
        FUNCTIONS.register("example_npc_heal", () -> HealingExampleGameTests::single);
        FUNCTIONS.register("example_npc_group_heal", () -> HealingExampleGameTests::group);
        FUNCTIONS.register("rune_species_filter", () -> HealingExampleGameTests::species);
        FUNCTIONS.register("example_prismatic_volley_budget", () -> PrismaticVolleyGameTests::budget);
        FUNCTIONS.register("projectile_impact_expiry", () -> ProjectileImpactGameTests::expiry);
        FUNCTIONS.register("projectile_impact_wall", () -> ProjectileImpactGameTests::block);
        FUNCTIONS.register("projectile_impact_piercing", () -> ProjectileImpactGameTests::piercing);
        FUNCTIONS.register("fireball_hits_and_bursts", () -> LogosGameTests::fireball);
        FUNCTIONS.register("beam_follows_gaze", () -> LogosGameTests::beamFollowsGaze);
        FUNCTIONS.register("field_heals_caster", () -> LogosGameTests::fieldHealsCaster);
        FUNCTIONS.register("infuse_gives_mana", () -> LogosGameTests::infuseGivesMana);
        FUNCTIONS.register("field_spares_caster_without_players", () -> LogosGameTests::fieldSparesCasterWithoutPlayers);
        FUNCTIONS.register("field_shields_caster_on_enter", () -> LogosGameTests::fieldShieldsCasterOnEnter);
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
        FUNCTIONS.register("context_wire_meanings", () -> ContextSourceGameTests::meanings);
        FUNCTIONS.register("config_limits_apply", () -> LogosGameTests::configLimits);
        FUNCTIONS.register("world_toggle_state", () -> WorldGameTests::toggleState);
        FUNCTIONS.register("world_resize", () -> WorldGameTests::resize);
        FUNCTIONS.register("world_use_block", () -> WorldGameTests::useBlock);
        FUNCTIONS.register("world_use_block_item", () -> WorldGameTests::useBlockWithItem);
        FUNCTIONS.register("world_grow_and_decay", () -> WorldGameTests::growAndDecay);
        FUNCTIONS.register("world_players_flag", () -> WorldGameTests::playersFlag);
        FUNCTIONS.register("value_random", () -> ValueGameTests::random);
        FUNCTIONS.register("value_mix", () -> ValueGameTests::mix);
        FUNCTIONS.register("value_round_clamp", () -> ValueGameTests::roundClamp);
        FUNCTIONS.register("value_wave", () -> ValueGameTests::wave);
        FUNCTIONS.register("value_choose", () -> ValueGameTests::choose);
        FUNCTIONS.register("value_spot_around", () -> ValueGameTests::spotAround);
        FUNCTIONS.register("value_spot_in", () -> ValueGameTests::spotIn);
        FUNCTIONS.register("world_switch", () -> WorldGameTests::switchBranches);
        FUNCTIONS.register("world_form_refs", () -> WorldGameTests::formRefs);
        FUNCTIONS.register("world_nearest_player", () -> WorldGameTests::nearestPlayer);
        FUNCTIONS.register("end_all_others", () -> EndAllGameTests::othersNeedPlayers);
        FUNCTIONS.register("end_all_own", () -> EndAllGameTests::ownAndTooStrong);
        FUNCTIONS.register("form_ops_projectile", () -> FormOpsGameTests::projectile);
        FUNCTIONS.register("form_ops_beam", () -> FormOpsGameTests::beam);
        FUNCTIONS.register("form_ops_field", () -> FormOpsGameTests::field);
        FUNCTIONS.register("form_ops_spells", () -> FormOpsGameTests::throughSpells);
        FUNCTIONS.register("world_items_magnet", () -> WorldGameTests::itemsMagnet);
        FUNCTIONS.register("world_filter_kinds", () -> WorldGameTests::filterKinds);
        FUNCTIONS.register("world_anchors", () -> WorldGameTests::anchors);
        FUNCTIONS.register("world_blink_target", () -> WorldGameTests::blinkTarget);
        FUNCTIONS.register("familiar_unlock_summon", () -> FamiliarGameTests::unlockAndSummon);
        FUNCTIONS.register("familiar_loyal_targeting", () -> FamiliarGameTests::loyalTargeting);
        FUNCTIONS.register("familiar_friendly_fire_credit", () -> FamiliarGameTests::friendlyFireAndCredit);
        FUNCTIONS.register("familiar_drops", () -> FamiliarGameTests::dropsOnlyPickedUp);
        FUNCTIONS.register("familiar_creeper_no_grief", () -> FamiliarGameTests::creeperBreaksNothing);
        FUNCTIONS.register("familiar_dragon_circles", () -> FamiliarGameTests::dragonCirclesMaster);
        FUNCTIONS.register("familiar_dragon_breath_spares_itself", () -> FamiliarGameTests::dragonBreathSparesItself);
        FUNCTIONS.register("familiar_dragon_throne", () -> FamiliarGameTests::dragonThroneRules);
        FUNCTIONS.register("familiar_dragon_ridden_altitude", () -> FamiliarGameTests::dragonRiddenAltitude);
        FUNCTIONS.register("familiar_dragon_ridden_altitude_forward", () -> FamiliarGameTests::dragonRiddenAltitudeForward);
        FUNCTIONS.register("familiar_dragon_ridden_hovers_then_flies", () -> FamiliarGameTests::dragonRiddenHoversThenFlies);
        FUNCTIONS.register("familiar_dragon_rider_fireball", () -> FamiliarGameTests::dragonRiderFireball);
        FUNCTIONS.register("familiar_dragon_rider_breath", () -> FamiliarGameTests::dragonRiderBreath);
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
        FUNCTIONS.register("lifeform_charges_its_cast", () -> LifeformGameTests::chargesItsCast);
        FUNCTIONS.register("lifeform_charge_returns_when_target_gone", () -> LifeformGameTests::chargeReturnsWhenTargetGone);
        FUNCTIONS.register("lifeform_heals_master", () -> LifeformGameTests::healsMaster);
        FUNCTIONS.register("lifeform_heal_projectile_hits_master", () -> LifeformGameTests::healProjectileHitsMaster);
        FUNCTIONS.register("lifeform_heal_ray_hits_master", () -> LifeformGameTests::healRayHitsMaster);
        FUNCTIONS.register("lifeform_heal_beam_hits_master", () -> LifeformGameTests::healBeamHitsMaster);
        FUNCTIONS.register("lifeform_unaimed_beam_heals_master", () -> LifeformGameTests::unaimedBeamHealsMaster);
        FUNCTIONS.register("lifeform_unaimed_beam_strikes_foe", () -> LifeformGameTests::unaimedBeamStrikesFoe);
        FUNCTIONS.register("lifeform_declared_heal_spares_master", () -> LifeformGameTests::declaredHealStillSparesMaster);
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
        FUNCTIONS.register("construct_sigil", () -> ConstructGameTests::sigilOnlyOnShields);
        FUNCTIONS.register("construct_one_way_arrows", () -> ConstructGameTests::oneWayStopsArrows);
        FUNCTIONS.register("construct_shields_explosion", () -> ConstructGameTests::wallShieldsExplosion);
        FUNCTIONS.register("construct_platform_stands", () -> ConstructGameTests::platformHoldsAndStands);
        FUNCTIONS.register("construct_stops_other_spells", () -> ConstructGameTests::wallStopsOtherSpells);
        FUNCTIONS.register("construct_wears_down_and_breaks", () -> ConstructGameTests::wallWearsDownAndBreaks);
        FUNCTIONS.register("construct_void_pierces_weak_wall", () -> ConstructGameTests::voidPiercesWeakWall);
        FUNCTIONS.register("construct_pass_ignites_copies", () -> ConstructGameTests::passIgnitesAndCopies);
        FUNCTIONS.register("construct_fire_wall", () -> ConstructGameTests::fireWallIgnitesWalkers);
        FUNCTIONS.register("construct_carries_rider", () -> ConstructGameTests::platformCarriesRider);
        FUNCTIONS.register("construct_follow_turns", () -> ConstructGameTests::followWallTurns);
        FUNCTIONS.register("world_nether_step", () -> WorldGameTests::netherStep);
        FUNCTIONS.register("world_time_skip", () -> WorldGameTests::timeSkip);
        FUNCTIONS.register("world_weather_global", () -> WorldGameTests::worldWeather);
        FUNCTIONS.register("world_weather_area", () -> WorldGameTests::areaWeather);
        FUNCTIONS.register("world_lightning", () -> WorldGameTests::lightningStrike);
        FUNCTIONS.register("world_wand_casts_selected_page", () -> WorldGameTests::wandCastsSelectedPage);
        FUNCTIONS.register("world_wood_wands_craft", () -> WorldGameTests::woodWandsCraft);
        FUNCTIONS.register("world_crystal_page_needs_adhesive", () -> WorldGameTests::crystalPageNeedsAdhesive);
        FUNCTIONS.register("world_echo_page_upgrade_keeps_spell", () -> WorldGameTests::echoPageUpgradeKeepsSpell);
        FUNCTIONS.register("world_transmuted_page_upgrade_keeps_spell", () -> WorldGameTests::transmutedPageUpgradeKeepsSpell);
        FUNCTIONS.register("world_chalk_page_upgrade_keeps_spell", () -> WorldGameTests::chalkPageUpgradeKeepsSpell);
        FUNCTIONS.register("world_firmament_page_upgrade_keeps_spell", () -> WorldGameTests::firmamentPageUpgradeKeepsSpell);
        FUNCTIONS.register("world_sage_page_upgrade_keeps_spell", () -> WorldGameTests::sagePageUpgradeKeepsSpell);
        FUNCTIONS.register("wand_charges_then_casts", () -> ChargingGameTests::chargesThenCasts);
        FUNCTIONS.register("wand_early_release_refunds", () -> ChargingGameTests::earlyReleaseRefunds);
        FUNCTIONS.register("wand_refuses_what_it_cannot_fill", () -> ChargingGameTests::refusesWhatItCannotFill);
        FUNCTIONS.register("wand_switching_spell_cancels", () -> ChargingGameTests::switchingSpellCancels);
        FUNCTIONS.register("wand_page_caps_charge", () -> ChargingGameTests::pageCapsCharge);
        FUNCTIONS.register("wand_charge_looks", () -> ChargingGameTests::chargeLooks);
        FUNCTIONS.register("world_table_inscribes", () -> WorldGameTests::tableInscribes);
        FUNCTIONS.register("passive_codex_without_wand", () -> RuneGameTests::codexPassiveWithoutWand);
        FUNCTIONS.register("rune_disarm_order", () -> RuneGameTests::disarmOrder);
        FUNCTIONS.register("rune_equip_order", () -> RuneGameTests::equipOrder);
        FUNCTIONS.register("rune_equip_materials", () -> RuneGameTests::equipMaterials);
        FUNCTIONS.register("rune_equip_groups_players", () -> RuneGameTests::equipGroupsAndPlayers);
        FUNCTIONS.register("rune_potion_effects", () -> RuneGameTests::potionEffects);
        FUNCTIONS.register("rune_shield_marks_ward", () -> RuneGameTests::shieldMarksWard);
        FUNCTIONS.register("potion_burst_polarity", () -> RuneGameTests::potionBurstPolarity);
        FUNCTIONS.register("rune_group_radius_cost", () -> RuneGameTests::groupRadiusCost);
        FUNCTIONS.register("rune_time_stop_mob", () -> RuneGameTests::timeStopMob);
        FUNCTIONS.register("rune_time_stop_player", () -> RuneGameTests::timeStopPlayer);
        FUNCTIONS.register("page_capacity_caps_injection", () -> PageGameTests::capacityCapsInjection);
        FUNCTIONS.register("page_immolation_burns", () -> PageGameTests::immolationBurnsPage);
        FUNCTIONS.register("free_mana_from_leftovers", () -> PageGameTests::freeManaFromLeftovers);
        FUNCTIONS.register("free_mana_gathered", () -> PageGameTests::freeManaGathered);
        FUNCTIONS.register("copy_table_copies", () -> PageGameTests::copyTableCopies);
        FUNCTIONS.register("page_source_command", () -> PageGameTests::sourceCommandReadsPage);
        FUNCTIONS.register("alchemy_brews_raw_mash", () -> AlchemyGameTests::brewsRawMash);
        FUNCTIONS.register("alchemy_crafts_gunpowder", () -> AlchemyGameTests::craftsGunpowder);
        FUNCTIONS.register("alchemy_crafts_maintenance_kit", () -> AlchemyGameTests::craftsMaintenanceKit);
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
        FUNCTIONS.register("process_calcines", () -> AlchemyGameTests::processCalcines);
        FUNCTIONS.register("calcine_needs_blast_furnace", () -> AlchemyGameTests::calcineNeedsBlastFurnace);
        FUNCTIONS.register("redstone_distils_mercury", () -> AlchemyGameTests::redstoneDistilsMercury);
        FUNCTIONS.register("tria_prima_dissolve", () -> AlchemyGameTests::triaPrimaDissolveIntoPhilosophersMercury);
        FUNCTIONS.register("philosophers_mercury_settles", () -> AlchemyGameTests::philosophersMercurySettlesIntoRebis);
        FUNCTIONS.register("conjunction_joins_powders", () -> AlchemyGameTests::conjunctionJoinsPowders);
        FUNCTIONS.register("purify_cleans_tainted_shards", () -> AlchemyGameTests::purifyCleansTaintedShards);
        FUNCTIONS.register("purify_washes_like_a_cauldron", () -> AlchemyGameTests::purifyWashesLikeACauldron);
        FUNCTIONS.register("mercury_is_poison", () -> AlchemyGameTests::mercuryIsPoison);
        FUNCTIONS.register("blackstone_calcines_to_caput_mortuum", () -> AlchemyGameTests::blackstoneCalcinesToCaputMortuum);
        FUNCTIONS.register("sublimation_needs_lit_beacon", () -> AlchemyGameTests::sublimationNeedsLitBeacon);
        FUNCTIONS.register("gold_dissolves_into_amalgam", () -> AlchemyGameTests::goldDissolvesIntoAmalgam);
        FUNCTIONS.register("chalk_and_rebis_calcine_white_stone", () -> AlchemyGameTests::chalkAndRebisCalcineWhiteStone);
        FUNCTIONS.register("firmament_liquid_dissolves", () -> AlchemyGameTests::firmamentLiquidDissolves);
        FUNCTIONS.register("firmament_liquid_settles", () -> AlchemyGameTests::firmamentLiquidSettles);
        FUNCTIONS.register("caput_mortuum_rots_into_soul_humus", () -> AlchemyGameTests::caputMortuumRotsIntoSoulHumus);
        FUNCTIONS.register("soul_humus_washes_into_chalk", () -> AlchemyGameTests::soulHumusWashesIntoChalk);
        FUNCTIONS.register("echo_shard_dissolves_into_tincture", () -> AlchemyGameTests::echoShardDissolvesIntoTincture);
        FUNCTIONS.register("sculk_veins_dissolve_into_tincture", () -> AlchemyGameTests::sculkVeinsDissolveIntoTincture);
        FUNCTIONS.register("caput_mortuum_rots_into_raven_head", () -> AlchemyGameTests::caputMortuumRotsIntoRavenHead);
        FUNCTIONS.register("soul_humus_drops_itself", () -> AlchemyGameTests::soulHumusDropsItself);
        FUNCTIONS.register("cultivation_jar_grows_crops", () -> AlchemyGameTests::cultivationJarGrowsCrops);
        FUNCTIONS.register("cultivation_jar_medium", () -> AlchemyGameTests::cultivationJarMedium);
        FUNCTIONS.register("cultivation_jar_grows_metal", () -> AlchemyGameTests::cultivationJarGrowsMetal);
        FUNCTIONS.register("cultivation_jar_grows_fruiting_plants", () -> AlchemyGameTests::cultivationJarGrowsFruitingPlants);
        FUNCTIONS.register("cultivation_jar_vital_mash", () -> AlchemyGameTests::cultivationJarVitalMash);
        FUNCTIONS.register("reddening_runs_the_seven_processes", () -> AlchemyGameTests::reddeningRunsTheSevenProcesses);
        FUNCTIONS.register("infuse_block_fills_table", () -> AlchemyGameTests::infuseBlockFillsTable);
        FUNCTIONS.register("field_settles_into_steps", () -> ManaFieldGameTests::fieldSettlesIntoSteps);
        FUNCTIONS.register("collectors_share_ambient", () -> ManaFieldGameTests::collectorsShareAmbient);
        FUNCTIONS.register("spreader_feeds_table_through_collector", () -> ManaFieldGameTests::spreaderFeedsTableThroughCollector);
        FUNCTIONS.register("storage_tank_stores_and_supplies", () -> ManaFieldGameTests::storageTankStoresAndSupplies);
        FUNCTIONS.register("storage_tank_condenses_and_keeps_mana", () -> ManaFieldGameTests::storageTankCondensesAndKeepsMana);
        FUNCTIONS.register("caput_mortuum_insulates", () -> ManaFieldGameTests::caputMortuumInsulates);
        FUNCTIONS.register("caput_mortuum_block_crafts", () -> ManaFieldGameTests::caputMortuumBlockCrafts);
        FUNCTIONS.register("survey_shows_field_and_flow", () -> ManaFieldGameTests::surveyShowsFieldAndFlow);
        FUNCTIONS.register("free_mana_seeps_into_field", () -> ManaFieldGameTests::freeManaSeepsIntoField);
        FUNCTIONS.register("emitter_pulse_casts_once", () -> SpellEmitterGameTests::pulseCastsOnce);
        FUNCTIONS.register("emitter_full_casts_and_pays_extra", () -> SpellEmitterGameTests::fullCastsAndPaysExtra);
        FUNCTIONS.register("emitter_spells_leave_through_itself", () -> SpellEmitterGameTests::spellsLeaveThroughItself);
        FUNCTIONS.register("emitter_edit_swaps_the_invocation", () -> SpellEmitterGameTests::editSwapsTheInvocation);
        FUNCTIONS.register("offset_follows_the_facing", () -> OffsetGameTests::offsetFollowsTheFacing);
        FUNCTIONS.register("dividers_measure_from_the_emitter", () -> OffsetGameTests::dividersMeasureFromTheEmitter);
        FUNCTIONS.register("alchemy_table_halves", () -> AlchemyGameTests::tableHalvesBreakTogether);
        FUNCTIONS.register("alchemy_table_automation", () -> AlchemyGameTests::tableAutomationIsOneWay);
        FUNCTIONS.register("alchemy_table_tank", () -> AlchemyGameTests::tableTankHoldsOneFluid);
        FUNCTIONS.register("alchemy_table_mana", () -> AlchemyGameTests::tableManaSources);
        FUNCTIONS.register("alchemy_table_bottles", () -> AlchemyGameTests::tableBottles);
        FUNCTIONS.register("alchemy_table_free_mana", () -> AlchemyGameTests::tableGathersFreeMana);
        FUNCTIONS.register("spell_modifier_element_power", () -> SpellModifierGameTests::elementPowerBoostsItsElement);
        FUNCTIONS.register("spell_modifier_heal_power", () -> SpellModifierGameTests::healPowerScalesHealing);
        FUNCTIONS.register("spell_modifier_vulnerability", () -> SpellModifierGameTests::vulnerabilityAddsToDamageTaken);
        FUNCTIONS.register("spell_modifier_resistance", () -> SpellModifierGameTests::resistanceTurnsAsideItsElement);
        FUNCTIONS.register("spell_modifier_fire_is_vanilla_fire", () -> SpellModifierGameTests::fireIsVanillaFire);
        FUNCTIONS.register("spell_modifier_arcane_damage", () -> SpellModifierGameTests::arcaneDamageHasItsOwnPower);
        FUNCTIONS.register("spell_modifier_creature_life_damage", () -> SpellModifierGameTests::creatureLifeDamage);
        FUNCTIONS.register("spell_modifier_node_cost", () -> SpellModifierGameTests::nodeCostScalesItsCategory);
        FUNCTIONS.register("spell_modifier_channel_speed", () -> SpellModifierGameTests::channelSpeedHalvesCharge);
        FUNCTIONS.register("spell_modifier_mana_regeneration", () -> SpellModifierGameTests::manaRegenerationAndRestore);
        FUNCTIONS.register("buff_potions_brew", () -> AlchemyGameTests::buffPotionsBrew);
        FUNCTIONS.register("buff_potions_dissolve", () -> AlchemyGameTests::buffPotionsDissolve);
        FUNCTIONS.register("buff_potions_effects", () -> AlchemyGameTests::buffPotionsEffects);
        FUNCTIONS.register("element_effects_every_element", () -> AlchemyGameTests::elementEffectsForEveryElement);
        FUNCTIONS.register("herbs_grow_in_jars", () -> AlchemyGameTests::herbsGrowInJars);
        FUNCTIONS.register("example_prismatic_barrage_budget", () -> PrismaticBarrageGameTests::budget);
        FUNCTIONS.register("jar_crop_empty_substrates", () -> JarCropGameTests::emptySubstrates);
        FUNCTIONS.register("jar_crop_tagged_substrates", () -> JarCropGameTests::taggedSubstrates);
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
            // Fire is vanilla fire, so the husk's 2 armour turns aside a little of it.
            h.assertTrue(husk.getHealth() <= husk.getMaxHealth() - 6.8f, "expected on-hit (5) + on-expire burst (2), health=" + husk.getHealth());
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

    /** A field written to affect players heals its own caster when its selector takes players in. */
    private static void fieldHealsCaster(GameTestHelper h) {
        ServerPlayer p = caster(h);
        p.getFoodData().setFoodLevel(10); // too hungry to regenerate on its own
        p.setHealth(6);
        cast(h, p, "(spell :players true :mana 200 (field :life 2 :every 0.25 (on-tick (heal (entities :filter (kind players)) 2))))");
        h.succeedWhen(() -> h.assertTrue(p.getHealth() >= 10, "the field should heal its caster: " + p.getHealth()));
    }

    /** 渡魔 gives a player mana from the spell at 1.1 a point; a creature that holds no mana takes none, and costs nothing. */
    private static void infuseGivesMana(GameTestHelper h) {
        ServerPlayer p = caster(h);
        mob(h, EntityType.COW, 4.5, 4.5);
        SpellInstance own = cast(h, p, "(spell :mana 50 (infuse caster 20))");
        h.assertTrue(Math.abs(own.mana - 28) < 1e-6, "giving 20 should cost 22 of 50, left " + own.mana);
        h.assertTrue(Math.abs(PlayerMagic.of(p).mana - (10000 - 50 + 20)) < 1e-6, "the caster holds " + PlayerMagic.of(p).mana);
        SpellInstance cow = cast(h, p, "(spell :mana 50 (infuse (nearest :radius 8) 20))");
        h.assertTrue(cow.mana > 48, "a cow holds no mana, yet infusing it cost " + (50 - cow.mana));
        h.succeed();
    }

    /** Without :players, a field's selectors still leave its caster out. */
    private static void fieldSparesCasterWithoutPlayers(GameTestHelper h) {
        ServerPlayer p = caster(h);
        p.getFoodData().setFoodLevel(10);
        p.setHealth(6);
        SpellInstance inst = cast(h, p, "(spell :mana 200 (field :life 1 :every 0.25 (on-tick (heal (entities) 2))))");
        h.succeedWhen(() -> {
            h.assertTrue(inst.finished(), "spell still running");
            h.assertTrue(p.getHealth() == 6, "the field should not touch its caster without :players: " + p.getHealth());
        });
    }

    /** A warding field greets its caster on entry when the spell affects players. */
    private static void fieldShieldsCasterOnEnter(GameTestHelper h) {
        ServerPlayer p = caster(h);
        cast(h, p, "(spell :players true :mana 200 (field :life 1 (on-enter (shield target 4))))");
        h.succeedWhen(() -> h.assertTrue(p.getAbsorptionAmount() >= 4, "the field should shield its caster: " + p.getAbsorptionAmount()));
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
