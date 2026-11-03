package dev.logos.client.uitest;

import java.util.function.IntSupplier;

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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Only the colossal arcane charge, for its aura round the caster (ArcaneChargeAura, after NS_Charge_MagicLift_Root):
 * by day from the front early, half way and late in the charge, held and let go; in first person; dropped part
 * way; side on, cast by a husk standing across the view; and by night.
 */
@LDLRegisterClient(name = "logos_arcane_charge_aura", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ArcaneChargeAuraScenario extends ChargeTour {
    private static final int FILL = CHARGE[3];
    /** Let go on this tick of the charge's clock. */
    private static final int LET_GO = FILL + HOLD + 1;
    private static Husk husk;

    @Override
    protected Element element() { return Element.ARCANE; }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear").runCommand("difficulty normal")
                .clearArea(new BlockPos(0, 110, 0), 16)
                .fill(new BlockPos(-14, 100, -14), new BlockPos(14, 100, 14), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, .5, 0, 10)
                .server("hold a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(LogosMod.MASTER_WAND.get())))
                .step("show the HUD, look from the front", ctx -> {
                    Minecraft.getInstance().options.hideGui = false;
                    camera(CameraType.THIRD_PERSON_FRONT);
                })
                .ticks(10);
        IntSupplier me = ChargeTour::player;
        charge(s, me, "day", 25, "early", 55, "middle", 90, "late", FILL + HOLD / 2, "held", LET_GO + 2, "release",
                LET_GO + 5, "release_late");

        s.step("first person", ctx -> camera(CameraType.FIRST_PERSON)).ticks(5);
        charge(s, me, "fp", 55, "middle", FILL + HOLD / 2, "held", LET_GO + 2, "release");

        s.step("front again", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(5)
                .step("colossal charge dropped part way", ctx -> ChargePreview.fizzle(player(), Element.ARCANE, 3, FILL, .6f));
        shot(at(s, 50), "arcane_aura_drop_charging");
        // Dropped at 60 %.
        shot(at(s, FILL * 6 / 10 + 3), "arcane_aura_drop_fizzle");
        shot(at(s, FILL * 6 / 10 + 7), "arcane_aura_drop_fading");
        at(s, FILL + 30).check("the dropped charge is over", ctx -> !ChargePreview.busy());

        // Side on: a husk five blocks ahead, facing east, casting while we look on in first person.
        s.server("a husk to cast", sc -> {
            husk = EntityType.HUSK.create(sc.player().level(), EntitySpawnReason.COMMAND);
            husk.snapTo(.5, 101, 6.5, -90, 0);
            husk.setNoAi(true);
            husk.setYBodyRot(-90);
            husk.setYHeadRot(-90);
            husk.setInvulnerable(true);
            husk.setPersistenceRequired();
            sc.player().level().addFreshEntity(husk);
        }).step("look at it side on", ctx -> {
            camera(CameraType.FIRST_PERSON);
            var p = Minecraft.getInstance().player;
            p.setYRot(0);
            p.setXRot(-14);
        }).ticks(5);
        charge(s, () -> husk.getId(), "side", 55, "middle", FILL + HOLD / 2, "held", LET_GO + 2, "release");
        s.server("send the husk away", sc -> husk.discard());

        s.step("front by night", ctx -> {
            camera(CameraType.THIRD_PERSON_FRONT);
            var p = Minecraft.getInstance().player;
            p.setXRot(10);
        }).runCommand("time set midnight").ticks(5);
        charge(s, me, "night", 55, "middle", 90, "late", FILL + HOLD / 2, "held", LET_GO + 2, "release");
        s.runCommand("time set noon");
        s.teardown("restore", ctx -> {
            ClientCharges.freeze(false);
            ChargePreview.abandon();
            camera(CameraType.FIRST_PERSON);
        });
    }

    /** A colossal charge by {@code who}, filled, held and let go, with a screenshot at each (tick, name) pair. */
    private static void charge(ScenarioBuilder s, IntSupplier who, String view, Object... shots) {
        s.step("colossal charge " + view, ctx -> ChargePreview.release(who.getAsInt(), Element.ARCANE, 3, FILL, HOLD));
        for (int i = 0; i + 1 < shots.length; i += 2)
            shot(at(s, (Integer) shots[i]), "arcane_aura_" + view + "_" + shots[i + 1]);
        at(s, LET_GO + 40).check("the " + view + " charge is over", ctx -> !ChargePreview.busy());
    }
}
