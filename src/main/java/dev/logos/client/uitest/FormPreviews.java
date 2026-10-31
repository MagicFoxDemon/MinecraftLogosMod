package dev.logos.client.uitest;

import static dev.logos.client.uitest.FormScenes.AT;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ManaTornadoVisual;
import dev.logos.client.ManaTornados;
import dev.logos.client.PillarBurstVisual;
import dev.logos.client.PillarBursts;
import dev.logos.runtime.ManaTornado;
import dev.logos.runtime.PillarBurst;
import dev.logos.runtime.SurfaceAura;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * The form effects (FORM_VFX.md) played for a person to watch in a window: no screenshots, no checks, each from a
 * few viewpoints with time to see it through. Not in the {@code logos} group; each effect has its own group, split
 * into short day and night scenes, run as {@code -PldTest=group:logos_<effect>_preview -PldTestWindow=1600x900}.
 */
public final class FormPreviews {
    private FormPreviews() {}

    /** A shared base: daylight or night, the viewport and the ground, then {@link #show}. */
    abstract static class Preview implements UIScenario {
        private boolean previousHideGui;

        abstract String tag();

        String time() { return "day"; }

        abstract void show(ScenarioBuilder s);

        void restore() {}

        @Override
        public void configure(ScenarioOptions options) {
            options.tags(tag()).defaultTimeoutMs(60000).scenarioTimeoutMs(300000);
        }

