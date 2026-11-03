package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The colossal life charge's wind aura (LifeChargeAura, CHARGE_VFX.md) on its own: only colossal charges, caught
 * early, half way and nearly full, held, let go and just after, from the front; side-on (a villager casting, the
 * player watching from beside it); in first person; dropped part way; and by night.
 */
@LDLRegisterClient(name = "logos_life_charge_aura", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LifeChargeAuraScenario extends ChargeTour {
    private static final int COLOSSAL = 3;
    private int caster = -1;

    @Override
    protected Element element() { return Element.LIFE; }

    /** A colossal charge by the player (or the villager), shot at every stage, named {@code life_aura_<view>_<stage>}. */
    private ScenarioBuilder colossal(ScenarioBuilder s, String view, boolean villager) {
        String name = "life_aura_" + view;
        int charge = CHARGE[COLOSSAL];
        s.step("charge " + name, ctx -> ChargePreview.release(villager ? caster : player(), Element.LIFE, COLOSSAL, charge, HOLD));
        shot(at(s, charge / 5), name + "_early");
        shot(at(s, charge / 2), name + "_mid");
        shot(at(s, charge * 9 / 10), name + "_late");
        shot(at(s, charge + HOLD / 2), name + "_held");
        // Let go at charge + HOLD + 1: the five big spirals sweep up over the aura's last flash.
        shot(at(s, charge + HOLD + 3), name + "_release");
        shot(at(s, charge + HOLD + 7), name + "_release2");
        shot(at(s, charge + HOLD + 14), name + "_after");
        return at(s, charge + HOLD + 50).check("the " + name + " charge is over", ctx -> !ChargePreview.busy());
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 110, 0), 16)
                .fill(new BlockPos(-14, 100, -14), new BlockPos(14, 100, 14), Blocks.SMOOTH_STONE.defaultBlockState())
                .fill(new BlockPos(-14, 100, 5), new BlockPos(14, 100, 14), Blocks.GRASS_BLOCK.defaultBlockState())
                .teleportPlayer(.5, 101, .5, 0, 10)
                .server("hold a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(LogosMod.MASTER_WAND.get())))
                .step("show the HUD, look from the front", ctx -> {
                    Minecraft.getInstance().options.hideGui = false;
                    camera(CameraType.THIRD_PERSON_FRONT);
                })
                .ticks(10);
        colossal(s, "front", false);

        // Side-on: a still villager six blocks ahead casts while the player looks at it from its side.
        s.server("a villager to cast", sc -> {
            var v = EntityType.VILLAGER.create(sc.player().level(), EntitySpawnReason.COMMAND);
            v.setNoAi(true);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.snapTo(.5, 101, 6.5, 90, 0);
            v.setYHeadRot(90);
            sc.player().level().addFreshEntity(v);
            caster = v.getId();
        }).teleportPlayer(.5, 101, .5, 0, 8).step("first person, looking at the villager", ctx -> camera(CameraType.FIRST_PERSON)).ticks(5);
        colossal(s, "side", true);
        s.server("villager gone", sc -> {
            var e = sc.player().level().getEntity(caster);
            if (e != null) e.discard();
        });

        // In first person: the spirals round the head fade, the ground and far tails stay.
        s.teleportPlayer(.5, 101, .5, 0, 25).ticks(5);
        colossal(s, "fp", false);

        // Dropped at 60 %.
        s.teleportPlayer(.5, 101, .5, 0, 10).step("front again", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(5)
                .step("colossal charge dropped part way", ctx -> ChargePreview.fizzle(player(), Element.LIFE, COLOSSAL, CHARGE[COLOSSAL], .6f));
        shot(at(s, CHARGE[COLOSSAL] / 2), "life_aura_drop_charging");
        shot(at(s, CHARGE[COLOSSAL] * 6 / 10 + 3), "life_aura_drop_fizzle");
        shot(at(s, CHARGE[COLOSSAL] * 6 / 10 + 8), "life_aura_drop_fizzle2");
        at(s, CHARGE[COLOSSAL] + 30).check("the dropped charge is over", ctx -> !ChargePreview.busy());

        s.runCommand("time set midnight").ticks(5);
        colossal(s, "night", false);
        s.runCommand("time set noon");
        s.teardown("restore", ctx -> {
            dev.logos.client.ClientCharges.freeze(false);
            ChargePreview.abandon();
            camera(CameraType.FIRST_PERSON);
        });
    }
}
