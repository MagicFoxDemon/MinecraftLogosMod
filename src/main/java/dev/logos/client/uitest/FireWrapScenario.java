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
 * The fire mana wrap on one's own hand (FORM_VFX.md, 魔力缠绕): a sword in first person by day and in a swing, then
 * at night the sword, from behind, and the bare fist. {@code logos_mana_wrap} has the row of stands and the fire fist
 * by day.
 */
@LDLRegisterClient(name = "logos_wrap_fire", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FireWrapScenario implements UIScenario {
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
                .server("a sword wrapped in fire", ctx -> {
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                    ManaWrap.wrap(ctx.player(), Element.FIRE, 4, 600);
                })
                .waitUntil("client sees the wrap", ctx -> SurfaceAuras.emitters() > 0)
                .ticks(40).screenshot("wrap_fire_sword_hand")
                .step("swing", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                .ticks(3).screenshot("wrap_fire_sword_swing")
                .runCommand("time set night")
                .ticks(30).screenshot("wrap_fire_sword_night")
                .step("third person behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .ticks(40).screenshot("wrap_fire_sword_back_night")
                .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .server("put the sword down", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY))
                .ticks(40).screenshot("wrap_fire_fist_night")
                .server("unwrap", ctx -> ManaWrap.wrap(ctx.player(), Element.FIRE, 0, 0));
        s.teardown("restore viewport", ctx -> ManaWrapScenario.restore(previousHideGui));
    }
}
