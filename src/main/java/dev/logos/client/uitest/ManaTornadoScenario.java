package dev.logos.client.uitest;

import static dev.logos.client.uitest.FormScenes.AT;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ManaTornados;
import dev.logos.runtime.ManaTornado;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * The mana tornado (FORM_VFX.md) round a villager standing still as the caster, seen level from 16 blocks off: one
 * shot each winding up, fully formed, turning in hue and fading. Each stage winds a fresh one up through the server
 * before sampling; the hue shot turns a full circle in 12 seconds and waits half of it.
 */
@LDLRegisterClient(name = "logos_mana_tornado", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ManaTornadoScenario implements UIScenario {
    static int caster = -1;
    private boolean previousHideGui;

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "vfx", "form", "tornado").defaultTimeoutMs(60000).scenarioTimeoutMs(300000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                .step("viewport", ctx -> previousHideGui = FormScenes.viewport())
                .server("mixed ground", ctx -> FormScenes.arena(ctx.server().overworld()))
                .server("a caster", ctx -> caster = summonCaster(ctx.server().overworld()))
                .server("level view from afar", ctx -> FormScenes.teleport(ctx.player(), new Vec3(.5, 100, 16.5), 180, -14))
                .ticks(20);
        stage(s, "tornado_grow", 6, 0);
        stage(s, "tornado_full", 40, 0);
        stage(s, "tornado_hue", 120, ManaTornado.CYCLE);
        s.server("release", ctx -> ManaTornado.stop(ctx.server().overworld(), casterEntity(ctx.server().overworld())))
                .ticks(12).screenshot("tornado_fade")
                .server("dismiss the caster", ctx -> dismiss(ctx.server().overworld()));
        s.teardown("restore viewport", ctx -> restore(previousHideGui));
    }

    /** Clears what is whirling, winds a fresh one up round the caster through the server (until released), and takes a screenshot {@code ticks} later. */
    static void stage(ScenarioBuilder s, String name, int ticks, int cycle) {
        s.step("clear " + name, ctx -> ManaTornados.clear())
                .server("wind up " + name, ctx -> ManaTornado.play(ctx.server().overworld(), casterEntity(ctx.server().overworld()), -1, 1, cycle))
                .waitUntil("client got the tornado", ctx -> ManaTornados.count() > 0)
                .ticks(ticks).screenshot(name);
    }

    /** A villager standing still and silent on {@link FormScenes#AT}. */
    static int summonCaster(ServerLevel level) {
        Entity old = caster >= 0 ? level.getEntity(caster) : null;
        if (old != null) old.discard();
        var villager = EntityType.VILLAGER.create(level, EntitySpawnReason.COMMAND);
        villager.setNoAi(true);
        villager.setInvulnerable(true);
        villager.setSilent(true);
        villager.snapTo(AT.x, AT.y, AT.z, 0, 0);
        villager.setYHeadRot(0);
        level.addFreshEntity(villager);
        return villager.getId();
    }

    static Entity casterEntity(ServerLevel level) {
        Entity e = level.getEntity(caster);
        return e != null ? e : level.getEntity(summonCaster(level));
    }

    /** Takes the villager away, so the next scene does not teleport the player into it. */
    static void dismiss(ServerLevel level) {
        Entity old = caster >= 0 ? level.getEntity(caster) : null;
        if (old != null) old.discard();
        caster = -1;
    }

    static void restore(boolean hideGui) {
        ManaTornados.clear();
        FormScenes.restore(hideGui);
    }

    /** The caster's own view: third person from behind, first person level and looking up; and from afar by night. */
    @LDLRegisterClient(name = "logos_mana_tornado_caster", group = "logos", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class Caster implements UIScenario {
        private boolean previousHideGui;

        @Override
        public void configure(ScenarioOptions options) {
            options.tags("logos", "vfx", "form", "tornado").defaultTimeoutMs(60000).scenarioTimeoutMs(300000);
        }

        @Override
        public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                    .step("viewport", ctx -> previousHideGui = FormScenes.viewport())
                    .server("mixed ground", ctx -> FormScenes.arena(ctx.server().overworld()))
                    .server("dismiss the caster", ctx -> dismiss(ctx.server().overworld()))
                    .server("stand in the middle", ctx -> FormScenes.teleport(ctx.player(), AT, 180, 12))
                    .step("third person behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                    .ticks(20);
            self(s, "tornado_caster_back", 40);
            s.step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                    .server("look level", ctx -> FormScenes.teleport(ctx.player(), AT, 180, -5))
                    .ticks(5);
            self(s, "tornado_caster_eye", 40);
            s.server("look up", ctx -> FormScenes.teleport(ctx.player(), AT, 180, -70)).ticks(5);
            self(s, "tornado_caster_up", 40);
            s.runCommand("time set night")
                    .server("a caster", ctx -> caster = summonCaster(ctx.server().overworld()))
                    .server("from afar by night", ctx -> FormScenes.teleport(ctx.player(), new Vec3(.5, 100, 16.5), 180, -14))
                    .ticks(10);
            stage(s, "tornado_night", 40, 0);
            s.server("dismiss the caster", ctx -> dismiss(ctx.server().overworld()));
            s.teardown("restore viewport", ctx -> restore(previousHideGui));
        }

        /** Round the player themselves. */
        static void self(ScenarioBuilder s, String name, int ticks) {
            s.step("clear " + name, ctx -> ManaTornados.clear())
                    .server("wind up " + name, ctx -> ManaTornado.play(ctx.server().overworld(), ctx.player(), -1, 1, 0))
                    .waitUntil("client got the tornado", ctx -> ManaTornados.count() > 0)
                    .ticks(ticks).screenshot(name);
        }
    }
}
