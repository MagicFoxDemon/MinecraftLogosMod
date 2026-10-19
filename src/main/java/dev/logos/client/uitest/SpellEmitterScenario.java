package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.block.SpellEmitterBlock;
import dev.logos.block.SpellEmitterBlockEntity;
import dev.logos.client.ClientHooks;
import dev.logos.graph.SpecialNodes;
import dev.logos.graph.SpellGraph;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Real-client look at the spell emitter: six of them facing six ways, lit with mana or dark without;
 * then the workbench opened on one's page, the emitter's node in place of the invocation, and 试射
 * saving the page and casting it at the emitter in front.
 * Run: {@code gradlew runClient -PldTest=logos_spell_emitter}.
 */
@LDLRegisterClient(name = "logos_spell_emitter", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SpellEmitterScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(8, 100, 8);
    /** The one edited: facing east, with an emitter in front to receive what it casts. */
    private static final BlockPos EDITED = ORIGIN.offset(-3, 1, -2), TARGET = EDITED.east();
    private static final String SPELL = "(spell :name \"渡魔\" :mana 30 (infuse-block (ahead 1) 10))";

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "spell_emitter").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 0), 10)
                .fill(ORIGIN.offset(-7, 0, -7), ORIGIN.offset(7, 0, 7), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 2.5, ORIGIN.getZ() + 4.5, 180, 25)
                .server("place emitters facing six ways, and one to edit", sc -> {
                    ServerLevel level = (ServerLevel) sc.player().level();
                    Direction[] ways = Direction.values();
                    for (int i = 0; i < ways.length; i++) {
                        BlockPos at = ORIGIN.offset(-5 + 2 * i, 1, 1);
                        level.setBlock(at, LogosMod.SPELL_EMITTER.get().defaultBlockState().setValue(SpellEmitterBlock.FACING, ways[i]), 3);
                        // Every other one holds mana, and glows.
                        if (i % 2 == 0 && level.getBlockEntity(at) instanceof SpellEmitterBlockEntity e) e.receiveMana(100);
                    }
                    level.setBlock(EDITED, LogosMod.SPELL_EMITTER.get().defaultBlockState().setValue(SpellEmitterBlock.FACING, Direction.EAST), 3);
                    level.setBlock(TARGET, LogosMod.SPELL_EMITTER.get().defaultBlockState().setValue(SpellEmitterBlock.FACING, Direction.EAST), 3);
                    if (level.getBlockEntity(EDITED) instanceof SpellEmitterBlockEntity e) {
                        e.setPage(SpellPages.inscribed(PageTier.CRYSTAL, SPELL, null), null);
                        e.receiveMana(200);
                    }
                })
                .waitUntil("the client sees the page in the emitter", ctx ->
                        ctx.level().getBlockEntity(EDITED) instanceof SpellEmitterBlockEntity e && !e.page().isEmpty())
                .ticks(20)
                .check("the emitters with mana are lit", ctx -> ctx.level().getBlockState(ORIGIN.offset(-5, 1, 1)).getValue(dev.logos.block.ManaSpreaderBlock.ACTIVE)
                        && !ctx.level().getBlockState(ORIGIN.offset(-3, 1, 1)).getValue(dev.logos.block.ManaSpreaderBlock.ACTIVE))
                .screenshot("emitters_six_ways")
                .teleportPlayer(ORIGIN.getX() - 1.5, ORIGIN.getY() + 2.2, ORIGIN.getZ() + 1.5, 160, 35)
                .ticks(10)
                .screenshot("emitter_close")
                .step("open the workbench on the emitter's page", ctx -> ClientHooks.openEmitterEditor(EDITED, (SpellEmitterBlockEntity) ctx.level().getBlockEntity(EDITED)))
                .awaitModularUI()
                .ticks(10)
                .checkTextContains("#status", "成立")
                .checkTextContains("#overview", "发射器魔力")
                .check("the emitter's node stands in for the invocation", ctx -> {
                    var view = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
                    SpellGraph g = (SpellGraph) view.getGraph();
                    return g.emitter() && g.getNodes().stream().anyMatch(n -> n instanceof SpecialNodes.EmitterRoot)
                            && g.getNodes().stream().noneMatch(n -> n.getClass() == SpecialNodes.SpellRoot.class);
                })
                .check("there is a test-fire button", ctx -> ctx.ui().select("#test_fire", UIElement.class).findFirst().isPresent())
                .screenshot("emitter_editor")
                .click("#test_fire")
                .waitUntilServer("试射 saved the page and cast it at the emitter in front", sc ->
                        sc.level().getBlockEntity(TARGET) instanceof SpellEmitterBlockEntity t && Math.abs(t.mana() - 10) < 1e-6)
                .checkServer("the saved page has its invocation back, cast by hand", sc ->
                        sc.level().getBlockEntity(EDITED) instanceof SpellEmitterBlockEntity e
                                && e.page().has(LogosMod.SPELL_GRAPH.get())
                                && e.page().getOrDefault(LogosMod.SPELL_SOURCE.get(), "").contains(":trigger cast"))
                .server("hand the player dividers that measured forward 2, up 1, right 1", sc -> {
                    var dividers = new net.minecraft.world.item.ItemStack(LogosMod.DIVIDERS_ITEM.get());
                    dividers.set(LogosMod.DIVIDERS.get(), new dev.logos.item.DividersItem.Measure(EDITED, net.minecraft.resources.Identifier.withDefaultNamespace("overworld"),
                            Direction.EAST, true, 2, 1, 1));
                    sc.player().getInventory().setItem(8, dividers);
                })
                .waitUntil("the client holds the measurement", ctx -> dev.logos.item.DividersItem.carried(ctx.player()) != null)
                .click("#paste_dividers")
                .ticks(5)
                .check("an offset node holds the measurement", ctx -> {
                    var view = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
                    return view.getGraph().getNodes().stream().anyMatch(n -> n instanceof dev.logos.graph.RuneNodes.Offset o
                            && o.in("forward").getEmbeddedValue().getValue() instanceof Float f && f == 2f
                            && o.in("up").getEmbeddedValue().getValue() instanceof Float u && u == 1f
                            && o.in("right").getEmbeddedValue().getValue() instanceof Float r && r == 1f);
                })
                .screenshot("emitter_pasted_dividers")
                .closeScreen()
                .ticks(5);
    }
}
