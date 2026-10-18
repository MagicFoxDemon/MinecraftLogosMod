package dev.logos.client.uitest;

import java.util.concurrent.CompletableFuture;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.graph.LogosNode;
import dev.logos.item.SpellPages;
import dev.logos.lang.Examples;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Real-client test of the workbench's side pages: the overview and the rune library open by
 * their ribbons and shut each other, shutting both gives the canvas the whole width, the library
 * filters by name, and a rune dragged from it onto the canvas is placed there (and undone).
 * Run: {@code gradlew runClient -PldTest=logos_rune_library}.
 */
@LDLRegisterClient(name = "logos_rune_library", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class RuneLibraryScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    private static GraphView view(TestContext ctx) {
        return ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
    }

    private static UIElement byId(TestContext ctx, String id) {
        return ctx.ui().select("#" + id, UIElement.class).findFirst().orElseThrow();
    }

    private static boolean reloaded(TestContext ctx) {
        CompletableFuture<Void> future = ctx.get("language_reload");
        return future.isDone() && !future.isCompletedExceptionally();
    }

    private static long count(TestContext ctx, String word) {
        return view(ctx).getGraph().graphModel.getNodeModels().stream()
                .filter(m -> m instanceof ICustomNodeModel c && c.getNode() instanceof LogosNode n && n.word().equals(word)).count();
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("read the page in Chinese", ctx -> {
                    var mc = Minecraft.getInstance();
                    ctx.put("original_language", mc.getLanguageManager().getSelected());
                    mc.getLanguageManager().setSelected("zh_cn");
                    ctx.put("language_reload", mc.reloadResourcePacks());
                })
                .waitUntil("Chinese resources loaded", RuneLibraryScenario::reloaded)
                .step("open the workbench", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO,
                        SpellPages.fitting(Examples.ALL.get("fireball"))))
                .awaitModularUI()
                .ticks(10)
                .step("the overview starts open and the library shut", ctx -> {
                    ctx.check("overview open", byId(ctx, "spell_details").isDisplayed());
                    ctx.check("library shut", !byId(ctx, "spell_library").isDisplayed());
                    ctx.put("canvas_width", byId(ctx, "spell_canvas").getSizeWidth());
                })
                .click("#tab_library")
                .ticks(5)
                .step("the library opens and shuts the overview", ctx -> {
                    ctx.check("library open", byId(ctx, "spell_library").isDisplayed());
                    ctx.check("overview shut", !byId(ctx, "spell_details").isDisplayed());
                    ctx.check("the library lists runes", ctx.ui().select(".spell-library-entry").count() > 80);
                })
                .screenshot("library_open")
                .step("search for damage", ctx -> ctx.ui().select("#spell_library_search", TextField.class).findFirst().orElseThrow().setValue("伤害", true))
                .ticks(5)
                .step("only matching runes remain", ctx -> {
                    long shown = ctx.ui().select(".spell-library-entry").filter(UIElement::isDisplayed).count();
                    ctx.check("search narrows the library (" + shown + ")", shown >= 1 && shown < 10);
                    ctx.put("before", count(ctx, "damage"));
                })
                .screenshot("library_search")
                .step("drag the first match onto the canvas", ctx -> {
                    UIElement entry = ctx.ui().select(".spell-library-entry").filter(UIElement::isDisplayed).findFirst().orElseThrow();
                    UIElement canvas = byId(ctx, "spell_canvas");
                    ctx.input().mouseDown(entry.getPositionX() + 20, entry.getPositionY() + 5, 0);
                    ctx.input().dragTo(canvas.getPositionX() + canvas.getSizeWidth() * 0.3f, canvas.getPositionY() + canvas.getSizeHeight() * 0.7f, 0);
                })
                .ticks(2)
                .step("drop it", ctx -> {
                    UIElement canvas = byId(ctx, "spell_canvas");
                    ctx.input().mouseUp(canvas.getPositionX() + canvas.getSizeWidth() * 0.3f, canvas.getPositionY() + canvas.getSizeHeight() * 0.7f, 0);
                })
                .ticks(5)
                .step("the rune is placed where it was dropped", ctx -> {
                    long before = ctx.get("before");
                    ctx.check("one more damage rune on the canvas", count(ctx, "damage") == before + 1);
                })
                .screenshot("library_dropped")
                .step("undo the placement", ctx -> view(ctx).getHistoryStack().undo())
                .ticks(3)
                .step("undo removes it", ctx -> {
                    long before = ctx.get("before");
                    ctx.check("placing is undoable", count(ctx, "damage") == before);
                })
                .click("#tab_library")
                .ticks(5)
                .step("shutting the open page gives the canvas the whole width", ctx -> {
                    ctx.check("library shut", !byId(ctx, "spell_library").isDisplayed());
                    ctx.check("overview still shut", !byId(ctx, "spell_details").isDisplayed());
                    float before = ctx.get("canvas_width");
                    ctx.check("canvas grew", byId(ctx, "spell_canvas").getSizeWidth() > before * 1.3f);
                })
                .screenshot("both_shut")
                .click("#tab_overview")
                .ticks(5)
                .step("the overview opens again", ctx -> {
                    ctx.check("overview open", byId(ctx, "spell_details").isDisplayed());
                    ctx.check("library shut", !byId(ctx, "spell_library").isDisplayed());
                })
                .closeScreen()
                .step("restore language", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.getLanguageManager().setSelected(ctx.get("original_language"));
                    ctx.put("language_reload", mc.reloadResourcePacks());
                })
                .waitUntil("original language restored", RuneLibraryScenario::reloaded);
    }
}
