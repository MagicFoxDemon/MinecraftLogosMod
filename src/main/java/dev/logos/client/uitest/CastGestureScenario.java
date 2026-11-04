package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.CastPoses;
import dev.logos.client.ClientCharges;
import dev.logos.runtime.CastPose;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * The cast gestures seen from the side, on a mannequin: each one played with a wand and caught at the
 * moments that show its shape, then the ones whose shape a staff changes most played again with a staff.
 */
@LDLRegisterClient(name = "logos_cast_gestures", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class CastGestureScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "wand", "pose").guiScale(3);
    }

    private static void hold(ScenarioBuilder s, ItemStack item) {
        s.server("hand the mannequin " + item.getItem(), sc -> sc.player().level()
                .getEntitiesOfClass(Mannequin.class, new AABB(ORIGIN).inflate(8))
                .forEach(b -> b.setItemSlot(EquipmentSlot.MAINHAND, item.copy())));
    }

    /**
     * Plays {@code gesture} on the mannequin and screenshots it {@code at} ticks into it, by the pose's own
     * clock (a scenario tick is not a client tick), which stops for each screenshot.
     */
    private static void play(ScenarioBuilder s, String name, Identifier gesture, int length, int... at) {
        s.server("play " + name, sc -> sc.player().level().getEntitiesOfClass(Mannequin.class, new AABB(ORIGIN).inflate(8))
                .forEach(b -> CastPose.play(b, gesture)));
        for (int t : at) {
            s.waitUntil(name + " reaches " + t, ctx -> stopAt(gesture, t)).screenshot(name + "_" + t)
                    .step("start the clock", ctx -> ClientCharges.freeze(false));
        }
        s.waitUntil(name + " is over", ctx -> mannequinPose() == null);
    }

    private static CastPoses.@org.jspecify.annotations.Nullable Active mannequinPose() {
        for (var e : Minecraft.getInstance().level.entitiesForRendering()) {
            if (e instanceof Mannequin) return CastPoses.of(e, 0);
        }
        return null;
    }

    /** Stops the pose clock once the mannequin's {@code gesture} is {@code t} ticks in. */
    static boolean stopAt(Identifier gesture, int t) {
        var a = mannequinPose();
        if (a == null || !a.pose().equals(gesture) || a.age() < t) return false;
        ClientCharges.freeze(true);
        LogosMod.LOGGER.info("[cast gesture] {} at {}", gesture, a);
        return true;
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 4), 10)
                .fill(ORIGIN.offset(-8, 0, -6), ORIGIN.offset(8, 0, 16), Blocks.SMOOTH_STONE.defaultBlockState())
                .server("a mannequin facing east", sc -> {
                    var level = sc.player().level();
                    Mannequin body = EntityType.MANNEQUIN.create(level, EntitySpawnReason.TRIGGERED);
                    body.snapTo(0.5, 101, 4.5, -90, 0);
                    body.setYHeadRot(-90);
                    body.setYBodyRot(-90);
                    level.addFreshEntity(body);
                })
                .teleportPlayer(0.5, 101, 7.5, 180, 0)
                .step("hide the HUD", ctx -> Minecraft.getInstance().options.hideGui = true);
        hold(s, new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()));
        s.ticks(5).screenshot("wand_rest");
        play(s, "projectile", CastPose.PROJECTILE, 10, 3, 6);
        play(s, "ray", CastPose.RAY, 7, 3);
        play(s, "field", CastPose.FIELD, 26, 6, 11, 15, 19);
        play(s, "construct", CastPose.CONSTRUCT, 12, 2, 6, 10);
        play(s, "vein", CastPose.VEIN, 13, 5, 8);
        play(s, "imbue", CastPose.IMBUE, 18, 5, 8, 12);
        play(s, "cast", CastPose.CAST, 8, 3, 5);
        hold(s, new ItemStack(LogosMod.WOOD_STAFFS.getFirst().get()));
        s.ticks(3);
        play(s, "staff_projectile", CastPose.PROJECTILE, 10, 3);
        play(s, "staff_field", CastPose.FIELD, 26, 6, 13);
        play(s, "staff_imbue", CastPose.IMBUE, 18, 8);
        // From the front, for the ones that move across: the field's circles, the construct's arc, the imbue's zigzag.
        hold(s, new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()));
        s.teleportPlayer(4.5, 101, 4.5, 90, 0).ticks(5);
        play(s, "front_field", CastPose.FIELD, 26, 9, 13, 17);
        play(s, "front_construct", CastPose.CONSTRUCT, 12, 2, 6, 10);
        play(s, "front_imbue", CastPose.IMBUE, 18, 4, 7, 10, 13);
    }
}
