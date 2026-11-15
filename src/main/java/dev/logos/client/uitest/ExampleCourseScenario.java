package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphPreview;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.item.SpellPages;
import dev.logos.lang.Examples;
import dev.logos.lang.SourceNotes;
import net.minecraft.core.BlockPos;

/**
 * Real-client look at grand example spells opened in the workbench: every ;; note of theirs is drawn, one region per
 * annotated part and one sticky note per note with text. Run: {@code gradlew runClient -PldTest=logos_example_course -PldTestHeadless}.
 */
@LDLRegisterClient(name = "logos_example_course", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ExampleCourseScenario implements UIScenario {
    private static final String[] SHOWN = {"starsplit-judgment", "absolute-zero", "bulwark"};

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    private static GraphView view(TestContext ctx) {
        return ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
    }

    @Override
    public void define(ScenarioBuilder s) {
        for (String id : SHOWN) {
            String src = Examples.ALL.get(id);
            var notes = SourceNotes.read(src).all();
            long regions = notes.size() - 1, stickies = notes.stream().filter(n -> !n.text().isBlank()).count();
            s.step("open " + id, ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(src)))
                    .awaitModularUI()
                    .ticks(10)
                    .step(id + " draws all its notes", ctx -> {
                        var model = view(ctx).getGraph().graphModel;
                        ctx.check(id + ": " + regions + " regions (" + model.getPlacematModels().size() + ")", model.getPlacematModels().size() == regions);
                        ctx.check(id + ": " + stickies + " sticky notes (" + model.getStickyNoteModels().size() + ")", model.getStickyNoteModels().size() == stickies);
                    })
                    .screenshot(id.replace('-', '_'))
                    .step("zoom in on the top of " + id, ctx -> {
                        var model = view(ctx).getGraph().graphModel;
                        float top = (float) model.getStickyNoteModels().stream().mapToDouble(n -> n.getPosition().y).min().orElse(0);
                        float left = (float) model.getPlacematModels().stream().mapToDouble(p -> p.getPosition().x).min().orElse(0);
                        view(ctx).fitGraph(left - 20, top - 20, left + 1400, top + 900, 0);
                    })
                    .ticks(5)
                    .screenshot(id.replace('-', '_') + "_close")
                    .closeScreen()
                    .ticks(2);
        }
    }
}
