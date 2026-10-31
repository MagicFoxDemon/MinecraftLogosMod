package dev.logos.client.uitest;

import static dev.logos.client.uitest.FormScenes.AT;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.SurfaceAuras;
import dev.logos.runtime.SurfaceAura;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Surface auras (FORM_VFX.md) on a sword an armour stand holds (on fire, wound with mana, fire turned blue, mana by
 * night) and on one dropped on the ground. Seen from two or three blocks off; each waits 40 ticks for the particles
 * in the world to build up before its screenshot.
 */
@LDLRegisterClient(name = "logos_surface_aura", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SurfaceAuraScenario implements UIScenario {
    static int stand = -1, dropped = -1;
    private boolean previousHideGui;

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "vfx", "form", "aura").defaultTimeoutMs(60000).scenarioTimeoutMs(300000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                .step("viewport", ctx -> previousHideGui = FormScenes.viewport())
                .server("mixed ground", ctx -> FormScenes.arena(ctx.server().overworld()))
                .server("an armour stand", ctx -> stand = summonStand(ctx.server().overworld(), SurfaceAura.Style.FIRE, 0))
                .server("close to the stand", ctx -> FormScenes.look(ctx.player(), new Vec3(1.7, 101.0, 2.0), AT.add(-.35, 1.0, .25)))
                .ticks(10);
        shot(s, "aura_fire_stand", SurfaceAura.Style.FIRE, 0);
        shot(s, "aura_mana_stand", SurfaceAura.Style.MANA, 0);
        shot(s, "aura_fire_blue", SurfaceAura.Style.FIRE, .55f);
        s.server("take the stand away", ctx -> discard(ctx.server().overworld()))
                .server("drop a sword", ctx -> dropped = drop(ctx.server().overworld(), new Vec3(.5, 100.1, .5)))
                .server("look at the ground", ctx -> FormScenes.look(ctx.player(), new Vec3(1.9, 101.2, 2.1), new Vec3(.5, 100.2, .5)))
                .waitUntil("client sees the aura", ctx -> SurfaceAuras.emitters() > 0)
                .ticks(40).screenshot("aura_fire_ground")
                .server("take the sword away", ctx -> discard(ctx.server().overworld()))
                .runCommand("time set night")
                .server("the stand again", ctx -> stand = summonStand(ctx.server().overworld(), SurfaceAura.Style.MANA, 0))
                .server("close to the stand", ctx -> FormScenes.look(ctx.player(), new Vec3(1.7, 101.0, 2.0), AT.add(-.35, 1.0, .25)))
                .waitUntil("client sees the aura", ctx -> SurfaceAuras.emitters() > 0)
                .ticks(40).screenshot("aura_mana_night")
                .server("take the stand away", ctx -> discard(ctx.server().overworld()));
        s.teardown("restore viewport", ctx -> restore(previousHideGui));
    }

    /** Puts this aura on the stand's sword and takes a screenshot 40 ticks later. */
    static void shot(ScenarioBuilder s, String name, SurfaceAura.Style style, float hue) {
        s.step("clear " + name, ctx -> SurfaceAuras.clear())
                .server("aura " + name, ctx -> {
                    if (ctx.server().overworld().getEntity(stand) instanceof ArmorStand a) a.setItemSlot(EquipmentSlot.MAINHAND, sword(style, hue));
                })
                .waitUntil("client sees the aura", ctx -> SurfaceAuras.emitters() > 0)
                .ticks(40).screenshot(name);
    }

    static ItemStack sword(SurfaceAura.Style style, float hue) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        SurfaceAura.set(sword, style, hue);
        return sword;
    }

    /** An armour stand on {@link FormScenes#AT}, facing south, arms showing, holding a sword. */
    static int summonStand(ServerLevel level, SurfaceAura.Style style, float hue) {
        discard(level);
        var a = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        a.setShowArms(true);
        a.setNoGravity(true);
        a.snapTo(AT.x, AT.y, AT.z, 0, 0);
        a.setItemSlot(EquipmentSlot.MAINHAND, sword(style, hue));
        level.addFreshEntity(a);
        return a.getId();
    }

    /** A sword on the ground that can be neither picked up nor despawn. */
    static int drop(ServerLevel level, Vec3 at) {
        var item = new ItemEntity(level, at.x, at.y, at.z, sword(SurfaceAura.Style.FIRE, 0), 0, 0, 0);
        item.setPickUpDelay(32767);
        item.setUnlimitedLifetime();
        level.addFreshEntity(item);
        return item.getId();
    }

    static void discard(ServerLevel level) {
        for (int id : new int[] {stand, dropped}) {
            Entity e = id >= 0 ? level.getEntity(id) : null;
            if (e != null) e.discard();
        }
        stand = dropped = -1;
    }

    static void restore(boolean hideGui) {
        SurfaceAuras.clear();
        FormScenes.restore(hideGui);
    }

    /** Held by the player themselves: in first person (on fire, a swing, wound with mana), and a swing in third person from behind. */
    @LDLRegisterClient(name = "logos_surface_aura_hand", group = "logos", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class Hand implements UIScenario {
        private boolean previousHideGui;

        @Override
        public void configure(ScenarioOptions options) {
            options.tags("logos", "vfx", "form", "aura").defaultTimeoutMs(60000).scenarioTimeoutMs(300000);
        }

        @Override
        public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                    .step("viewport", ctx -> previousHideGui = FormScenes.viewport())
                    // With the GUI hidden (F1) vanilla draws no first-person hand or item either.
                    .step("show the hand", ctx -> Minecraft.getInstance().options.hideGui = false)
                    .server("mixed ground", ctx -> FormScenes.arena(ctx.server().overworld()))
                    .server("take the stand away", ctx -> discard(ctx.server().overworld()))
                    .server("stand in the middle", ctx -> FormScenes.teleport(ctx.player(), AT, 180, 10))
                    .server("take up a flaming sword", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, sword(SurfaceAura.Style.FIRE, 0)))
                    .waitUntil("client sees the aura", ctx -> SurfaceAuras.emitters() > 0)
                    .ticks(40).screenshot("aura_fire_hand")
                    .step("swing", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                    .ticks(3).screenshot("aura_fire_swing")
                    .step("third person behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                    .ticks(40).screenshot("aura_fire_back")
                    .step("swing", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                    .ticks(3).screenshot("aura_fire_back_swing")
                    .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                    .server("change to a mana sword", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, sword(SurfaceAura.Style.MANA, 0)))
                    .ticks(40).screenshot("aura_mana_hand")
                    .server("put the sword down", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
            s.teardown("restore viewport", ctx -> restore(previousHideGui));
        }
    }
}
