package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.wire.WireModel;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.graph.LogosNode;
import dev.logos.graph.SpecialNodes;
import dev.logos.item.SpellPages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Selected context wires show their use-site meaning; nodes keep their generic documentation. */
@LDLRegisterClient(name = "logos_context_wire", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ContextWireScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) { o.tags("logos").guiScale(2); }

    private static GraphView view(TestContext ctx) {
        return ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
    }

    private static Label help(TestContext ctx) {
        return ctx.ui().select("#selected_rune_description", Label.class).findFirst().orElseThrow();
    }

    private static WireModel contextWire(TestContext ctx) {
        return view(ctx).getGraph().graphModel.getWireModels().stream().filter(w -> w.getFromPort() != null
                && LogosNode.of(w.getFromPort()) instanceof SpecialNodes.Here).findFirst().orElseThrow();
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open nested events", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(
                        "(spell :name \"Context\" :mana 100 (field (on-tick (projectile (on-hit (echo (above here 2)))))))")))
                .awaitModularUI().ticks(10)
                .step("select context wire", ctx -> view(ctx).addSelected(contextWire(ctx)))
                .ticks(2)
                .step("explain the nested use", ctx -> {
                    String text = help(ctx).getValue().getString();
                    ctx.check("wire help visible", help(ctx).isDisplayed());
                    ctx.check("collision position, not outer field center", text.contains(Component.translatable("logos.editor.context.meaning.projectile_contact").getString()));
                    ctx.check("execution route included", text.contains("projectile") && text.contains("field"));
                    ctx.check("localization resolved", !text.contains("logos.editor.context."));
                }).screenshot("context_wire_nested")
                .step("select the context node instead", ctx -> {
                    var source = LogosNode.of(contextWire(ctx).getFromPort());
                    view(ctx).clearAllSelected();
                    view(ctx).addSelected(source.model());
                }).ticks(2)
                .step("node help is generic", ctx -> {
                    var source = LogosNode.of(contextWire(ctx).getFromPort());
                    ctx.check("generic description restored", help(ctx).getValue().getString().equals(source.title() + "\n\n" + source.doc()));
                })
                .step("select ordinary flow wire", ctx -> {
                    view(ctx).clearAllSelected();
                    view(ctx).addSelected(view(ctx).getGraph().graphModel.getWireModels().stream().filter(w -> w.getFromPort() != null
                            && LogosNode.of(w.getFromPort()) instanceof SpecialNodes.SpellRoot).findFirst().orElseThrow());
                }).ticks(2)
                .step("ordinary wire has no context explanation", ctx -> ctx.check("help hidden", !help(ctx).isDisplayed()))
                .step("clear selection", ctx -> view(ctx).clearAllSelected()).ticks(2)
                .step("old meaning cleared", ctx -> ctx.check("empty help", help(ctx).getValue().getString().isEmpty()))
                .closeScreen();
    }
}
