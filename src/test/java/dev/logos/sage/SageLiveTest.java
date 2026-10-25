package dev.logos.sage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.logos.lang.Rune;
import dev.logos.lang.Runes;

/**
 * Asks a real model, with the player's own sage settings ({@link SageSettings#client()}). Off unless
 * {@code LOGOS_SAGE_LIVE=1} is set, since it spends the player's tokens. It prints what the sage did
 * and how long it took, never the key.
 */
@EnabledIfEnvironmentVariable(named = "LOGOS_SAGE_LIVE", matches = "1")
class SageLiveTest {
    private static final List<String> WISHES = List.of(
            "向前方发射一枚追踪最近敌人的火球",
            "在我周围展开一个领域，持续治疗范围内的友方",
            "在面前立起一面石墙挡住箭",
            "不用管载页容量，给我来一发几千魔力的华丽流星雨");

    @Test
    void realModelWritesSpellsThatHold() throws Exception {
        SageEndpoint endpoint = SageSettings.client().endpoint();
        assertTrue(endpoint.configured(), "no sage settings: " + endpoint);
        System.out.println("[sage live] " + endpoint);
        Set<String> all = new LinkedHashSet<>();
        for (Rune r : Runes.all()) all.add(r.name);
        System.out.println("[sage live] system prompt: " + SagePrompt.system("zh_cn", all).length() + " chars");
        int held = 0;
        for (String wish : WISHES) {
            OpenAiChat real = new OpenAiChat(endpoint, Duration.ofSeconds(90));
            List<String> calls = new ArrayList<>();
            int[] rounds = {0};
            SageModel counted = (messages, tools) -> {
                rounds[0]++;
                SageModel.Reply r = real.complete(messages, tools);
                for (SageModel.ToolCall c : r.toolCalls()) calls.add(c.name());
                return r;
            };
            long t0 = System.nanoTime();
            SageAgent.Outcome out = SageAgent.run(counted, new SageAgent.Request("zh_cn", all, List.of(), wish, "", 50, false), (k, a) -> {});
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("\n[sage live] ===== " + wish);
            System.out.println("[sage live] " + ms + " ms, " + rounds[0] + " rounds, tools " + calls);
            System.out.println("[sage live] error=" + out.error() + " " + out.detail());
            System.out.println("[sage live] text:\n" + out.text());
            System.out.println("[sage live] source:\n" + out.source());
            if (out.ok() && !out.source().isEmpty()) held++;
        }
        System.out.println("\n[sage live] " + held + "/" + WISHES.size() + " handed in a spell that holds");
    }
}
