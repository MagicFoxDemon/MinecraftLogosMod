package dev.logos.client.uitest;

import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import com.google.gson.GsonBuilder;

import org.joml.Vector2f;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.command.GraphCommands;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.GraphNodeCreationData;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.NodeModelLibraryItem;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.wiget.StickyNoteElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.graph.LogosNode;
import dev.logos.graph.RuneNode;
import dev.logos.graph.RuneNodes;
import dev.logos.item.SpellPages;
import dev.logos.lang.Examples;
import dev.logos.lang.ConstructShape;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/** Checks Chinese library labels/search and a note's fold/unfold behavior in the real client. */
@LDLRegisterClient(name = "logos_editor_localization", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SpellEditorLocalizationScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos").guiScale(3);
    }

    private static GraphView view(TestContext ctx) {
        return ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
    }

    private static boolean reloaded(TestContext ctx) {
        CompletableFuture<Void> future = ctx.get("language_reload");
        return future.isDone() && !future.isCompletedExceptionally();
    }

    private static void recordLabel(TestContext ctx, List<Map<String, String>> manifest, LogosNode node,
            String kind, String id, Component label) {
        String text = label.getString();
        ctx.check("localized " + node.word() + "/" + kind + "/" + id, !text.matches(".*[A-Za-z].*"));
        manifest.add(Map.of("node", node.word(), "name", Component.translatable(node.nameKey()).getString(),
                "kind", kind, "id", id, "label", text));
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("load the Chinese language", ctx -> {
                    var mc = Minecraft.getInstance();
                    ctx.put("original_language", mc.getLanguageManager().getSelected());
                    mc.getLanguageManager().setSelected("zh_cn");
                    ctx.put("language_reload", mc.reloadResourcePacks());
                })
                .waitUntil("Chinese resources loaded", SpellEditorLocalizationScenario::reloaded)
                .step("open the workbench", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO,
                        SpellPages.fitting(Examples.ALL.get("fireball"))))
                .awaitModularUI()
                .ticks(10)
                .step("check every registered library name", ctx -> {
                    var view = view(ctx);
                    var graph = view.getGraph().graphModel;
                    int before = graph.getNodeModels().size();
                    int count = 0;
                    List<Map<String, String>> manifest = new ArrayList<>();
                    for (var item : view.itemLibrary.getAllItems().toList()) {
                        if (!(item instanceof NodeModelLibraryItem entry)) continue;
                        if (!(entry.createNode(GraphNodeCreationData.ofOrphan(graph)) instanceof ICustomNodeModel model)
                                || !(model.getNode() instanceof LogosNode node)) continue;
                        count++;
                        String name = Component.translatable(node.nameKey()).getString();
                        ctx.check("translation exists for " + node.word(), Language.getInstance().has(node.nameKey()));
                        ctx.check("library displays " + name, name.equals(item.getDisplayName().getString()));
                        ctx.check("Chinese search includes " + name, item.getSearchableName().contains(name));
                        ctx.check("English search includes " + node.word(), item.getSearchableName().contains(node.word()));
                        node.model().getInputsById().forEach((id, port) -> recordLabel(ctx, manifest, node, "input", id, port.getDisplayName()));
                        node.model().getOutputsById().forEach((id, port) -> recordLabel(ctx, manifest, node, "output", id, port.getDisplayName()));
                        node.getNodeOptions().forEach(option -> recordLabel(ctx, manifest, node, "option", option.getId(), option.getDisplayName()));
                        if (node instanceof RuneNode rune) {
                            if (rune.word().equals("split")) ctx.check("split spread is an angle", rune.in("kw_spread").getDisplayName().getString().equals("散布角（度）"));
                            if (rune.word().equals("copy")) ctx.check("copy spread is a deflection step", rune.in("kw_spread").getDisplayName().getString().equals("偏转角（度）"));
                            if (rune.word().equals("phantasm")) ctx.check("phantasm spread is a distance scale", rune.in("kw_spread").getDisplayName().getString().equals("散布幅度（格）"));
                            if (rune.word().equals("drain")) ctx.check("drain amount denotes damage", rune.in("amount").getDisplayName().getString().equals("汲取伤害"));
                            if (rune.word().equals("potion")) ctx.check("potion level is effect level", rune.in("level").getDisplayName().getString().equals("药效等级"));
                        }
                    }
                    ctx.check("all registered spell nodes are localized", count == view.getGraph().getSupportNodes().size());
                    for (var shape : ConstructShape.values()) {
                        ctx.check("translated shape choice " + shape.name(), Language.getInstance().has("dev.logos.lang.ConstructShape." + shape.name()));
                    }
                    ctx.check("library inspection leaves the drawing intact", graph.getNodeModels().size() == before);
                    ctx.log("Localized spell library nodes: " + count);
                    try {
                        Path output = FMLPaths.GAMEDIR.get().resolve("../build/ldlib2-uitest/parameter-manifest.json").normalize();
                        Files.createDirectories(output.getParent());
                        Files.writeString(output,
                                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(manifest));
                    } catch (IOException ex) {
                        throw new IllegalStateException("Could not export parameter labels", ex);
                    }
                    view.itemLibrary.show(40, 40, item -> {});
                    view.itemLibrary.nodeTree.expandAllNodesIf(view.itemLibrary.nodeTree.getRoot(), node -> true);
                })
                .ticks(5)
                .screenshot("chinese_node_library")
                .step("search by Chinese name", ctx -> view(ctx).itemLibrary.searchField.setValue("周遭方块", true))
                .ticks(5)
                .step("Chinese search finds blocks", ctx -> ctx.check("Chinese search result is localized",
                        view(ctx).itemLibrary.searchTree.getRoot().getChildren().stream()
                                .anyMatch(n -> n.getKey().getDisplayName().getString().equals("周遭方块"))))
                .screenshot("chinese_node_search")
                .step("search by the old English name", ctx -> view(ctx).itemLibrary.searchField.setValue("blocks", true))
                .ticks(5)
                .step("English search still finds blocks", ctx -> {
                    ctx.check("English search retains Chinese labels", view(ctx).itemLibrary.searchTree.getRoot().getChildren().stream()
                            .anyMatch(n -> n.getKey().getDisplayName().getString().equals("周遭方块")));
                    view(ctx).itemLibrary.hide();
                    view(ctx).getGraph().graphModel.createNodeModel(new RuneNodes.Beam(), new Vector2f(1800, 0));
                    view(ctx).getGraph().graphModel.createNodeModel(new RuneNodes.Construct(), new Vector2f(2050, 0));
                })
                .ticks(5)
                .step("frame translated shape parameters", ctx -> view(ctx).fitGraph(1780, -20, 2300, 430, 8))
                .ticks(5)
                .screenshot("translated_parameters")
                .step("create a paper note", ctx -> {
                    view(ctx).dispatchCommand(new GraphCommands.CreateStickyNoteCommand(new Vector2f(4000, 0)));
                    view(ctx).getGraph().graphModel.getStickyNoteModels().getFirst().setContent("伤害触发说明\n命中后造成伤害");
                })
                .ticks(5)
                .step("frame the note", ctx -> view(ctx).fitGraph(3980, -20, 4200, 120, 8))
                .ticks(5)
                .click(".__sticky-note_folder-icon__")
                .ticks(3)
                .step("check the collapsed label", ctx -> {
                    StickyNoteElement note = ctx.ui().select(".__sticky-note__", StickyNoteElement.class).findFirst().orElseThrow();
                    ctx.check("note is collapsed", note.getModel().isCollapsed());
                    ctx.check("collapsed label is compact", Math.abs(note.getSizeHeight() - 14) < 1);
                    Label summary = note.select(".spell-note-summary", Label.class).findFirst().orElseThrow();
                    ctx.check("collapsed note shows its first line", summary.getText().getString().equals("伤害触发说明"));
                    ctx.check("collapsed summary is visible", summary.isDisplayed());
                })
                .screenshot("collapsed_paper_note")
                .click(".__sticky-note_folder-icon__")
                .ticks(3)
                .step("check unfolding preserves the sheet", ctx -> {
                    StickyNoteElement note = ctx.ui().select(".__sticky-note__", StickyNoteElement.class).findFirst().orElseThrow();
                    ctx.check("note unfolds", !note.getModel().isCollapsed());
                    ctx.check("original note height restored", Math.abs(note.getSizeHeight() - note.getModel().getSize().y) < 1);
                    ctx.check("note content retained", note.getModel().getContent().equals("伤害触发说明\n命中后造成伤害"));
                    ctx.check("summary is hidden on the open sheet", !note.select(".spell-note-summary", Label.class)
                            .findFirst().orElseThrow().isDisplayed());
                })
                .screenshot("expanded_paper_note")
                .closeScreen()
                .step("restore the original language", ctx -> {
                    Minecraft.getInstance().getLanguageManager().setSelected(ctx.get("original_language"));
                    ctx.put("language_reload", Minecraft.getInstance().reloadResourcePacks());
                })
                .waitUntil("original language restored", SpellEditorLocalizationScenario::reloaded);
    }
}
