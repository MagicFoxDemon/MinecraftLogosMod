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
 * The frost mana wrap on one's own hand (FORM_VFX.md, 魔力缠绕): the bare fist in first person by day, in a swing and
 * from behind, a sword in a swing, then at night the fist, the sword and the sword from behind.
 * {@code logos_mana_wrap_hand} has the frost sword in first person by day, {@code logos_mana_wrap} the row of stands.
 */
@LDLRegisterClient(name = "logos_wrap_frost", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FrostWrapScenario implements UIScenario {
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
                .server("empty hand, wrapped in frost", ctx -> {
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    ManaWrap.wrap(ctx.player(), Element.FROST, 4, 600);
                })
                .waitUntil("client sees the wrap", ctx -> SurfaceAuras.emitters() > 0)
                .ticks(40).screenshot("wrap_frost_fist_hand")
                .step("swing", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                .ticks(3).screenshot("wrap_frost_fist_swing")
                .step("third person behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .ticks(40).screenshot("wrap_frost_fist_back")
                .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .server("take up a sword", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD)))
                .ticks(30).step("swing", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                .ticks(3).screenshot("wrap_frost_sword_swing")
                .runCommand("time set night")
                .ticks(30).screenshot("wrap_frost_sword_night")
                .step("third person behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .ticks(40).screenshot("wrap_frost_sword_back_night")
                .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .server("put the sword down", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY))
                .ticks(40).screenshot("wrap_frost_fist_night")
                .server("unwrap", ctx -> ManaWrap.wrap(ctx.player(), Element.FROST, 0, 0));
        s.teardown("restore viewport", ctx -> ManaWrapScenario.restore(previousHideGui));
    }

    /** Close to the frost stand in {@code logos_mana_wrap}'s row (third from the left): its sword by day and at night, then its fist at night. */
    @LDLRegisterClient(name = "logos_wrap_frost_stand", group = "logos", registry = UIScenario.REGISTRY,
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
                    .server("close to the frost sword", ctx -> FormScenes.look(ctx.player(), AT.add(-.3, 1.1, 2.5), AT.add(-1.45, .85, .3)))
                    .waitUntil("client sees the wraps", ctx -> SurfaceAuras.emitters() >= 3)
                    .ticks(40).screenshot("wrap_frost_stand_sword")
                    .runCommand("time set night")
                    .ticks(30).screenshot("wrap_frost_stand_sword_night")
                    .step("clear", ctx -> SurfaceAuras.clear())
                    .server("empty hands", ctx -> ManaWrapScenario.summon(ctx.server().overworld(), Items.AIR))
                    .waitUntil("client sees the fists", ctx -> SurfaceAuras.emitters() >= 3)
                    .ticks(40).screenshot("wrap_frost_stand_fist_night")
                    .server("take the stands away", ctx -> ManaWrapScenario.discard(ctx.server().overworld()));
            s.teardown("restore viewport", ctx -> ManaWrapScenario.restore(previousHideGui));
        }
    }
}
