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

import dev.logos.client.ClientHooks;
import dev.logos.client.GuidePage;
import dev.logos.client.GuideScreen;

/**
 * Real-client test of 逻各斯手记: it opens at its first page with the starter chapter listed; the contents open a page;
 * a link in the text is followed and back returns; a page's recipe is drawn from the recipes the server sent; search
 * finds pages by their text. Run: {@code gradlew runClient -PldTest=logos_guide -PldTestHeadless}.
 */
@LDLRegisterClient(name = "logos_guide", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class GuideScenario implements UIScenario {
    private static final String WELCOME = "logos:basics/welcome", WANDS = "logos:basics/wand_and_pages",
            FIRST = "logos:basics/first_spell", CASTING = "logos:basics/casting_and_mana";

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    private static String title(TestContext ctx) {
        UIElement row = ctx.ui().select("#guide_title").findFirst().orElseThrow();
        for (UIElement child : row.getChildren()) if (child instanceof Label l) return l.getText().getString();
        return "";
    }

    /** The starter chapter's pages in the contents. */
    private static long entries(TestContext ctx) {
        return ctx.ui().select(".guide-toc-entry").filter(e -> e.getId().startsWith("toc_logos_basics_")).count();
    }

    /** Where on screen the link being followed is drawn, found in one step and clicked in the next two. */
    private static final float[] LINK_AT = new float[2];

    /** Finds the first run of text on the page that links to {@code link}, and moves the pointer over it. */
    private static void pointAtLink(TestContext ctx, String link) {
        for (UIElement e : ctx.ui().select("#guide_page").findFirst().orElseThrow().getChildren()) {
            if (find(e, link)) {
                ctx.input().moveTo(LINK_AT[0], LINK_AT[1]);
                return;
            }
        }
        throw new AssertionError("no link to " + link + " on the page");
    }

    private static boolean find(UIElement e, String link) {
        if (e instanceof GuidePage.LinkedText text) {
            float y = text.getContentY() + 4;
            for (float x = text.getContentX(); x < text.getContentX() + text.getContentWidth(); x += 2) {
                if (link.equals(GuidePage.LinkedText.link(text.styleAt(x, y)))) {
                    LINK_AT[0] = x + 1;
                    LINK_AT[1] = y;
                    return true;
                }
            }
        }
        for (UIElement child : e.getChildren()) if (find(child, link)) return true;
        return false;
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("open the journal", ctx -> ClientHooks.openGuide())
                .awaitModularUI()
                .ticks(10)
                .step("it opens at its first page, with the starter chapter listed", ctx -> {
                    ctx.check("first page shown: " + GuideScreen.lastRead(), WELCOME.equals(GuideScreen.lastRead()));
                    ctx.check("its title: " + title(ctx), title(ctx).contains("Logos"));
                    ctx.check("four starter pages listed (" + entries(ctx) + ")", entries(ctx) == 4);
                    ctx.check("a ribbon for the chapter", ctx.ui().select("#ribbon_logos_basics").count() == 1);
                })
                .screenshot("guide_welcome")
                .click("#toc_logos_basics_wand_and_pages")
                .ticks(5)
                .step("the contents open a page, drawing its recipes", ctx -> {
                    ctx.check("wand page shown: " + GuideScreen.lastRead(), WANDS.equals(GuideScreen.lastRead()));
                    long cards = ctx.ui().select(".guide-recipe").count();
                    ctx.check("its three recipes are drawn (" + cards + ")", cards == 3);
                    ctx.check("seven wood wands in a row", ctx.ui().select(".guide-items").findFirst().orElseThrow().getChildren().size() == 7);
                })
                .screenshot("guide_wand_and_pages")
                .hover("#guide_title_icon")
                .ticks(3)
                .screenshot("guide_item_tooltip")
                .step("point at the link to the first spell", ctx -> pointAtLink(ctx, "first_spell"))
                .ticks(2)
                .step("press on it", ctx -> ctx.input().mouseDown(LINK_AT[0], LINK_AT[1], 0))
                .step("let go", ctx -> ctx.input().mouseUp(LINK_AT[0], LINK_AT[1], 0))
                .ticks(5)
                .step("the link is followed", ctx -> ctx.check("first spell shown: " + GuideScreen.lastRead(), FIRST.equals(GuideScreen.lastRead())))
                .screenshot("guide_first_spell")
                .click("#guide_back")
                .ticks(5)
                .step("back returns to the wand page", ctx -> ctx.check("wand page again: " + GuideScreen.lastRead(), WANDS.equals(GuideScreen.lastRead())))
                .step("search for 蓄力", ctx -> ctx.ui().select("#guide_search", TextField.class).findFirst().orElseThrow().setValue("蓄力", true))
                .ticks(5)
                .step("search finds the casting page by its text", ctx -> ctx.check("casting page found",
                        ctx.ui().select("#toc_" + GuideScreen.safe(CASTING)).count() == 1))
                .screenshot("guide_search")
                .click("#toc_" + GuideScreen.safe(CASTING))
                .ticks(5)
                .screenshot("guide_casting")
                .closeScreen();
    }
}
