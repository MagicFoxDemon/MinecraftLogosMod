package dev.logos.client.uitest;

import java.nio.file.Files;
import java.util.Random;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientHooks;
import dev.logos.client.voice.SpeechEmbedding;
import dev.logos.client.voice.SpellVoices;
import dev.logos.client.voice.VoiceClient;
import dev.logos.item.SpellPages;
import net.minecraft.core.BlockPos;

/**
 * Real-client UI test of 言出法随's page in the workbench: open it on a written spell, record three
 * takes and a test, apply, and see the overview say it is on. There is no microphone in a test, so
 * made-up "words" (tone sequences) are handed in as speech; recordings go to a throwaway folder.
 */
@LDLRegisterClient(name = "logos_voice_page", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class VoiceScenario implements UIScenario {
    static final String SPELL = "(spell :name \"火球\" :mana 20 (mark caster #voiced))";
    /** The tones standing for saying 火球. */
    static final double[] FIREBALL = {300, 800, 500};

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "voice").guiScale(2);
    }

    /** A made-up word: three 150 ms tones with a little noise, at {@code speed}. */
    static float[] word(double[] tones, double speed, long seed) {
        int rate = SpeechEmbedding.SAMPLE_RATE;
        Random r = new Random(seed);
        int segment = (int) (rate * 0.15 / speed), pad = rate / 5;
        float[] x = new float[pad * 2 + segment * tones.length];
        for (int n = 0; n < x.length; n++) x[n] = (float) (r.nextGaussian() * 0.002);
        double phase = 0;
        for (int s = 0; s < tones.length; s++) {
            double hz = tones[s] * (1 + r.nextGaussian() * 0.01);
            for (int n = 0; n < segment; n++) {
                phase += 2 * Math.PI * hz / rate;
                x[pad + s * segment + n] += (float) (0.3 * Math.sin(phase) + 0.1 * Math.sin(2 * phase));
            }
        }
        return x;
    }

    /** Recordings in a throwaway folder, and no microphone: speech is handed in by the scenario. */
    static void throwaway() {
        try {
            VoiceClient.useVoices(new SpellVoices(Files.createTempDirectory("logos-voice-scenario"), SpeechEmbedding.get()));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        VoiceClient.simulated = true;
    }

    private static String label(TestContext ctx, String id) {
        return ctx.ui().select(id, Label.class).findFirst().orElseThrow().getText().getString();
    }

    /** Says 火球 into the page and waits for {@code done}; checks go by marks and translations, whatever the client's language. */
    private static ScenarioBuilder say(ScenarioBuilder s, int take, String what, java.util.function.Predicate<TestContext> done) {
        return s.click("#voice_record")
                .ticks(2)
                .step("say 火球 (" + take + ")", ctx -> VoiceClient.simulateSpeech(word(FIREBALL, 0.92 + 0.06 * take, take)))
                .waitUntil(what, done::test);
    }

    private static long filled(TestContext ctx) {
        return label(ctx, "#voice_steps").chars().filter(c -> c == '●').count();
    }

    private static String tr(String key, Object... args) {
        return net.minecraft.network.chat.Component.translatable(key, args).getString();
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("use a throwaway folder and no microphone", ctx -> throwaway())
                .step("open the workbench on 火球", ctx -> ClientHooks.openTableEditor(BlockPos.ZERO, SpellPages.inscribed(SPELL, null)))
                .awaitModularUI()
                .ticks(10)
                .checkTextContains("#overview", "言出法随：未启用")
                .click("#tab_voice")
                .ticks(5)
                .step("the voice page is open on 火球", ctx -> {
                    ctx.check("voice page shown", ctx.ui().select("#voice_panel", UIElement.class).findFirst().orElseThrow().isDisplayed());
                    ctx.check("names the spell", label(ctx, "#voice_name").contains("火球"));
                })
                .screenshot("voice_open");
        for (int take = 1; take <= 3; take++) {
            int n = take;
            say(s, take, "take " + take + " kept", ctx -> filled(ctx) == n);
        }
        s.screenshot("voice_three_takes");
        say(s, 4, "the test is recognised", ctx -> label(ctx, "#voice_steps").contains("✔"));
        s.step("apply is offered", ctx -> ctx.check("apply enabled after a passed test",
                        ctx.ui().select("#voice_apply", UIElement.class).findFirst().orElseThrow().isActive()))
                .screenshot("voice_tested")
                .click("#voice_apply")
                .waitUntil("applied", ctx -> label(ctx, "#voice_state").equals(tr("logos.voice.state.on", "火球")))
                .checkTextContains("#overview", "言出法随：可用")
                .screenshot("voice_applied")
                .step("back to the microphone", ctx -> VoiceClient.simulated = false);
    }
}
