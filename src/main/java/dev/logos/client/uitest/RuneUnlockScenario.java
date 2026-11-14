package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
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

import dev.logos.LogosMod;
import dev.logos.client.ClientHooks;
import dev.logos.graph.LogosNode;
import dev.logos.item.SpellPages;
import dev.logos.lang.Unlocks;
import dev.logos.runtime.RuneKnowledge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * Real-client test of 符文解锁 in the workbench: in survival, a page's runes the player has not learned are drawn
 * garbled (title and ports) while learned ones read; the library lists the unlearned garbled and no search finds
 * them; a template using them is refused before it is sent. Learning one makes it readable.
 * Run: {@code gradlew runClient -PldTest=logos_rune_unlocks -PldTestHeadless}.
 */
@LDLRegisterClient(name = "logos_rune_unlocks", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class RuneUnlockScenario implements UIScenario {
    private static final String HALF_KNOWN = "(spell :name \"半懂\" :mana 30 (projectile :motion homing (on-hit (damage target 5 fire) (freeze target 2))))";

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    private static GraphView view(TestContext ctx) {
        return ctx.ui().select("#spell_navigation", GraphPreview.class).findFirst().orElseThrow().graphView;
    }

    /** Whether any text drawn on the canvas node for {@code word} is obfuscated. */
    private static boolean garbled(TestContext ctx, String word) {
        GraphView view = view(ctx);
        for (var model : view.getGraph().graphModel.getNodeModels()) {
            if (!(model instanceof ICustomNodeModel c && c.getNode() instanceof LogosNode n && n.word().equals(word))) continue;
            UIElement element = view.getModelElement(model);
            return element != null && anyObfuscated(element);
        }
        throw new IllegalStateException("no " + word + " node on the canvas");
    }

    private static boolean anyObfuscated(UIElement e) {
        if (e instanceof TextElement t && t.getText() != null && t.getText().getStyle().isObfuscated()) return true;
        for (UIElement child : e.getChildren()) if (anyObfuscated(child)) return true;
        return false;
    }

    private static String status(TestContext ctx) {
        return ctx.ui().select("#status", Label.class).findFirst().orElseThrow().getText().getString();
    }

    /** Whether the status line says the drawing adds words not learned, in whichever language is loaded. */
    private static boolean warns(TestContext ctx) {
        String line = net.minecraft.network.chat.Component.translatable("logos.unlock.editor", "#").getString();
        return status(ctx).contains(line.substring(0, line.indexOf('#')));
    }

    private static long shownEntries(TestContext ctx) {
        return ctx.ui().select(".spell-library-entry").filter(UIElement::isDisplayed).count();
    }

    private static void search(TestContext ctx, String query) {
        ctx.ui().select("#spell_library_search", TextField.class).findFirst().orElseThrow().setValue(query, true);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .server("forget everything learned", sc -> {
                    for (String key : Unlocks.all()) RuneKnowledge.forget(sc.player(), key);
                })
                .waitUntil("the client holds the player to learning", ctx -> {
                    var p = Minecraft.getInstance().player;
                    return p != null && !p.hasInfiniteMaterials() && !RuneKnowledge.knows(p, Unlocks.rune("freeze"));
                })
                .step("open a page that says more than the player knows", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(HALF_KNOWN)))
                .awaitModularUI()
                .ticks(10)
                .step("what is learned reads and the rest is garbled", ctx -> {
                    ctx.check("freeze is garbled", garbled(ctx, "freeze"));
                    ctx.check("projectile reads", !garbled(ctx, "projectile"));
                    ctx.check("damage reads", !garbled(ctx, "damage"));
                    ctx.check("the page may keep its own words: " + status(ctx), status(ctx).contains("✓") && !warns(ctx));
                })
                .screenshot("sealed_nodes")
                .click("#tab_library")
                .ticks(5)
                .step("the library lists unlearned runes garbled and hides them from search", ctx -> {
                    long sealed = ctx.ui().select(".spell-library-sealed").count();
                    ctx.check("most runes are sealed (" + sealed + ")", sealed > 100);
                    ctx.check("projectile and damage are not", ctx.ui().select(".spell-library-entry").count() - sealed >= 2);
                })
                .screenshot("sealed_library")
                .step("search for damage", ctx -> search(ctx, "damage"))
                .ticks(5)
                .step("a learned rune is found", ctx -> ctx.check("damage found (" + shownEntries(ctx) + ")", shownEntries(ctx) >= 1))
                .step("search for freeze", ctx -> search(ctx, "freeze"))
                .ticks(5)
                .step("an unlearned rune is not", ctx -> ctx.check("freeze hidden (" + shownEntries(ctx) + ")", shownEntries(ctx) == 0))
                .closeScreen()
                .ticks(3)
                .step("open a blank page", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, new ItemStack(LogosMod.SPELL_PAGE.get())))
                .awaitModularUI()
                .ticks(5)
                .click("#load_template")
                .ticks(10)
                .step("a template using unlearned words is marked unwritable", ctx -> ctx.check("the status warns: " + status(ctx), warns(ctx)))
                .screenshot("sealed_template")
                .closeScreen()
                .server("learn freeze", sc -> RuneKnowledge.learn(sc.player(), Unlocks.rune("freeze")))
                .waitUntil("the client knows freeze", ctx -> RuneKnowledge.knows(Minecraft.getInstance().player, Unlocks.rune("freeze")))
                .step("open the page again", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.fitting(HALF_KNOWN)))
                .awaitModularUI()
                .ticks(10)
                .step("freeze reads once learned", ctx -> ctx.check("freeze reads", !garbled(ctx, "freeze")))
                .screenshot("learned_freeze")
                .closeScreen()
                .server("forget freeze again", sc -> RuneKnowledge.forget(sc.player(), Unlocks.rune("freeze")))
                .setGameMode(GameType.CREATIVE);
    }
}
