package dev.logos.client.uitest;

import java.util.EnumSet;
import java.util.Set;

import com.geckolib.animation.object.PlayState;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.alchemy.Process;
import dev.logos.alchemy.ProcessCard;
import dev.logos.block.AlchemyTableBlock;
import dev.logos.block.AlchemyTableBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Real-client check of the alchemy table at work: idle (no flame, still airlock), then with a
 * fermenting and a distilling card (the flame lit, the airlock bubbling, and the fermenting and
 * distilling levers pressed as their crafts finish, the others not), then idle again.
 * Run: {@code gradlew runClient -PldTest=logos_alchemy_table_working}.
 */
@LDLRegisterClient(name = "logos_alchemy_table_working", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class AlchemyTableWorkingScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    /** The main half; the table faces south, toward the player. */
    private static final BlockPos MAIN = ORIGIN.above();
    private static AlchemyTableBlockEntity table;
    /** The levers seen pressed on the client, by process. */
    private static final Set<Process> pressed = EnumSet.noneOf(Process.class);
    private static boolean watching;

    static {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            if (!watching || !(clientTable() instanceof AlchemyTableBlockEntity t)) return;
            var controllers = t.getAnimatableInstanceCache().getManagerForId(0).getAnimationControllers();
            for (Process p : Process.values()) {
                var lever = controllers.get("lever_" + p.ordinal());
                if (lever != null && lever.isPlayingTriggeredAnimation()) pressed.add(p);
            }
        });
    }

    private static AlchemyTableBlockEntity clientTable() {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(MAIN) instanceof AlchemyTableBlockEntity t ? t : null;
    }

    private static void expectWorking(Process process, boolean working) {
        AlchemyTableBlockEntity t = clientTable();
        if (t == null) throw new AssertionError("the client has no table");
        if (t.isWorking(process) != working) throw new AssertionError("the client thinks " + process + (working ? " is idle" : " is working"));
    }

    private static boolean bubbling() {
        var ferment = clientTable().getAnimatableInstanceCache().getManagerForId(0).getAnimationControllers().get("ferment");
        return ferment != null && ferment.getPlayState() == PlayState.CONTINUE;
    }

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "alchemy").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 0), 10)
                .fill(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(6, 0, 6), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(MAIN.getX() + 1.0, MAIN.getY() + 1.1, MAIN.getZ() + 3.2, 180, 22)
                .server("place a table facing south", sc -> {
                    var level = sc.player().level();
                    BlockState main = LogosMod.ALCHEMY_TABLE.get().defaultBlockState().setValue(AlchemyTableBlock.FACING, Direction.SOUTH);
                    level.setBlock(MAIN, main, 3);
                    level.setBlock(AlchemyTableBlock.otherHalf(MAIN, main), main.setValue(AlchemyTableBlock.PART, AlchemyTableBlock.Part.SIDE), 3);
                    table = (AlchemyTableBlockEntity) level.getBlockEntity(MAIN);
                    table.setMana(AlchemyTableBlockEntity.MAX_MANA);
                })
                .ticks(30)
                .step("idle: nothing works", ctx -> {
                    for (Process p : Process.values()) expectWorking(p, false);
                    if (bubbling()) throw new AssertionError("the airlock bubbles with nothing fermenting");
                })
                .screenshot("table_idle")
                .server("a fermenting and a distilling card at work", sc -> {
                    ProcessCard ferment = table.addCard(Process.FERMENT);
                    ferment.inputs().set(0, ItemResource.of(Items.SUGAR), 16);
                    ferment.inputs().set(1, ItemResource.of(Items.RED_MUSHROOM), 16);
                    ferment.inputs().set(2, ItemResource.of(Items.WHEAT), 16);
                    table.addCard(Process.DISTILL).inputs().set(0, ItemResource.of(LogosMod.FOUL_BRINE.get()), 16);
                    // Nothing to dissolve or settle: these levers stay put.
                    table.addCard(Process.DISSOLVE);
                    pressed.clear();
                    watching = true;
                })
                .ticks(20)
                .step("working: the flame and the bubbles", ctx -> {
                    expectWorking(Process.FERMENT, true);
                    expectWorking(Process.DISTILL, true);
                    expectWorking(Process.DISSOLVE, false);
                    if (!bubbling()) throw new AssertionError("the airlock does not bubble while fermenting");
                })
                .screenshot("table_working")
                // The first crafts finish 40 ticks in.
                .ticks(40)
                .step("finished crafts pressed their levers", ctx -> {
                    System.out.println("[logos] levers pressed: " + pressed);
                    if (!pressed.equals(EnumSet.of(Process.FERMENT, Process.DISTILL))) throw new AssertionError("levers pressed: " + pressed);
                })
                .server("take the cards off", sc -> {
                    watching = false;
                    while (!table.cards().isEmpty()) table.removeCard(0);
                })
                .ticks(20)
                .step("idle again", ctx -> {
                    for (Process p : Process.values()) expectWorking(p, false);
                    if (bubbling()) throw new AssertionError("the airlock still bubbles");
                })
                .screenshot("table_idle_again");
    }
}
