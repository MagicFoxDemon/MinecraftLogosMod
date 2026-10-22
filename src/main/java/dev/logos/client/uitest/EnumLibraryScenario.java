package dev.logos.client.uitest;

import java.util.concurrent.CompletableFuture;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.graph.EnumValues;
import dev.logos.graph.GraphCompiler;
import dev.logos.graph.RuneNode;
import dev.logos.graph.RuneNodes;
import dev.logos.item.SpellPages;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Real-client catalogue search, game-art previews, drag placement, undo/redo and formerly unwireable inputs. */
@LDLRegisterClient(name="logos_enum_library", group="logos", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class EnumLibraryScenario implements UIScenario {
    @Override public void configure(ScenarioOptions o) { o.tags("logos").guiScale(2); }
    private static GraphView view(TestContext ctx) { return ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView; }
    private static UIElement element(TestContext ctx, String id) { return ctx.ui().select("#" + id, UIElement.class).findFirst().orElseThrow(); }
    private static boolean reloaded(TestContext ctx) { CompletableFuture<Void> f = ctx.get("language_reload"); return f.isDone() && !f.isCompletedExceptionally(); }
    @SuppressWarnings("unchecked")
    private static void category(TestContext ctx, EnumValues.Kind kind) {
        ((Selector<EnumValues.Kind>) ctx.ui().select("#enum_category", Selector.class).findFirst().orElseThrow()).setSelected(kind, true);
        ctx.ui().select("#enum_search", TextField.class).findFirst().orElseThrow().setValue("", true);
    }
    private static long cards(TestContext ctx) { return view(ctx).getGraph().getNodes().stream().filter(n -> n instanceof EnumValues.Value).count(); }

    @Override public void define(ScenarioBuilder s) {
        s.step("Chinese catalogue", ctx -> {
                    var mc = Minecraft.getInstance(); ctx.put("language", mc.getLanguageManager().getSelected());
                    mc.getLanguageManager().setSelected("zh_cn"); ctx.put("language_reload", mc.reloadResourcePacks());
                }).waitUntil("resources loaded", EnumLibraryScenario::reloaded)
                .step("open editor", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting("(spell :name \"枚举试用\" :mana 400 (summon zombie))")))
                .awaitModularUI().ticks(10).click("#tab_enums").ticks(6)
                .step("third bookmark", ctx -> {
                    ctx.check("catalogue open", element(ctx, "spell_enum_library").isDisplayed());
                    ctx.check("overview closed", !element(ctx, "spell_details").isDisplayed());
                    ctx.check("rune library closed", !element(ctx, "spell_library").isDisplayed());
                }).screenshot("enum_creatures")
                .step("find exact creature", ctx -> ctx.ui().select("#enum_search", TextField.class).findFirst().orElseThrow().setValue("minecraft:zombie", true))
                .ticks(4).screenshot("enum_creature_search")
                .step("drag a registry card", ctx -> {
                    ctx.put("cards", cards(ctx));
                    UIElement row = element(ctx, "enum_minecraft_zombie_villager"), canvas = element(ctx, "spell_canvas");
                    ctx.input().mouseDown(row.getPositionX() + 36, row.getPositionY() + 8, 0);
                    ctx.input().dragTo(canvas.getPositionX() + canvas.getSizeWidth() * .7f, canvas.getPositionY() + canvas.getSizeHeight() * .45f, 0);
                }).ticks(2).step("drop card", ctx -> {
                    UIElement canvas = element(ctx, "spell_canvas");
                    ctx.input().mouseUp(canvas.getPositionX() + canvas.getSizeWidth() * .7f, canvas.getPositionY() + canvas.getSizeHeight() * .45f, 0);
                }).ticks(4)
                .step("selected value and undo", ctx -> {
                    long before = ctx.get("cards"); ctx.check("one new card", cards(ctx) == before + 1);
                    var card = view(ctx).getGraph().getNodes().stream().filter(n -> n instanceof EnumValues.Value).map(n -> (EnumValues.Value)n).findFirst().orElseThrow();
                    ctx.check("drag preserves a nondefault ID", card.value().equals("minecraft:zombie_villager"));
                    view(ctx).getHistoryStack().undo(); ctx.check("undo removes card", cards(ctx) == before);
                    view(ctx).getHistoryStack().redo();
                }).ticks(4)
                .step("wire the creature input", ctx -> {
                    var card = view(ctx).getGraph().getNodes().stream().filter(n -> n instanceof EnumValues.Value).map(n -> (EnumValues.Value)n).findFirst().orElseThrow();
                    ctx.check("redo preserves the selected ID", card.value().equals("minecraft:zombie_villager"));
                    RuneNodes.Summon summon = view(ctx).getGraph().getNodes().stream().filter(n -> n instanceof RuneNodes.Summon).map(n -> (RuneNodes.Summon)n).findFirst().orElseThrow();
                    view(ctx).dispatchCommand(new com.lowdragmc.lowdraglib2.nodegraphtookit.gui.command.WireCommands.CreateWireCommand(summon.in("creature"), card.out(RuneNode.OUT)));
                    ctx.check("wired enum compiles", GraphCompiler.compile((dev.logos.graph.SpellGraph)view(ctx).getGraph()).ok());
                    view(ctx).fitGraphChildren();
                }).ticks(6).step("connected option display", ctx -> {
                    var options = ctx.ui().select(".__node-option-container__", com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.NodeOptionsInspector.class)
                            .filter(o -> o.nodeModel instanceof com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel model
                                    && model.getNode() instanceof RuneNodes.Summon).findFirst().orElseThrow();
                    ctx.check("connected legacy fallback hidden", !options.getChildren().getFirst().isDisplayed());
                }).screenshot("enum_wired_card")
                .step("items", ctx -> category(ctx, EnumValues.Kind.ITEM)).ticks(4).screenshot("enum_items")
                .step("blocks", ctx -> category(ctx, EnumValues.Kind.BLOCK)).ticks(4).screenshot("enum_blocks")
                .step("particles", ctx -> category(ctx, EnumValues.Kind.PARTICLE)).ticks(4).screenshot("enum_particles")
                .step("dimensions", ctx -> category(ctx, EnumValues.Kind.DIMENSION)).ticks(4).screenshot("enum_dimensions")
                .step("effects", ctx -> category(ctx, EnumValues.Kind.EFFECT)).ticks(4).screenshot("enum_effects")
                .step("motion values", ctx -> category(ctx, EnumValues.Kind.MOTION)).ticks(4).screenshot("enum_motions")
                .click("#tab_library").ticks(4).check("rune bookmark still works", ctx -> !element(ctx, "spell_enum_library").isDisplayed() && element(ctx, "spell_library").isDisplayed())
                .closeScreen().step("restore language", ctx -> {
                    var mc = Minecraft.getInstance(); mc.getLanguageManager().setSelected(ctx.get("language")); ctx.put("language_reload", mc.reloadResourcePacks());
                }).waitUntil("language restored", EnumLibraryScenario::reloaded);
    }
}
