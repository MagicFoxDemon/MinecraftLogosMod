package dev.logos.client.uitest;

import java.util.concurrent.CompletableFuture;

import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.graph.GraphCompiler;
import dev.logos.graph.RuneNode;
import dev.logos.graph.RuneNodes;
import dev.logos.graph.SpellGraph;
import dev.logos.item.SpellPages;
import dev.logos.lang.Examples;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** The same-species healing graph exposes a typed species node in the existing parchment style. */
@LDLRegisterClient(name = "logos_species", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SpeciesScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions options) { options.tags("logos").guiScale(2); }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("load Chinese labels", ctx -> {
                    var mc = Minecraft.getInstance();
                    ctx.put("original_language", mc.getLanguageManager().getSelected());
                    mc.getLanguageManager().setSelected("zh_cn");
                    ctx.put("language_reload", mc.reloadResourcePacks());
                })
                .waitUntil("Chinese resources loaded", ctx -> {
                    CompletableFuture<Void> reload = ctx.get("language_reload");
                    return reload.isDone() && !reload.isCompletedExceptionally();
                })
                .step("open Circle of Renewal", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO,
                        SpellPages.fitting(Examples.ALL.get("npc-group-heal"))))
                .awaitModularUI().ticks(10)
                .step("species is connected and explained", ctx -> {
                    var view = ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
                    var graph = (SpellGraph) view.getGraph();
                    var node = graph.getNodes().stream().filter(n -> n instanceof RuneNodes.Species)
                            .map(n -> (RuneNodes.Species) n).findFirst().orElseThrow();
                    ctx.check("Chinese species name", Component.translatable(node.nameKey()).getString().equals("族属"));
                    ctx.check("species description", !node.doc().contains("尚待补充") && node.doc().contains("生物注册 ID"));
                    ctx.check("species output is wired", node.out(RuneNode.OUT).isConnected());
                    var compiled = GraphCompiler.compile(graph);
                    ctx.check("same-species filter survives", compiled.ok() && compiled.source().contains("(kind (species caster))"));
                }).screenshot("species_healing_graph")
                .closeScreen()
                .step("restore language", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.getLanguageManager().setSelected(ctx.get("original_language"));
                    mc.reloadResourcePacks();
                });
    }
}
