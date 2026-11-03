package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;

import dev.logos.LogosMod;
import dev.logos.client.ClientCharges;
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
 * The preview of the colossal aura lightning, void, frost and physical draw in place of the shared pillar
 * (CHARGE_VFX.md, after NS_Charge_EvokeEnergy_Root): only colossal charges, caught early, half way and nearly full, held, let go and
 * dropped, by day and by night, from the front, from the side (a husk charging, seen side-on) and in first
 * person. Charges are played by {@link ChargePreview}; screenshots are taken on the charge's own clock.
 */
abstract class ChargeAuraEvokeTour extends ChargeTour {
    private static final int COLOSSAL = 3, FILL = CHARGE[COLOSSAL];
    private int husk = -1;

    /** A colossal charge on {@code entity}, with the given shots: {@code at} ticks into it, named {@code names}. */
    private ScenarioBuilder colossal(ScenarioBuilder s, String label, java.util.function.IntSupplier entity, int[] at, String[] names) {
        String e = element().name().toLowerCase();
        s.step("colossal charge " + label, ctx -> ChargePreview.release(entity.getAsInt(), element(), COLOSSAL, FILL, HOLD));
        for (int i = 0; i < at.length; i++) shot(at(s, at[i]), e + "_" + label + "_" + names[i]);
        return at(s, FILL + HOLD + 40).check("the " + label + " charge is over", ctx -> !ChargePreview.busy());
    }

    @Override
    public void define(ScenarioBuilder s) {
        String e = element().name().toLowerCase();
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 110, 0), 16)
                .fill(new BlockPos(-14, 100, -14), new BlockPos(14, 100, 14), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, .5, 0, 10)
                .server("hold a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.MASTER_WAND.get())))
                .step("show the HUD, look from the front", ctx -> {
                    Minecraft.getInstance().options.hideGui = false;
                    camera(CameraType.THIRD_PERSON_FRONT);
                })
                .ticks(10);
        // By day from the front: early, half way, nearly full, held, just let go, after.
        int[] stages = {FILL / 4, FILL * 11 / 20, FILL * 9 / 10, FILL + HOLD / 2, FILL + HOLD + 3, FILL + HOLD + 9};
        String[] names = {"early", "mid", "late", "held", "release", "after"};
        colossal(s, "day_front", ChargeTour::player, stages, names);

        // From the side: a husk charging, seen side-on from five blocks off (not despawned as on peaceful).
        s.runCommand("difficulty normal").server("a husk to charge, side-on", sc -> {
            var p = sc.player();
            Husk h = EntityType.HUSK.create(p.level(), EntitySpawnReason.COMMAND);
            if (h == null) throw new AssertionError("no husk");
            h.snapTo(.5, 101, 3.5, 0, 0);
            h.setYHeadRot(0);
            h.setYBodyRot(0);
            h.setNoAi(true);
            h.setInvulnerable(true);
            h.setPersistenceRequired();
            p.level().addFreshEntity(h);
            husk = h.getId();
        }).teleportPlayer(-4.5, 101, 3.5, -90, 8).step("first person, looking at its side", ctx -> camera(CameraType.FIRST_PERSON)).ticks(10);
        colossal(s, "day_side", () -> husk, new int[] {FILL * 11 / 20, FILL + HOLD / 2, FILL + HOLD + 3},
                new String[] {"mid", "held", "release"});
        s.server("send the husk away", sc -> {
            var h = sc.player().level().getEntity(husk);
            if (h != null) h.discard();
        });

        // The caster's own colossal charge in first person: nothing may cover the view.
        s.teleportPlayer(.5, 101, .5, 0, 10).ticks(5);
        colossal(s, "day_fp", ChargeTour::player, new int[] {FILL * 11 / 20, FILL + HOLD / 2, FILL + HOLD + 3},
                new String[] {"mid", "held", "release"});

        // Dropped at 60 %, from the front.
        s.step("front again", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(5)
                .step("colossal charge dropped", ctx -> ChargePreview.fizzle(player(), element(), COLOSSAL, FILL, .6f));
        shot(at(s, FILL / 2), e + "_day_drop_charging");
        shot(at(s, FILL * 6 / 10 + 3), e + "_day_drop_fizzle");
        shot(at(s, FILL * 6 / 10 + 8), e + "_day_drop_fading");
        at(s, FILL + 20).check("the dropped charge is over", ctx -> !ChargePreview.busy());

        // By night from the front.
        s.runCommand("time set midnight").ticks(5);
        colossal(s, "night_front", ChargeTour::player, new int[] {FILL / 4, FILL * 11 / 20, FILL + HOLD / 2, FILL + HOLD + 3},
                new String[] {"early", "mid", "held", "release"});
        s.runCommand("time set noon");
        s.teardown("restore", ctx -> {
            ClientCharges.freeze(false);
            ChargePreview.abandon();
            camera(CameraType.FIRST_PERSON);
        });
    }
}
