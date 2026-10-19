package dev.logos.client.uitest;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.GsonBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.GraphNodeCreationData;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.NodeModelLibraryItem;
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
import dev.logos.item.SpellPages;
import dev.logos.lang.Examples;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/** Exercises every library description and variable input count with real Chinese client resources. */
@LDLRegisterClient(name = "logos_editor_descriptions", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SpellEditorDescriptionsScenario implements UIScenario {
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

    private static void checkField(TestContext ctx, LogosNode node, Component displayName) {
        String label = displayName.getString().replaceFirst("^[^\\p{IsHan}]*", "");
        if (label.isBlank()) return;
        ctx.check("separate explanation for " + node.word() + "/" + label,
                node.doc().contains("\n" + label + "："));
    }

    private static void selectResult(TestContext ctx, String name) {
        var library = view(ctx).itemLibrary;
        library.searchTree.setSelected(List.of(library.searchTree.getRoot().getChildren().stream()
                .filter(item -> item.getKey().getDisplayName().getString().equals(name)).findFirst().orElseThrow()), true);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("load Chinese descriptions", ctx -> {
                    var mc = Minecraft.getInstance();
                    ctx.put("original_language", mc.getLanguageManager().getSelected());
                    mc.getLanguageManager().setSelected("zh_cn");
                    ctx.put("language_reload", mc.reloadResourcePacks());
                })
                .waitUntil("Chinese resources loaded", SpellEditorDescriptionsScenario::reloaded)
                .step("open editor", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO,
                        SpellPages.fitting(Examples.ALL.get("fireball"))))
                .awaitModularUI()
                .ticks(10)
                .step("check all descriptions and actual field labels", ctx -> {
                    var graph = view(ctx).getGraph().graphModel;
                    int before = graph.getNodeModels().size();
                    List<Map<String, String>> manifest = new ArrayList<>();
                    for (var item : view(ctx).itemLibrary.getAllItems().toList()) {
                        if (!(item instanceof NodeModelLibraryItem entry)) continue;
                        if (!(entry.createNode(GraphNodeCreationData.ofOrphan(graph)) instanceof ICustomNodeModel model)
                                || !(model.getNode() instanceof LogosNode node)) continue;
                        String doc = node.doc();
                        ctx.check("authored description " + node.word(), !doc.isBlank() && !doc.contains("尚待补充"));
                        String heading = node.category().label() + "\n" + Component.translatable(node.nameKey()).getString() + "\n\n";
                        ctx.check("localized heading without source name " + node.word(),
                                ((Label) node.createDescriptionUI()).getText().getString().equals(heading + doc));
                        node.model().getInputsById().values().forEach(port -> checkField(ctx, node, port.getDisplayName()));
                        node.model().getOutputsById().forEach((id, port) -> {
                            if (!id.equals(RuneNode.OUT)) checkField(ctx, node, port.getDisplayName());
                        });
                        node.getNodeOptions().forEach(option -> checkField(ctx, node, option.getDisplayName()));
                        if (node instanceof RuneNode rune && rune.rune().params.stream().anyMatch(p -> p.rest())) {
                            node.setOption(RuneNode.COUNT, 3);
                            ctx.check("variable count adds third explanation " + node.word(), node.doc().contains(" 3："));
                            node.model().getInputsById().values().forEach(port -> checkField(ctx, node, port.getDisplayName()));
                            node.setOption(RuneNode.COUNT, 1);
                            ctx.check("variable count removes obsolete explanations " + node.word(), !node.doc().contains(" 2："));
                        }
                        if (node instanceof RuneNode rune) ctx.check("source signature omitted " + node.word(),
                                !doc.contains(rune.rune().signature()));
                        manifest.add(Map.of("node", node.word(), "name", Component.translatable(node.nameKey()).getString(), "description", doc));
                    }
                    ctx.check("every supported node described", manifest.size() == view(ctx).getGraph().getSupportNodes().size());
                    ctx.check("description inspection leaves graph intact", graph.getNodeModels().size() == before);
                    try {
                        var output = FMLPaths.GAMEDIR.get().resolve("../build/ldlib2-uitest/description-manifest.json").normalize();
                        Files.createDirectories(output.getParent());
                        Files.writeString(output, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(manifest));
                    } catch (IOException ex) {
                        throw new IllegalStateException("Could not export descriptions", ex);
                    }
                    view(ctx).itemLibrary.show(40, 40, item -> {});
                    view(ctx).itemLibrary.searchField.setValue("炼成体", true);
                })
                .ticks(5)
                .step("select the construct description", ctx -> selectResult(ctx, "炼成体"))
                .ticks(5)
                .screenshot("construct_description")
                .step("scroll the long description", ctx -> {
                    var description = view(ctx).itemLibrary.descriptionView;
                    ctx.check("long help has scrollable content", description.verticalScroller.getMaxValue() > 0);
                    description.verticalScroller.setNormalizedValue(1, true);
                })
                .ticks(5)
                .step("check description scroll reaches bottom", ctx -> ctx.check("help can reach final events and notes",
                        view(ctx).itemLibrary.descriptionView.verticalScroller.getNormalizedValue() > 0.99))
                .screenshot("construct_events_and_notes")
                .step("find damage", ctx -> view(ctx).itemLibrary.searchField.setValue("伤害", true))
                .ticks(5)
                .step("select damage description", ctx -> selectResult(ctx, "伤害"))
                .ticks(5)
                .screenshot("damage_description")
                .step("find infuse", ctx -> view(ctx).itemLibrary.searchField.setValue("灌魔", true))
                .ticks(5)
                .step("select infuse description", ctx -> selectResult(ctx, "灌魔"))
                .ticks(5)
                .screenshot("infuse_block_description")
                .closeScreen()
                .step("restore language", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.getLanguageManager().setSelected(ctx.get("original_language"));
                    ctx.put("language_reload", mc.reloadResourcePacks());
                })
                .waitUntil("original language restored", SpellEditorDescriptionsScenario::reloaded);
    }
}
