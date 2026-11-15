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
import net.minecraft.core.BlockPos;

/**
 * Real-client look at a spell whose source carries ;; notes, opened in the workbench: the spell's note above its root,
 * and a titled region with its sticky note round each annotated part, nested where the source nests them.
 * Run: {@code gradlew runClient -PldTest=logos_example_notes -PldTestHeadless}.
 */
@LDLRegisterClient(name = "logos_example_notes", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ExampleNotesScenario implements UIScenario {
    private static final String NOTED = String.join("\n",
            ";; 陨星天降",
            ";; 在视线落点预警片刻，随后召下一颗燃烧的陨星，砸伤并点燃落点周围的敌人。",
            "(spell :name \"陨星天降\" :mana 90",
            "  ;; 锁定落点",
            "  ;; 记住视线 40 格内看到的位置，之后的预警与陨星都围绕这一点展开。",
            "  (let $p (look-point 40)",
            "    ;; 预警",
            "    ;; 在落点展开 1.5 秒的领域，提示敌我即将受到打击。",
            "    (field :at $p :radius 5 :life 1.5 :every 2)",
            "    ;; 陨星坠落",
            "    ;; 1.5 秒后自落点上方 16 格射出抛物线魔弹，消散时结算范围伤害。",
            "    (delay 1.5",
            "      (projectile :from (above $p 16) :dir down :speed 1.2 :motion arc :size 0.9",
            "        (on-expire",
            "          ;; 冲击",
            "          ;; 对半径 5 格内的每个敌人造成物理与火焰伤害，点燃并击飞。",
            "          (each (enemies :radius 5)",
            "            (damage target major physical)",
            "            (damage target standard fire)",
            "            (ignite target 4)",
            "            (launch target 0.8)))))))");

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    private static GraphView view(TestContext ctx) {
        return ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open a page written with notes", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(NOTED)))
                .awaitModularUI()
                .ticks(10)
                .step("its notes are drawn as regions and sticky notes", ctx -> {
                    var model = view(ctx).getGraph().graphModel;
                    ctx.check("four regions (" + model.getPlacematModels().size() + ")", model.getPlacematModels().size() == 4);
                    ctx.check("five sticky notes (" + model.getStickyNoteModels().size() + ")", model.getStickyNoteModels().size() == 5);
                })
                .screenshot("example_notes")
                .step("zoom in on the top of the spell", ctx -> {
                    var model = view(ctx).getGraph().graphModel;
                    var warn = model.getPlacematModels().stream().filter(p -> p.getName().equals("预警")).findFirst().orElseThrow();
                    var top = model.getStickyNoteModels().stream().mapToDouble(n -> n.getPosition().y).min().orElse(0);
                    view(ctx).fitGraph(warn.getPosition().x - 320, (float) top - 10, warn.getPosition().x + warn.getSize().x + 20,
                            warn.getPosition().y + warn.getSize().y + 10, 0);
                })
                .ticks(5)
                .screenshot("example_notes_close")
                .closeScreen();
    }
}
