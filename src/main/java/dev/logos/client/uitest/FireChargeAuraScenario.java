package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.ClientCharges;
import dev.logos.lang.Element;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The colossal fire charge's own aura (FireChargeAura, after NS_Charge_AuraBrust_Root) on its own: only colossal
 * charges, seen from the side through an invisible stand's eyes by day (early, midway, nearly full, held, let go,
 * after) and dropped part way, from the front in third person, in first person, and from the side by night.
 */
@LDLRegisterClient(name = "logos_fire_charge_aura", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FireChargeAuraScenario extends ChargeTour {
    private static final int COLOSSAL = 3;
    private static int side = -1;

    @Override
    protected Element element() { return Element.FIRE; }

    private static int stand(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        ArmorStand eye = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        if (eye == null) throw new IllegalStateException("armour stand creation failed");
        eye.setInvisible(true);
        eye.setNoGravity(true);
        eye.snapTo(x, y, z, yaw, pitch);
        level.addFreshEntity(eye);
        return eye.getId();
    }

    /** Looks through the side stand (or the player when false); the HUD is hidden while borrowing the stand's eyes. */
    private static void look(boolean fromSide) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = fromSide ? mc.level.getEntity(side) : null;
        if (e != null) {
            // The stand comes over with its body turned, not its view: point its eyes the way they were placed.
            e.setYRot(-90); e.yRotO = -90; e.setXRot(-8); e.xRotO = -8; e.setYHeadRot(-90);
        }
        camera(CameraType.FIRST_PERSON);
        mc.setCameraEntity(e != null ? e : mc.player);
        mc.options.hideGui = e != null;
    }

    /** A colossal charge filled over {@link ChargeTour#CHARGE}, held and let go, a screenshot at each listed tick. */
    private ScenarioBuilder release(ScenarioBuilder s, String name, int... ticks) {
        int charge = CHARGE[COLOSSAL];
        s.step("charge " + name, ctx -> ChargePreview.release(player(), Element.FIRE, COLOSSAL, charge, HOLD));
        String[] label = {"1_early", "2_mid", "3_late", "4_held", "5_release", "6_after"};
        int[] at = {charge / 5, charge / 2, charge * 9 / 10, charge + HOLD / 2, charge + HOLD + 3, charge + HOLD + 10};
        for (int i = 0; i < at.length; i++) {
            for (int t : ticks) if (t == i) shot(at(s, at[i]), "fire_aura_" + name + "_" + label[i]);
        }
        return at(s, charge + HOLD + 50).check("the " + name + " charge is over", ctx -> !ChargePreview.busy());
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 110, 0), 16)
                .fill(new BlockPos(-14, 100, -14), new BlockPos(14, 100, 14), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, .5, 0, 10)
                .server("hold a wand, and set an eye at the side", sc -> {
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.MASTER_WAND.get()));
                    side = stand(sc.level(), -6, 101, .5, -90, -8);
                })
                .ticks(20)
                .step("look from the side", ctx -> look(true)).ticks(5);
        release(s, "side_day", 0, 1, 2, 3, 4, 5);

        int charge = CHARGE[COLOSSAL];
        s.step("dropped part way", ctx -> ChargePreview.fizzle(player(), Element.FIRE, COLOSSAL, charge, .6f));
        shot(at(s, charge * 6 / 10 - 2), "fire_aura_side_day_7_before_drop");
        shot(at(s, charge * 6 / 10 + 4), "fire_aura_side_day_8_dropped");
        at(s, charge + 30).check("the dropped charge is over", ctx -> !ChargePreview.busy());

        s.step("front, third person", ctx -> {
            look(false);
            camera(CameraType.THIRD_PERSON_FRONT);
        }).ticks(5);
        release(s, "front_day", 2, 3, 4);

        s.step("first person", ctx -> look(false)).ticks(5);
        release(s, "fp_day", 1, 3, 4);

        s.step("side again, by night", ctx -> look(true)).runCommand("time set midnight").ticks(5);
        release(s, "side_night", 1, 3, 4, 5);
        s.runCommand("time set noon");
        s.teardown("restore", ctx -> {
            ClientCharges.freeze(false);
            ChargePreview.abandon();
            look(false);
        });
    }
}
