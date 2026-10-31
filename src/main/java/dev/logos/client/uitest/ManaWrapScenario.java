package dev.logos.client.uitest;

import static dev.logos.client.uitest.FormScenes.AT;

import java.util.ArrayList;
import java.util.List;

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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Mana wraps (the imbue rune, FORM_VFX.md) in all seven elements: a row of armour stands, physical to life from left
 * to right, first holding swords, then bare-handed (the wrap on the fist). Each waits 40 ticks for the particles to
 * build up before its screenshot. The element worktrees check their own wrap here.
 */
@LDLRegisterClient(name = "logos_mana_wrap", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ManaWrapScenario implements UIScenario {
    static final List<Integer> STANDS = new ArrayList<>();
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
                .server("seven wrapped stands", ctx -> summon(ctx.server().overworld(), Items.DIAMOND_SWORD))
                .server("in front of the row", ctx -> FormScenes.look(ctx.player(), AT.add(0, 1.0, 4.2), AT.add(0, .7, 0)))
                .waitUntil("client sees the wraps", ctx -> SurfaceAuras.emitters() >= Element.values().length)
                .ticks(40).screenshot("wrap_swords")
                .server("close to the fire sword", ctx -> FormScenes.look(ctx.player(), AT.add(-1.4, 1.0, 1.9), AT.add(-1.55, .95, .25)))
                .ticks(30).screenshot("wrap_fire_sword")
                .step("clear", ctx -> SurfaceAuras.clear())
                .server("empty hands", ctx -> summon(ctx.server().overworld(), Items.AIR))
                .server("in front of the row", ctx -> FormScenes.look(ctx.player(), AT.add(0, 1.0, 4.2), AT.add(0, .7, 0)))
                .waitUntil("client sees the fists", ctx -> SurfaceAuras.emitters() >= Element.values().length)
                .ticks(40).screenshot("wrap_fists")
                .server("close to the arcane fist", ctx -> FormScenes.look(ctx.player(), AT.add(1.4, 1.0, 1.9), AT.add(1.25, .75, .25)))
                .ticks(30).screenshot("wrap_arcane_fist")
                .runCommand("time set night")
                .ticks(30).screenshot("wrap_arcane_fist_night")
                .server("take the stands away", ctx -> discard(ctx.server().overworld()));
        s.teardown("restore viewport", ctx -> restore(previousHideGui));
    }

    /** One armour stand per element in a row along X, centred on {@link FormScenes#AT}, facing south, arms showing, holding {@code item}, wrapped. */
    static void summon(ServerLevel level, net.minecraft.world.item.Item item) {
        discard(level);
        Element[] elements = Element.values();
        for (int i = 0; i < elements.length; i++) {
            var a = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
            a.setShowArms(true);
            a.setNoGravity(true);
            a.snapTo(AT.x + (i - (elements.length - 1) / 2.0) * 0.9, AT.y, AT.z, 0, 0);
            a.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
            level.addFreshEntity(a);
            ManaWrap.wrap(a, elements[i], 4, 600);
            STANDS.add(a.getId());
        }
    }

    static void discard(ServerLevel level) {
        for (int id : STANDS) {
            Entity e = level.getEntity(id);
            if (e != null) e.discard();
        }
        STANDS.clear();
    }

    static void restore(boolean hideGui) {
        SurfaceAuras.clear();
        FormScenes.restore(hideGui);
    }

    /** One's own wrapped hand: a bare fist in first person and from behind, then a sword, wrapped in fire, then frost. */
    @LDLRegisterClient(name = "logos_mana_wrap_hand", group = "logos", registry = UIScenario.REGISTRY,
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
                    // With the GUI hidden (F1) vanilla draws no first-person hand or item either.
                    .step("show the hand", ctx -> Minecraft.getInstance().options.hideGui = false)
                    .server("mixed ground", ctx -> FormScenes.arena(ctx.server().overworld()))
                    .server("stand in the middle", ctx -> FormScenes.teleport(ctx.player(), AT, 180, 10))
                    .server("empty hand, wrapped in fire", ctx -> {
                        ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        ManaWrap.wrap(ctx.player(), Element.FIRE, 4, 600);
                    })
                    .waitUntil("client sees the wrap", ctx -> SurfaceAuras.emitters() > 0)
                    .ticks(40).screenshot("wrap_fire_fist_hand")
                    .step("swing", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                    .ticks(3).screenshot("wrap_fire_fist_swing")
                    .step("third person behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                    .ticks(40).screenshot("wrap_fire_fist_back")
                    .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                    .server("take up a sword, wrapped in frost", ctx -> {
                        ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                        ManaWrap.wrap(ctx.player(), Element.FROST, 4, 600);
                    })
                    .ticks(40).screenshot("wrap_frost_sword_hand")
                    .server("unwrap and put the sword down", ctx -> {
                        ManaWrap.wrap(ctx.player(), Element.FROST, 0, 0);
                        ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    });
            s.teardown("restore viewport", ctx -> restore(previousHideGui));
        }
    }
}
