package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.ServerContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Examples;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Real-client check that Photon renders the code-built spell effects: casts spells at husks and
 * screenshots them mid-flight. Run: {@code gradlew runClient -PldTest=logos_vfx}.
 */
@LDLRegisterClient(name = "logos_vfx", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SpellVfxScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "vfx").guiScale(2);
    }

    private static void husks(ServerContext sc) {
        var level = sc.level();
        for (int i = 0; i < 4; i++) {
            var h = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
            if (h == null) continue;
            h.snapTo(ORIGIN.getX() - 3 + i * 2.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 5 + (i % 2) * 2, 180, 0);
            h.setNoAi(true);
            level.addFreshEntity(h);
        }
    }

    private static void cast(ServerContext sc, String example) {
        var p = sc.player();
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 10000;
        m.trace = false;
        Casting.cast(p, Casting.compile(Examples.ALL.get(example)), null, null);
    }

    private static void castSrc(ServerContext sc, String src) {
        var p = sc.player();
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 10000;
        m.trace = false;
        Casting.cast(p, Casting.compile(src), null, null);
    }

    /** A trail packet for a body wearing the mace, drifting right across the view. */
    private static void heldMace(int k) {
        dev.logos.client.LogosFx.handle(new dev.logos.net.FxPayload((byte) dev.logos.net.FxPayload.Kind.TRAIL.ordinal(),
                (byte) dev.logos.lang.Element.PHYSICAL.ordinal(), (byte) 0,
                ORIGIN.getX() - 0.5f + k * 0.3f, ORIGIN.getY() + 3.2f, ORIGIN.getZ() + 0.5f, 1, 0, 0, 0.4f, 777,
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(net.minecraft.world.item.Items.MACE), 0, -1));
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .runCommand("difficulty normal")
                .clearArea(ORIGIN.offset(0, 5, 6), 8)
                .fill(ORIGIN.offset(-10, 0, -6), ORIGIN.offset(10, 0, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 2.5, ORIGIN.getZ() - 3.5, 0, 14)
                .server("summon targets", SpellVfxScenario::husks)
                .ticks(10)
                .server("cast fireball", sc -> cast(sc, "fireball"))
                .ticks(3)
                .screenshot("fireball_in_flight")
                .ticks(8)
                .screenshot("fireball_impact")
                .server("cast chain lightning", sc -> cast(sc, "chain-lightning"))
                .ticks(2)
                .screenshot("chain_lightning")
                .server("cast blood aura", sc -> cast(sc, "blood-aura"))
                .ticks(12)
                .screenshot("blood_aura_field")
                .server("cast star pull", sc -> cast(sc, "star-pull"))
                .ticks(6)
                .screenshot("star_pull_homing")
                .server("cast frost nova", sc -> cast(sc, "frost-nova"))
                .ticks(3)
                .screenshot("frost_nova")
                .ticks(30)
                .step("hold a mace-shaped body still in front of the camera", ctx -> heldMace(0))
                .step("move it", ctx -> heldMace(1))
                .step("move it", ctx -> heldMace(2))
                .screenshot("held_mace_look")
                .step("end it", ctx -> dev.logos.client.LogosFx.handle(new dev.logos.net.FxPayload(
                        (byte) dev.logos.net.FxPayload.Kind.TRAIL_END.ordinal(), (byte) 0, (byte) 0,
                        ORIGIN.getX() + 0.5f, ORIGIN.getY() + 3.2f, ORIGIN.getZ() + 0.5f, 0, 0, 0, 1, 777, -1, 0, -1)))
                .server("cast storm hammer", sc -> cast(sc, "storm-hammer"))
                .ticks(5)
                .screenshot("storm_hammer_look")
                .ticks(40)
                .server("cast ender gale", sc -> cast(sc, "ender-gale"))
                .ticks(4)
                .screenshot("ender_gale_trail")
                .ticks(30)
                .server("cast specium beam", sc -> cast(sc, "specium"))
                .ticks(8)
                .screenshot("specium_beam")
                .server("turn the caster", sc -> {
                    sc.player().setYRot(30);
                    sc.player().setYHeadRot(30);
                })
                .ticks(4)
                .screenshot("specium_beam_turned")
                .ticks(60)
                .server("face ahead again", sc -> {
                    sc.player().setYRot(0);
                    sc.player().setYHeadRot(0);
                })
                .server("raise a wall", sc -> castSrc(sc, "(construct :shape wall :width 5 :height 3 :life 6 :one-way :at (ahead 4) (on-block (glow target 1)))"))
                .ticks(12)
                .screenshot("construct_wall")
                .ticks(65)
                .ticks(65)
                .server("raise a dome and a glass bridge", sc -> {
                    castSrc(sc, "(construct :shape dome :radius 3 :life 6 :at (ahead 9))");
                    cast(sc, "sky-bridge");
                })
                .ticks(12)
                .screenshot("construct_dome_bridge")
                .ticks(65)
                .ticks(65)
                .server("raise a held shield", sc -> cast(sc, "held-shield"))
                .ticks(10)
                .screenshot("held_shield")
                .server("turn the caster", sc -> {
                    sc.player().setYRot(35);
                    sc.player().setYHeadRot(35);
                })
                .ticks(4)
                .screenshot("held_shield_turned")
                .server("face ahead", sc -> {
                    sc.player().setYRot(0);
                    sc.player().setYHeadRot(0);
                })
                .ticks(65)
                .ticks(65)
                .step("play a fire ring and a frost pulse directly", ctx -> {
                    dev.logos.client.LogosFx.handle(new dev.logos.net.FxPayload((byte) dev.logos.net.FxPayload.Kind.RING.ordinal(), (byte) dev.logos.lang.Element.FIRE.ordinal(), (byte) 0,
                            ORIGIN.getX() + 0.5f, ORIGIN.getY() + 1.2f, ORIGIN.getZ() + 3.5f, 0, 0, 0, 3, 0, -1, 0, -1));
                    dev.logos.client.LogosFx.handle(new dev.logos.net.FxPayload((byte) dev.logos.net.FxPayload.Kind.PULSE.ordinal(), (byte) dev.logos.lang.Element.FROST.ordinal(), (byte) 0,
                            ORIGIN.getX() - 3.5f, ORIGIN.getY() + 1.5f, ORIGIN.getZ() + 4.5f, 0, 0, 0, 2, 0, -1, 0, -1));
                })
                .ticks(3)
                .screenshot("direct_ring_pulse")
                .ticks(30)
                .step("play one lightning burst directly", ctx -> dev.logos.client.LogosFx.handle(new dev.logos.net.FxPayload(
                        (byte) dev.logos.net.FxPayload.Kind.BURST.ordinal(), (byte) dev.logos.lang.Element.LIGHTNING.ordinal(), (byte) 0,
                        ORIGIN.getX() + 0.5f, ORIGIN.getY() + 2f, ORIGIN.getZ() + 3.5f, 0, 0, 0, 1.05f, 0, -1, 0, -1)))
                .ticks(2)
                .screenshot("direct_burst");
    }
}
