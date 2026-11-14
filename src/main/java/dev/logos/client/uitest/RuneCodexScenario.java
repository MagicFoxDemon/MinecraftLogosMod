package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.GuideScreen;
import dev.logos.guide.RuneCodex;
import dev.logos.lang.Unlocks;
import dev.logos.runtime.RuneKnowledge;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;

/**
 * Real-client test of 符文图鉴 in 逻各斯手记: in survival, a word not learned has no page in the contents and is found by
 * no search, and the overview shows it as a black square; a learned one opens its page from its square; a link to a word
 * not learned opens a blank page; in creative every word is there.
 * Run: {@code gradlew runClient -PldTest=logos_rune_codex -PldTestHeadless}.
 */
@LDLRegisterClient(name = "logos_rune_codex", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class RuneCodexScenario implements UIScenario {
    private static final String FREEZE = RuneCodex.page(Unlocks.rune("freeze")), HEAL = RuneCodex.page(Unlocks.rune("heal")),
            FIRE = "logos:runes/element_fire", FROST = "logos:runes/element_frost";

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    private static long count(TestContext ctx, String selector) {
        return ctx.ui().select(selector).count();
    }

    private static boolean listed(TestContext ctx, String page) {
        return count(ctx, "#toc_" + GuideScreen.safe(page)) == 1;
    }

    private static String title(TestContext ctx) {
        UIElement row = ctx.ui().select("#guide_title").findFirst().orElseThrow();
        for (UIElement child : row.getChildren()) if (child instanceof Label l) return l.getText().getString();
        return "";
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .server("know only the start and freeze, fire and homing", sc -> {
                    for (String key : Unlocks.all()) RuneKnowledge.forget(sc.player(), key);
                    for (String key : new String[]{Unlocks.rune("freeze"), "element:fire", "motion:homing"}) RuneKnowledge.learn(sc.player(), key);
                })
                .waitUntil("the client knows freeze and is held to learning", ctx -> {
                    var p = Minecraft.getInstance().player;
                    return p != null && !p.hasInfiniteMaterials() && RuneKnowledge.knows(p, Unlocks.rune("freeze")) && !RuneKnowledge.knows(p, Unlocks.rune("heal"));
                })
                .step("open the codex's overview", ctx -> GuideScreen.open(RuneCodex.OVERVIEW))
                .awaitModularUI()
                .ticks(10)
                .step("words not learned are black squares and have no page in the contents", ctx -> {
                    long locked = count(ctx, ".guide-rune-locked"), learned = count(ctx, "#rune_overview .guide-rune");
                    ctx.check("most words are black (" + locked + ")", locked > 100);
                    ctx.check("learned words and those no one learns are drawn (" + learned + ")", learned >= 10);
                    ctx.check("damage and freeze are listed", listed(ctx, RuneCodex.page(Unlocks.rune("damage"))) && listed(ctx, FREEZE));
                    ctx.check("fire is listed, frost is not", listed(ctx, FIRE) && !listed(ctx, FROST));
                    ctx.check("heal is not listed", !listed(ctx, HEAL));
                })
                .screenshot("codex_overview")
                .click("#rune_" + GuideScreen.safe(FREEZE))
                .ticks(5)
                .step("a learned word's square opens its page", ctx -> {
                    ctx.check("freeze shown: " + GuideScreen.lastRead(), FREEZE.equals(GuideScreen.lastRead()));
                    ctx.check("its page has its help", count(ctx, "#guide_page") == 1 && !title(ctx).isEmpty() && !title(ctx).contains("？"));
                })
                .screenshot("codex_rune_page")
                .click("#toc_" + GuideScreen.safe(FIRE))
                .ticks(5)
                .screenshot("codex_element_page")
                .step("search for heal", ctx -> ctx.ui().select("#guide_search", TextField.class).findFirst().orElseThrow().setValue("heal", true))
                .ticks(5)
                .step("a word not learned is found by no search", ctx -> ctx.check("heal not found", !listed(ctx, HEAL)))
                .step("clear the search", ctx -> ctx.ui().select("#guide_search", TextField.class).findFirst().orElseThrow().setValue("", true))
                .closeScreen()
                .step("follow a link to heal", ctx -> GuideScreen.open(HEAL))
                .awaitModularUI()
                .ticks(5)
                .step("its page is blank and unnamed", ctx -> ctx.check("title is ？？？: " + title(ctx), title(ctx).contains("？？？")))
                .screenshot("codex_locked_page")
                .closeScreen()
                .setGameMode(GameType.CREATIVE)
                .waitUntil("creative knows every word", ctx -> RuneKnowledge.knows(Minecraft.getInstance().player, Unlocks.rune("heal")))
                .step("open the overview in creative", ctx -> GuideScreen.open(RuneCodex.OVERVIEW))
                .awaitModularUI()
                .ticks(10)
                .step("in creative no square is black", ctx -> {
                    ctx.check("no black squares (" + count(ctx, ".guide-rune-locked") + ")", count(ctx, ".guide-rune-locked") == 0);
                    ctx.check("heal is listed", listed(ctx, HEAL));
                })
                .screenshot("codex_overview_creative")
                .closeScreen()
                .server("forget what was taught", sc -> {
                    for (String key : new String[]{Unlocks.rune("freeze"), "element:fire", "motion:homing"}) RuneKnowledge.forget(sc.player(), key);
                });
    }
}
