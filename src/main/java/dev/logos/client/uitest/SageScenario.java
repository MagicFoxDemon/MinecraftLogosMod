package dev.logos.client.uitest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.client.SageClient;
import dev.logos.sage.SageModel;
import dev.logos.sage.SageSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * Real-client UI test of 书灵 (the sage page) with a scripted model in place of a provider: ask on the sage's page,
 * get a spell back, preview it on the canvas, put the drawing back, preview again and adopt, then
 * open the settings page. Chats and settings go to a throwaway folder, never the player's own.
 */
@LDLRegisterClient(name = "logos_sage", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SageScenario implements UIScenario {
    private static final String SPELL = "(spell :name \"追踪火球\" :mana 40\n  (projectile :motion homing\n    (on-hit (damage target standard fire))))";
    private static Path oldDir;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    /** Looks a rune up, tries the spell, then hands it in: one round each, like a real model would. */
    private static SageModel scripted() {
        AtomicInteger round = new AtomicInteger();
        return (messages, tools) -> switch (round.getAndIncrement()) {
            case 0 -> new SageModel.Reply("", List.of(new SageModel.ToolCall("a", "lookup_rune", "{\"name\":\"projectile\"}")));
            case 1 -> new SageModel.Reply("", List.of(new SageModel.ToolCall("b", "compile", json("source", SPELL))));
            default -> new SageModel.Reply("", List.of(new SageModel.ToolCall("c", "submit_spell",
                    "{\"source\":" + quote(SPELL) + ",\"explanation\":" + quote("一、projectile 射出魔弹；二、:motion homing 让它追踪最近的敌人；三、命中时以火焰造成标准伤害。") + "}")));
        };
    }

    /** Press and release in one step: the bar holding the button folds away as soon as it is pressed. */
    private static void press(com.lowdragmc.lowdraglib2.uitest.TestContext ctx, String id) {
        UIElement b = ctx.ui().select(id, UIElement.class).findFirst().orElseThrow();
        float x = b.getPositionX() + b.getSizeWidth() / 2, y = b.getPositionY() + b.getSizeHeight() / 2;
        ctx.input().mouseDown(x, y, 0);
        ctx.input().mouseUp(x, y, 0);
    }

    /** Every label's text under {@code e}, one after another. */
    private static String text(UIElement e) {
        StringBuilder sb = new StringBuilder();
        if (e instanceof com.lowdragmc.lowdraglib2.gui.ui.elements.Label l) sb.append(l.getText().getString()).append('\n');
        for (UIElement c : e.getChildren()) sb.append(text(c));
        return sb.toString();
    }

    private static String json(String k, String v) {
        return "{" + quote(k) + ":" + quote(v) + "}";
    }

    private static String quote(String s) {
        return new com.google.gson.JsonPrimitive(s).toString();
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("use a throwaway folder and the scripted model", ctx -> {
                    try {
                        oldDir = SageSettings.dir;
                        SageSettings.dir = Files.createTempDirectory("logos-sage-scenario");
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                    SageClient.testModel = scripted();
                })
                .step("open the workbench", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, ItemStack.EMPTY))
                .awaitModularUI()
                .ticks(10)
                .click("#tab_sage")
                .ticks(5)
                .step("the sage's page is open", ctx -> ctx.check("sage page shown",
                        ctx.ui().select("#sage_panel", UIElement.class).findFirst().orElseThrow().isDisplayed()))
                .screenshot("sage_open")
                .click("#sage_input")
                .ticks(5)
                .step("the field keeps the focus and takes Chinese", ctx -> {
                    UIElement field = ctx.ui().select("#sage_input", UIElement.class).findFirst().orElseThrow();
                    ctx.check("input keeps the focus across ticks", ctx.ui().getFocusedElement() == field);
                    ctx.check("the game's text input is on for the IME", ctx.ui().getFocusedElement().isTextInput());
                    ctx.input().charTyped('火', 0);
                    ctx.input().charTyped('球', 0);
                })
                .ticks(2)
                .step("committed characters land in the field", ctx -> ctx.check("typed 火球",
                        ctx.ui().select("#sage_input", TextField.class).findFirst().orElseThrow().getValue().contains("火球")))
                .step("type a wish", ctx -> ctx.ui().select("#sage_input", TextField.class).findFirst().orElseThrow()
                        .setValue("向前方发射一枚追踪最近敌人的火球", false))
                .click("#sage_send")
                .waitUntil("the sage answers with a spell", ctx -> ctx.ui().select(".sage-preview", UIElement.class).findFirst().isPresent())
                .ticks(5)
                .step("the reply names the spell", ctx -> ctx.check("a line of the reply names the spell",
                        text(ctx.ui().select("#sage_messages", UIElement.class).findFirst().orElseThrow()).contains("追踪火球")))
                .screenshot("sage_reply")
                .click(".sage-preview")
                .ticks(10)
                .checkTextContains("#status", "追踪火球")
                .step("the preview bar is up", ctx -> ctx.check("preview bar shown",
                        ctx.ui().select("#sage_adopt", UIElement.class).findFirst().orElseThrow().getParent().isDisplayed()))
                .screenshot("sage_preview")
                .step("put the drawing back", ctx -> press(ctx, "#sage_revert"))
                .ticks(10)
                .step("the empty drawing is back", ctx -> ctx.check("canvas no longer holds the sage's spell",
                        !ctx.ui().select("#status", com.lowdragmc.lowdraglib2.gui.ui.elements.Label.class).findFirst().orElseThrow()
                                .getText().getString().contains("追踪火球")))
                .click(".sage-preview")
                .ticks(5)
                .step("adopt the spell", ctx -> press(ctx, "#sage_adopt"))
                .ticks(5)
                .step("adopting keeps the spell", ctx -> ctx.check("preview bar gone after adopting",
                        !ctx.ui().select("#sage_adopt", UIElement.class).findFirst().orElseThrow().getParent().isDisplayed()))
                .checkTextContains("#status", "追踪火球")
                .click("#sage_settings")
                .ticks(5)
                .screenshot("sage_settings")
                .step("put the real folder back", ctx -> {
                    SageClient.testModel = null;
                    SageSettings.dir = oldDir;
                });
    }
}
