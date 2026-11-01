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
import net.minecraft.world.phys.Vec3;

/**
 * The void wrap (FORM_VFX.md, 魔力缠绕) up close on the row of {@link ManaWrapScenario}'s stands: the void stand's
 * sword and fist, by day and by night.
 */
@LDLRegisterClient(name = "logos_wrap_void", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class VoidWrapScenario implements UIScenario {
    /** The void stand, fifth of seven. */
    private static final Vec3 STAND = AT.add(.9, 0, 0);
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
                .server("seven wrapped stands", ctx -> ManaWrapScenario.summon(ctx.server().overworld(), Items.IRON_SWORD))
                .server("close to the void sword", ctx -> FormScenes.look(ctx.player(), STAND.add(.1, 1.0, 1.7), STAND.add(-.15, .95, .25)))
                .waitUntil("client sees the wraps", ctx -> SurfaceAuras.emitters() >= Element.values().length)
                .ticks(40).screenshot("wrap_void_sword")
                .runCommand("time set night")
                .ticks(20).screenshot("wrap_void_sword_night")
                .runCommand("time set day")
                .step("clear", ctx -> SurfaceAuras.clear())
                .server("empty hands", ctx -> ManaWrapScenario.summon(ctx.server().overworld(), Items.AIR))
                .server("close to the void fist", ctx -> FormScenes.look(ctx.player(), STAND.add(.1, 1.0, 1.9), STAND.add(-.15, .75, .25)))
                .waitUntil("client sees the fists", ctx -> SurfaceAuras.emitters() >= Element.values().length)
                .ticks(40).screenshot("wrap_void_fist")
                .runCommand("time set night")
                .ticks(20).screenshot("wrap_void_fist_night")
                .server("take the stands away", ctx -> ManaWrapScenario.discard(ctx.server().overworld()));
        s.teardown("restore viewport", ctx -> ManaWrapScenario.restore(previousHideGui));
    }

    /** One's own hand wrapped in void: the bare fist in first person, a swing, from behind, then a sword, by day and by night. */
    @LDLRegisterClient(name = "logos_wrap_void_hand", group = "logos", registry = UIScenario.REGISTRY,
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
                    .server("empty hand, wrapped in void", ctx -> {
                        ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        ManaWrap.wrap(ctx.player(), Element.VOID, 4, 600);
                    })
                    .waitUntil("client sees the wrap", ctx -> SurfaceAuras.emitters() > 0)
                    .ticks(40).screenshot("wrap_void_fist_hand")
                    .step("swing", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                    .ticks(3).screenshot("wrap_void_fist_swing")
                    .step("third person behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                    .ticks(30).screenshot("wrap_void_fist_back")
                    .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                    .server("take up a sword", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD)))
                    .ticks(30).screenshot("wrap_void_sword_hand")
                    .step("swing", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                    .ticks(3).screenshot("wrap_void_sword_swing")
                    .runCommand("time set night")
                    .ticks(25).screenshot("wrap_void_sword_hand_night")
                    .server("unwrap and put the sword down", ctx -> {
                        ManaWrap.wrap(ctx.player(), Element.VOID, 0, 0);
                        ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    });
            s.teardown("restore viewport", ctx -> ManaWrapScenario.restore(previousHideGui));
        }
    }
}
