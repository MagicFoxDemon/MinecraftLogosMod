package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The showcase every element's charge preview runs (see CHARGE_VFX.md): the four tiers one after another,
 * each gathering, held and let go, from the front in third person; the elaborate and colossal ones again
 * in first person; a grand charge dropped part way; and a colossal one by night. Charges are played by
 * {@link ChargePreview} at a fixed pace, so nothing is cast. An element subclasses it, registers it as
 * {@code logos_<element>_charge}, and may add its own steps in {@link #extra}.
 */
public abstract class ChargeTour implements UIScenario {
    /** Ticks each tier takes to fill: long enough to watch, longer for the bigger ones. */
    protected static final int[] CHARGE = {30, 50, 70, 100};
    /** Ticks each charge is held full before it is let go. */
    protected static final int HOLD = 30;
    private static final String[] TIER = {"ordinary", "elaborate", "grand", "colossal"};

    protected abstract Element element();

    /** Steps the element adds after the tour. */
    protected void extra(ScenarioBuilder s) {}

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "vfx", "charge", element().name().toLowerCase()).guiScale(2);
    }

    protected static int player() {
        var p = Minecraft.getInstance().player;
        if (p == null) throw new IllegalStateException("no player");
        return p.getId();
    }

    protected static void camera(CameraType type) {
        Minecraft.getInstance().options.setCameraType(type);
    }

    /** Waits {@code ticks}, in steps short enough for the scene runner's per-step limit. */
    protected static ScenarioBuilder wait(ScenarioBuilder s, int ticks) {
        for (; ticks > 0; ticks -= 60) s.ticks(Math.min(60, ticks));
        return s;
    }

    /** One charge of {@code tier}, filled, held and let go, with a screenshot at each stage named after {@code view}. */
    protected ScenarioBuilder play(ScenarioBuilder s, int tier, String view) {
        String name = element().name().toLowerCase() + "_" + TIER[tier] + "_" + view;
        int charge = CHARGE[tier];
        int early = charge * 2 / 5, late = charge * 9 / 10 - early;
        s.step("charge " + name, ctx -> ChargePreview.release(player(), element(), tier, charge, HOLD));
        wait(s, early).screenshot(name + "_gather");
        wait(s, late).screenshot(name + "_late");
        wait(s, charge - early - late + HOLD / 2).screenshot(name + "_held");
        wait(s, HOLD - HOLD / 2 + 2).screenshot(name + "_release");
        wait(s, 10).screenshot(name + "_after");
        return wait(s, 40).check("the " + name + " charge is over", ctx -> !ChargePreview.busy());
    }

    @Override
    public void define(ScenarioBuilder s) {
        String e = element().name().toLowerCase();
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
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
        for (int tier = 0; tier < 4; tier++) play(s, tier, "front");

        s.step("first person", ctx -> camera(CameraType.FIRST_PERSON)).ticks(5);
        play(s, 1, "fp");
        play(s, 3, "fp");

        s.step("from behind", ctx -> camera(CameraType.THIRD_PERSON_BACK)).ticks(5)
                .step("grand charge dropped part way", ctx -> ChargePreview.fizzle(player(), element(), 2, CHARGE[2], .6f));
        wait(s, CHARGE[2] / 2).screenshot(e + "_grand_back_charging");
        wait(s, CHARGE[2] / 10 + 3).screenshot(e + "_grand_back_fizzle");
        wait(s, 30).check("the dropped charge is over", ctx -> !ChargePreview.busy());

        s.step("front again", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).runCommand("time set midnight").ticks(5);
        play(s, 3, "night");
        s.runCommand("time set noon");
        extra(s);
        s.teardown("restore", ctx -> {
            ChargePreview.abandon();
            camera(CameraType.FIRST_PERSON);
        });
    }
}
