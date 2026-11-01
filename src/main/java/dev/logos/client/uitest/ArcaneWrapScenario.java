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
 * The arcane mana wrap (FORM_VFX.md, 魔力缠绕) up close: the arcane stand of {@link ManaWrapScenario}'s row with a
 * sword and bare-handed, by day and by night.
 */
@LDLRegisterClient(name = "logos_wrap_arcane", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ArcaneWrapScenario implements UIScenario {
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
                .server("wrapped stands with swords", ctx -> ManaWrapScenario.summon(ctx.server().overworld(), Items.DIAMOND_SWORD))
                .server("close to the arcane sword", ctx -> FormScenes.look(ctx.player(), AT.add(1.75, 1.05, 2.0), AT.add(1.5, .8, .25)))
                .waitUntil("client sees the wraps", ctx -> SurfaceAuras.emitters() >= Element.values().length)
                .ticks(40).screenshot("arcane_sword")
                .runCommand("time set night")
                .ticks(20).screenshot("arcane_sword_night")
                .runCommand("time set day")
                .step("clear", ctx -> SurfaceAuras.clear())
                .server("empty hands", ctx -> ManaWrapScenario.summon(ctx.server().overworld(), Items.AIR))
                .server("close to the arcane fist", ctx -> FormScenes.look(ctx.player(), AT.add(1.6, 1.0, 1.6), AT.add(1.4, .65, .1)))
                .waitUntil("client sees the fists", ctx -> SurfaceAuras.emitters() >= Element.values().length)
                .ticks(40).screenshot("arcane_fist")
                .runCommand("time set night")
                .ticks(20).screenshot("arcane_fist_night")
                .server("take the stands away", ctx -> ManaWrapScenario.discard(ctx.server().overworld()));
        s.teardown("restore viewport", ctx -> ManaWrapScenario.restore(previousHideGui));
    }

    /** One's own hand wrapped in arcane: a bare fist in first person, a punch, from behind; then a sword, a swing, at night, and a swing from behind. */
    @LDLRegisterClient(name = "logos_wrap_arcane_hand", group = "logos", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class Hand implements UIScenario {
        private boolean previousHideGui;

        @Override
        public void configure(ScenarioOptions options) {
            options.tags("logos", "vfx", "form", "wrap").defaultTimeoutMs(60000).scenarioTimeoutMs(300000);
        }

        @Override
        public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                    .step("viewport", ctx -> previousHideGui = FormScenes.viewport())
                    .step("show the hand", ctx -> Minecraft.getInstance().options.hideGui = false)
                    .server("mixed ground", ctx -> FormScenes.arena(ctx.server().overworld()))
                    .server("stand in the middle", ctx -> FormScenes.teleport(ctx.player(), AT, 180, 10))
                    .server("empty hand, wrapped in arcane", ctx -> {
                        ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        ManaWrap.wrap(ctx.player(), Element.ARCANE, 4, 600);
                    })
                    .waitUntil("client sees the wrap", ctx -> SurfaceAuras.emitters() > 0)
                    .ticks(40).screenshot("arcane_fist_hand")
                    .step("punch", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                    .ticks(3).screenshot("arcane_fist_punch")
                    .step("third person behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                    .ticks(40).screenshot("arcane_fist_back")
                    .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                    .server("take up a sword", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD)))
                    .ticks(40).screenshot("arcane_sword_hand")
                    .step("swing", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                    .ticks(3).screenshot("arcane_sword_swing")
                    .runCommand("time set night")
                    .ticks(30).screenshot("arcane_sword_hand_night")
                    .step("third person behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                    .ticks(30).screenshot("arcane_sword_back_night")
                    .step("swing from behind", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                    .ticks(2).screenshot("arcane_sword_back_swing_night")
                    .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                    .server("unwrap and put the sword down", ctx -> {
                        ManaWrap.wrap(ctx.player(), Element.ARCANE, 0, 0);
                        ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    });
            s.teardown("restore viewport", ctx -> ManaWrapScenario.restore(previousHideGui));
        }
    }
}
