package dev.logos.client.uitest;

import static dev.logos.client.uitest.FormScenes.AT;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.SurfaceAuras;
import dev.logos.lang.Element;
import dev.logos.runtime.ManaWrap;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * The lightning mana wrap (FORM_VFX.md, 魔力缠绕) on one's own hand by day: a sword in first person and in a swing,
 * the bare fist and a punch, from behind and from the front. {@link Night} has the same at night, {@link Stand} a close
 * look at the lightning stand of {@code logos_mana_wrap}'s row. Split in three because the test tool stalls around
 * client tick 410.
 */
@LDLRegisterClient(name = "logos_wrap_lightning", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LightningWrapScenario implements UIScenario {
    private boolean previousHideGui;

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "vfx", "form", "wrap").defaultTimeoutMs(60000).scenarioTimeoutMs(300000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                .step("viewport", ctx -> previousHideGui = FormScenes.viewport())
                // With the GUI hidden (F1) vanilla draws no first-person hand or item either.
                .step("show the hand", ctx -> Minecraft.getInstance().options.hideGui = false)
                .server("mixed ground", ctx -> FormScenes.arena(ctx.server().overworld()))
                .server("stand in the middle", ctx -> FormScenes.teleport(ctx.player(), AT, 180, 10))
                .server("a sword wrapped in lightning", ctx -> {
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                    ManaWrap.wrap(ctx.player(), Element.LIGHTNING, 4, 600);
                })
                .waitUntil("client sees the wrap", ctx -> SurfaceAuras.emitters() > 0)
                .ticks(40).screenshot("wrap_lightning_sword_hand")
                .step("swing", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                .ticks(3).screenshot("wrap_lightning_sword_swing")
                .server("put the sword down", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY))
                .ticks(30).screenshot("wrap_lightning_fist_hand")
                .step("punch", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                .ticks(3).screenshot("wrap_lightning_fist_swing")
                .step("third person behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .ticks(30).screenshot("wrap_lightning_fist_back")
                .server("take the sword up again", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD)))
                .ticks(30).screenshot("wrap_lightning_sword_back")
                .step("third person in front", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(30).screenshot("wrap_lightning_sword_front")
                .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .server("unwrap and put the sword down", ctx -> {
                    ManaWrap.wrap(ctx.player(), Element.LIGHTNING, 0, 0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                });
        s.teardown("restore viewport", ctx -> ManaWrapScenario.restore(previousHideGui));
    }

    /** The same hand at night: the sword in first person and from behind, then the bare fist. */
    @LDLRegisterClient(name = "logos_wrap_lightning_night", group = "logos", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class Night implements UIScenario {
        private boolean previousHideGui;

        @Override
        public void configure(ScenarioOptions options) {
            options.tags("logos", "vfx", "form", "wrap").defaultTimeoutMs(60000).scenarioTimeoutMs(300000);
        }

        @Override
        public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE).runCommand("time set night").runCommand("difficulty peaceful")
                    .step("viewport", ctx -> previousHideGui = FormScenes.viewport())
                    .step("show the hand", ctx -> Minecraft.getInstance().options.hideGui = false)
                    .server("mixed ground", ctx -> FormScenes.arena(ctx.server().overworld()))
                    .server("stand in the middle", ctx -> FormScenes.teleport(ctx.player(), AT, 180, 10))
                    .server("a sword wrapped in lightning", ctx -> {
                        ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                        ManaWrap.wrap(ctx.player(), Element.LIGHTNING, 4, 600);
                    })
                    .waitUntil("client sees the wrap", ctx -> SurfaceAuras.emitters() > 0)
                    .ticks(40).screenshot("wrap_lightning_sword_night")
                    .step("third person behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                    .ticks(30).screenshot("wrap_lightning_sword_back_night")
                    .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                    .server("put the sword down", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY))
                    .ticks(30).screenshot("wrap_lightning_fist_night")
                    .server("unwrap", ctx -> ManaWrap.wrap(ctx.player(), Element.LIGHTNING, 0, 0));
            s.teardown("restore viewport", ctx -> ManaWrapScenario.restore(previousHideGui));
        }
    }

    /** Close to the lightning stand (fourth of seven) in {@code logos_mana_wrap}'s row: its sword by day and night, then its fist. */
    @LDLRegisterClient(name = "logos_wrap_lightning_stand", group = "logos", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class Stand implements UIScenario {
        private boolean previousHideGui;

        @Override
        public void configure(ScenarioOptions options) {
            options.tags("logos", "vfx", "form", "wrap").defaultTimeoutMs(60000).scenarioTimeoutMs(300000);
        }

        @Override
        public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                    .step("viewport", ctx -> previousHideGui = FormScenes.viewport())
                    .server("mixed ground", ctx -> FormScenes.arena(ctx.server().overworld()))
                    .server("seven wrapped stands", ctx -> ManaWrapScenario.summon(ctx.server().overworld(), Items.DIAMOND_SWORD))
                    .server("close to the lightning sword", ctx -> FormScenes.look(ctx.player(), AT.add(-1.0, 1.05, 1.7), AT.add(-.3, .75, .2)))
                    .waitUntil("client sees the wraps", ctx -> SurfaceAuras.emitters() >= Element.values().length)
                    .ticks(40).screenshot("wrap_lightning_stand_sword")
                    .runCommand("time set night")
                    .ticks(25).screenshot("wrap_lightning_stand_sword_night")
                    .step("clear", ctx -> SurfaceAuras.clear())
                    .server("empty hands", ctx -> ManaWrapScenario.summon(ctx.server().overworld(), Items.AIR))
                    .server("close to the lightning fist", ctx -> FormScenes.look(ctx.player(), AT.add(-.4, 1.0, 1.9), AT.add(-.55, .75, .25)))
                    .waitUntil("client sees the fists", ctx -> SurfaceAuras.emitters() >= Element.values().length)
                    .ticks(30).screenshot("wrap_lightning_stand_fist_night")
                    .runCommand("time set day")
                    .ticks(25).screenshot("wrap_lightning_stand_fist")
                    .server("take the stands away", ctx -> ManaWrapScenario.discard(ctx.server().overworld()));
            s.teardown("restore viewport", ctx -> ManaWrapScenario.restore(previousHideGui));
        }
    }
}
