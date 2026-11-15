package dev.logos.client.uitest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

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
import dev.logos.client.SageClient;
import dev.logos.client.SageToast;
import dev.logos.sage.SageModel;
import dev.logos.sage.SageSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * Real-client UI test of leaving 书灵 mid-thought: ask, close the inscription table while the sage
 * is still thinking, open it again and find the same conversation still thinking; close it once
 * more, let the answer land, and see the note in the corner; back at the table the answer is there.
 */
@LDLRegisterClient(name = "logos_sage_away", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SageAwayScenario implements UIScenario {
    private static final String WISH = "向前方发射一枚追踪最近敌人的火球";
    private static final String SPELL = "(spell :name \"追踪火球\" :mana 40\n  (projectile :motion homing\n    (on-hit (damage target standard fire))))";
    private static Path oldDir;
    private static CountDownLatch release;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    /** Holds its first answer until the scenario lets it go, then hands the spell in. */
    private static SageModel held() {
        AtomicInteger round = new AtomicInteger();
        return (messages, tools) -> {
            if (round.getAndIncrement() == 0) {
                release.await();
                return new SageModel.Reply("", List.of(new SageModel.ToolCall("a", "compile", "{\"source\":" + quote(SPELL) + "}")));
            }
            return new SageModel.Reply("", List.of(new SageModel.ToolCall("b", "submit_spell",
                    "{\"source\":" + quote(SPELL) + ",\"explanation\":" + quote("写好啦，一发追踪火球。") + "}")));
        };
    }

    private static String quote(String s) {
        return new com.google.gson.JsonPrimitive(s).toString();
    }

    private static UIElement one(TestContext ctx, String id) {
        return ctx.ui().select(id, UIElement.class).findFirst().orElseThrow();
    }

    private static String text(UIElement e) {
        StringBuilder sb = new StringBuilder();
        if (e instanceof Label l) sb.append(l.getText().getString()).append('\n');
        for (UIElement c : e.getChildren()) sb.append(text(c));
        return sb.toString();
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("use a throwaway folder and a model that waits", ctx -> {
                    try {
                        oldDir = SageSettings.dir;
                        SageSettings.dir = Files.createTempDirectory("logos-sage-away");
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                    release = new CountDownLatch(1);
                    SageClient.testModel = held();
                })
                .step("open the workbench", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, ItemStack.EMPTY))
                .awaitModularUI()
                .ticks(10)
                .click("#tab_sage")
                .ticks(5)
                .step("type a wish", ctx -> ctx.ui().select("#sage_input", TextField.class).findFirst().orElseThrow().setValue(WISH, false))
                .step("send", ctx -> {
                    UIElement b = one(ctx, "#sage_send");
                    float x = b.getPositionX() + b.getSizeWidth() / 2, y = b.getPositionY() + b.getSizeHeight() / 2;
                    ctx.input().mouseDown(x, y, 0);
                    ctx.input().mouseUp(x, y, 0);
                })
                .ticks(5)
                .step("the sage is thinking", ctx -> ctx.check("cancel shown while thinking", one(ctx, "#sage_cancel").isDisplayed()))
                .closeScreen()
                .ticks(10)
                .step("open the workbench again", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, ItemStack.EMPTY))
                .awaitModularUI()
                .ticks(10)
                .click("#tab_sage")
                .ticks(5)
                .step("back in the same conversation, still thinking", ctx -> {
                    ctx.check("the question is shown", text(one(ctx, "#sage_messages")).contains(WISH));
                    ctx.check("still thinking", one(ctx, "#sage_cancel").isDisplayed());
                    ctx.check("send is held back", !one(ctx, "#sage_send").isDisplayed());
                    ctx.check("no note while the sage's page is open", !SageToast.showing());
                })
                .screenshot("sage_away_back")
                .closeScreen()
                .ticks(5)
                .step("the sage finishes", ctx -> release.countDown())
                .waitUntil("the note pops up", ctx -> SageToast.showing())
                .step("open the workbench a third time", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, ItemStack.EMPTY))
                .awaitModularUI()
                .ticks(10)
                .step("the note stays up at the table until the sage's page opens", ctx -> ctx.check("note shown over the editor", SageToast.showing()))
                .screenshot("sage_away_toast")
                .click("#tab_sage")
                .ticks(5)
                .step("the answer is in the conversation", ctx -> {
                    ctx.check("the spell card is there", ctx.ui().select(".sage-preview", UIElement.class).findFirst().isPresent());
                    ctx.check("idle again", one(ctx, "#sage_send").isDisplayed());
                    ctx.check("the note is gone on the sage's page", !SageToast.showing());
                })
                .screenshot("sage_away_answered")
                .step("put the real folder back", ctx -> {
                    SageClient.testModel = null;
                    SageSettings.dir = oldDir;
                });
    }
}
