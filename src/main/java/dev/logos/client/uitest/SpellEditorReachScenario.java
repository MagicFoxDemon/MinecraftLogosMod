package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
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
 * The workbench's reach tick boxes: a field of healing shows the reach read off it (itself and a group) with no box
 * ticked; ticking one declares the reach instead, and the overview follows.
 */
@LDLRegisterClient(name = "logos_spell_editor_reach", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SpellEditorReachScenario implements UIScenario {
    private static final String FIELD_HEAL = "(spell :name \"群愈\" :mana 50 (field :life 2 (on-enter (heal target 1))))";
    private static final String DECLARED = "(spell :name \"护身\" :reach self+group :mana 20 (heal (master) 2))";

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos").guiScale(3);
    }

    private static Toggle box(TestContext ctx, String id) {
        return ctx.ui().select("#" + id, Toggle.class).findFirst().orElseThrow();
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open the workbench on a page that declares its reach", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(DECLARED)))
                .awaitModularUI()
                .ticks(10)
                .step("its declared reach is ticked", ctx -> {
                    ctx.check("reach_self is on", box(ctx, "reach_self").isOn());
                    ctx.check("reach_group is on", box(ctx, "reach_group").isOn());
                    ctx.check("reach_one is off", !box(ctx, "reach_one").isOn());
                })
                .checkTextContains("#overview", "治疗（自己·群体）")
                .step("open the workbench on a field of healing", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(FIELD_HEAL)))
                .awaitModularUI()
                .ticks(10)
                .checkTextContains("#overview", "治疗（自己·群体）")
                .step("no box is ticked while the reach is inferred", ctx -> {
                    for (String id : new String[] {"reach_self", "reach_one", "reach_group"}) ctx.check(id + " is off", !box(ctx, id).isOn());
                })
                .screenshot("reach_inferred")
                .step("tick 单体", ctx -> box(ctx, "reach_one").setOn(true, true))
                .ticks(5)
                .checkTextContains("#overview", "治疗（单体）")
                .step("only 单体 is ticked", ctx -> {
                    ctx.check("reach_one is on", box(ctx, "reach_one").isOn());
                    ctx.check("reach_self is off", !box(ctx, "reach_self").isOn());
                })
                .screenshot("reach_declared");
    }
}