        @Override
        public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE).runCommand("time set " + time()).runCommand("difficulty peaceful")
                    .step("viewport", ctx -> previousHideGui = FormScenes.viewport())
                    .server("mixed ground", ctx -> FormScenes.arena(ctx.server().overworld()));
            show(s);
            s.teardown("restore viewport", ctx -> {
                restore();
                FormScenes.restore(previousHideGui);
            });
        }
    }

    // ---- pillar burst --------------------------------------------------------------------------

    /** Long enough for one pillar to play out, and a little more. */
    static final int PILLAR_WATCH = PillarBurstVisual.LIFE + 16;

    /** Moves to a viewpoint, lets the picture settle, plays one pillar through the server and watches it through. */
    static void pillar(ScenarioBuilder s, String name, Vec3 at, float yaw, float pitch, float scale) {
        s.server(name, ctx -> FormScenes.teleport(ctx.player(), at, yaw, pitch))
                .ticks(15)
                .step("clear", ctx -> PillarBursts.clear())
                .server("burst at scale " + scale, ctx -> PillarBurst.play(ctx.server().overworld(), AT, scale))
                .ticks(PILLAR_WATCH);
    }

    /** By day: from afar, up close, and twice the size from the side. */
    @LDLRegisterClient(name = "logos_pillar_preview_day", group = "logos_pillar_preview", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class PillarDay extends Preview {
        @Override String tag() { return "logos_pillar_preview"; }

        @Override void show(ScenarioBuilder s) {
            pillar(s, "look up from afar", new Vec3(.5, 100, 34.5), 180, -30, 1);
            pillar(s, "level view up close", new Vec3(.5, 100, 13.5), 180, 8, 1);
            pillar(s, "twice the size from the side", new Vec3(-19.5, 100, .5), -90, -42, 2);
        }

        @Override void restore() { PillarBursts.clear(); }
    }

    /** By night: from afar, standing beside it in first person, and in third person from behind. */
    @LDLRegisterClient(name = "logos_pillar_preview_night", group = "logos_pillar_preview", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class PillarNight extends PillarDay {
        @Override String time() { return "night"; }

        @Override void show(ScenarioBuilder s) {
            pillar(s, "from afar by night", new Vec3(.5, 100, 34.5), 180, -30, 1);
            pillar(s, "standing beside it", new Vec3(.5, 100, 6.5), 180, -20, 1);
            s.step("third person behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK));
            pillar(s, "from behind", new Vec3(.5, 100, 9.5), 180, -12, 1);
        }
    }

    // ---- mana tornado --------------------------------------------------------------------------

    /** How long each tornado whirls, and how long more to watch it fade. */
    static final int TORNADO_WATCH = 200, TORNADO_AFTER = (int) ManaTornadoVisual.FADE + 16;

    /** Moves to a viewpoint, winds one up round a villager, releases it and watches it fade. */
    static void villager(ScenarioBuilder s, String name, Vec3 at, float yaw, float pitch) {
        s.server("a caster", ctx -> ManaTornadoScenario.caster = ManaTornadoScenario.summonCaster(ctx.server().overworld()))
                .server(name, ctx -> FormScenes.teleport(ctx.player(), at, yaw, pitch))
                .ticks(15)
                .step("clear", ctx -> ManaTornados.clear())
                .server("wind up", ctx -> ManaTornado.play(ctx.server().overworld(), ManaTornadoScenario.casterEntity(ctx.server().overworld()), -1, 1, ManaTornado.CYCLE))
                .ticks(TORNADO_WATCH)
                .server("release", ctx -> ManaTornado.stop(ctx.server().overworld(), ManaTornadoScenario.casterEntity(ctx.server().overworld())))
                .ticks(TORNADO_AFTER)
                .server("dismiss the caster", ctx -> ManaTornadoScenario.dismiss(ctx.server().overworld()));
    }

    /** Stands in the middle, turns to {@code camera}, winds one up round the player, releases it and watches it fade. */
    static void self(ScenarioBuilder s, String name, CameraType camera, float pitch) {
        s.server("stand in the middle", ctx -> FormScenes.teleport(ctx.player(), AT, 180, pitch))
                .step(name, ctx -> Minecraft.getInstance().options.setCameraType(camera))
                .ticks(15)
                .step("clear", ctx -> ManaTornados.clear())
                .server("wind up", ctx -> ManaTornado.play(ctx.server().overworld(), ctx.player(), -1, 1, ManaTornado.CYCLE))
                .ticks(TORNADO_WATCH)
                .server("release", ctx -> ManaTornado.stop(ctx.server().overworld(), ctx.player()))
                .ticks(TORNADO_AFTER)
                .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));
    }

    /** By day: round a villager from afar and up close, then round the player in third person; its colour turns a circle in 12 seconds. */
    @LDLRegisterClient(name = "logos_tornado_preview_day", group = "logos_tornado_preview", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class TornadoDay extends Preview {
        @Override String tag() { return "logos_tornado_preview"; }

        @Override void show(ScenarioBuilder s) {
            villager(s, "level view from afar", new Vec3(.5, 100, 16.5), 180, -14);
            villager(s, "up close", new Vec3(.5, 100, 7.5), 180, -25);
            self(s, "third person behind", CameraType.THIRD_PERSON_BACK, 12);
        }

        @Override void restore() { ManaTornados.clear(); }
    }

    /** By night: from afar, third person from behind, first person looking up. */
    @LDLRegisterClient(name = "logos_tornado_preview_night", group = "logos_tornado_preview", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class TornadoNight extends TornadoDay {
        @Override String time() { return "night"; }

        @Override void show(ScenarioBuilder s) {
            villager(s, "from afar by night", new Vec3(.5, 100, 16.5), 180, -14);
            self(s, "third person behind", CameraType.THIRD_PERSON_BACK, 12);
            self(s, "first person looking up", CameraType.FIRST_PERSON, -50);
        }
    }

    // ---- surface aura --------------------------------------------------------------------------

    /** How long each look lasts; held in hand, how often it swings. */
    static final int AURA_WATCH = 160, AURA_SWING = 20;

    static void stand(ScenarioBuilder s, SurfaceAura.Style style, float hue) {
        s.server("change the aura", ctx -> {
            if (ctx.server().overworld().getEntity(SurfaceAuraScenario.stand) instanceof ArmorStand a) a.setItemSlot(EquipmentSlot.MAINHAND, SurfaceAuraScenario.sword(style, hue));
        }).ticks(AURA_WATCH);
    }

    static void hand(ScenarioBuilder s, SurfaceAura.Style style, CameraType camera) {
        s.server("take up the sword", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, SurfaceAuraScenario.sword(style, 0)))
                .step("change view", ctx -> Minecraft.getInstance().options.setCameraType(camera))
                .ticks(AURA_SWING * 2);
        for (int i = 0; i < AURA_WATCH / AURA_SWING; i++) s.step("swing", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND)).ticks(AURA_SWING);
    }

    /**
     * An armour stand's sword on fire, wound with mana, then on blue fire; then held by the player, in first and third
     * person each a while, swinging every second.
     */
    @LDLRegisterClient(name = "logos_aura_preview_day", group = "logos_aura_preview", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class AuraDay extends Preview {
        @Override String tag() { return "logos_aura_preview"; }

        @Override void show(ScenarioBuilder s) {
            s.server("an armour stand", ctx -> SurfaceAuraScenario.stand = SurfaceAuraScenario.summonStand(ctx.server().overworld(), SurfaceAura.Style.FIRE, 0))
                    .server("close to the stand", ctx -> FormScenes.look(ctx.player(), new Vec3(1.7, 101.0, 2.0), AT.add(-.35, 1.0, .25)));
            stand(s, SurfaceAura.Style.FIRE, 0);
            stand(s, SurfaceAura.Style.MANA, 0);
            stand(s, SurfaceAura.Style.FIRE, .55f);
            s.server("take the stand away", ctx -> SurfaceAuraScenario.discard(ctx.server().overworld()))
                    // With the GUI hidden (F1) vanilla draws no first-person hand or item either.
                    .step("show the hand", ctx -> Minecraft.getInstance().options.hideGui = false)
                    .server("stand in the middle", ctx -> FormScenes.look(ctx.player(), AT, AT.add(0, 1.4, -5)));
            hand(s, SurfaceAura.Style.FIRE, CameraType.FIRST_PERSON);
            hand(s, SurfaceAura.Style.FIRE, CameraType.THIRD_PERSON_BACK);
            hand(s, SurfaceAura.Style.MANA, CameraType.FIRST_PERSON);
            hand(s, SurfaceAura.Style.MANA, CameraType.THIRD_PERSON_BACK);
            s.server("put the sword down", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY))
                    .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));
        }

        @Override void restore() { dev.logos.client.SurfaceAuras.clear(); }
    }

    /** The same by night. */
    @LDLRegisterClient(name = "logos_aura_preview_night", group = "logos_aura_preview", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class AuraNight extends AuraDay {
        @Override String time() { return "midnight"; }
    }
}
