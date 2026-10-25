package dev.logos.sage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import dev.logos.lang.Rune;
import dev.logos.lang.Runes;

class SageAgentTest {
    private static final String FIREBALL = "(spell :name \"火球\" :mana 30 (projectile (on-hit (damage target standard fire))))";
    private static final String BROKEN = "(spell :mana 30 (projectile (on-hit (damage))))";

    /** A model that answers from a script and remembers what it was sent. */
    private static final class Scripted implements SageModel {
        final Deque<Reply> replies = new ArrayDeque<>();
        final List<List<Message>> seen = new ArrayList<>();

        Scripted then(String content, ToolCall... calls) {
            replies.add(new Reply(content, List.of(calls)));
            return this;
        }

        @Override
        public Reply complete(List<Message> messages, List<Tool> tools) {
            seen.add(List.copyOf(messages));
            return replies.isEmpty() ? new Reply("……", List.of()) : replies.poll();
        }
    }

    private static Set<String> all() {
        Set<String> s = new LinkedHashSet<>();
        for (Rune r : Runes.all()) s.add(r.name);
        return s;
    }

    private static SageModel.ToolCall call(String id, String name, String key, String value) {
        JsonObject o = new JsonObject();
        if (key != null) o.addProperty(key, value);
        return new SageModel.ToolCall(id, name, o.toString());
    }

    private static SageModel.ToolCall submit(String id, String source) {
        JsonObject o = new JsonObject();
        o.addProperty("source", source);
        o.addProperty("explanation", "先射出弹丸，命中时灼烧。");
        return new SageModel.ToolCall(id, "submit_spell", o.toString());
    }

    private static SageAgent.Request request(Set<String> available) {
        return new SageAgent.Request("zh_cn", available, List.of(), "向前方发射一枚火球", "", 0, false);
    }

    @Test
    void promptListsOnlyTheRunesThePlayerHas() {
        Set<String> some = all();
        some.remove("projectile");
        String prompt = SagePrompt.system("zh_cn", some);
        assertFalse(prompt.contains("(projectile "), "a locked rune is listed");
        assertTrue(prompt.contains("(damage "));
        assertTrue(prompt.contains("fireball"), "examples are listed by id");
        assertTrue(SagePrompt.system("en_us", all()).startsWith("You are the \"Book Sprite\""));
    }

    @Test
    void anAcceptedSubmissionEndsTheLoop() throws Exception {
        Scripted model = new Scripted()
                .then("", call("a", "lookup_rune", "name", "projectile"), call("b", "compile", "source", FIREBALL))
                .then("", submit("c", FIREBALL));
        List<String> statuses = new ArrayList<>();
        SageAgent.Outcome out = SageAgent.run(model, request(all()), (k, a) -> statuses.add(k));
        assertTrue(out.ok(), out.error() + " " + out.detail());
        assertTrue(out.source().contains("projectile"));
        assertEquals("先射出弹丸，命中时灼烧。", out.text());
        assertTrue(statuses.containsAll(List.of("lookup", "compile", "submitted")), statuses.toString());
        // The tool results went back to the model before it submitted.
        List<SageModel.Message> second = model.seen.get(1);
        assertTrue(second.stream().anyMatch(m -> "tool".equals(m.role()) && m.content().startsWith("✓ 成立")));
    }

    @Test
    void aLockedRuneIsSentBackLikeACompileError() throws Exception {
        Set<String> some = all();
        some.remove("projectile");
        String ray = "(spell :mana 30 (ray (on-hit (damage target standard fire))))";
        Scripted model = new Scripted().then("", submit("a", FIREBALL)).then("", submit("b", ray));
        SageAgent.Outcome out = SageAgent.run(model, request(some), (k, a) -> {});
        assertTrue(out.ok());
        assertTrue(out.source().contains("(ray"));
        String feedback = model.seen.get(1).getLast().content();
        assertTrue(feedback.contains("projectile") && feedback.contains("还没有掌握"), feedback);
    }

    @Test
    void retriesStopAtTheLimit() throws Exception {
        Scripted model = new Scripted();
        for (int i = 0; i < SageAgent.MAX_ROUNDS; i++) model.then("", submit("s" + i, BROKEN));
        SageAgent.Outcome out = SageAgent.run(model, request(all()), (k, a) -> {});
        assertFalse(out.ok());
        assertEquals("logos.sage.error.unfixed", out.error());
        assertEquals(SageAgent.MAX_SUBMITS, model.seen.size());
        assertEquals(BROKEN, out.source(), "the last try is kept for the player to finish");
    }

    @Test
    void plainTalkHandsNoSpell() throws Exception {
        SageAgent.Outcome out = SageAgent.run(new Scripted().then("魔力是一次施放注入的总量。"), request(all()), (k, a) -> {});
        assertTrue(out.ok());
        assertEquals("", out.source());
        assertEquals("魔力是一次施放注入的总量。", out.text());
    }

    @Test
    void historyAndCurrentSpellReachTheModel() throws Exception {
        Scripted model = new Scripted().then("", call("a", "get_current_spell", null, null)).then("好。");
        var req = new SageAgent.Request("zh_cn", all(), List.of(new SageAgent.Turn(true, "火球", ""), new SageAgent.Turn(false, "给你。", FIREBALL)),
                "再省一点魔力", FIREBALL, 0, false);
        SageAgent.run(model, req, (k, a) -> {});
        List<SageModel.Message> first = model.seen.getFirst();
        assertEquals(List.of("system", "user", "assistant", "user"), first.stream().map(SageModel.Message::role).toList());
        assertTrue(first.get(2).content().contains("projectile"));
        assertEquals(FIREBALL, model.seen.get(1).getLast().content());
    }

    @Test
    void thePageCapacityGoesWithTheQuestion() throws Exception {
        Scripted model = new Scripted().then("", call("a", "compile", "source", "(spell :mana 120 (projectile (on-hit (damage target standard fire))))"))
                .then("好。");
        SageAgent.run(model, new SageAgent.Request("zh_cn", all(), List.of(), "火球", "", 50, false), (k, a) -> {});
        String asked = model.seen.getFirst().getLast().content();
        assertTrue(asked.contains("容量是 50") && asked.contains("焚式"), asked);
        String report = model.seen.get(1).getLast().content();
        assertTrue(report.startsWith("✓ 成立") && report.contains("超出了载页容量 50"), report);

        // An emitter cannot immolate; an unknown page says nothing.
        assertTrue(SageAgent.page(new SageAgent.Request("zh_cn", all(), List.of(), "", "", 400, true)).contains("发射器不能焚式"));
        assertEquals("", SageAgent.page(new SageAgent.Request("zh_cn", all(), List.of(), "", "", 0, false)));
        // The system prompt does not change with the page, so its prefix stays cacheable.
        assertFalse(SagePrompt.system("zh_cn", all()).contains("容量是"));
    }

    @Test
    void anInterruptedRequestStops() {
        SageModel blocked = (messages, tools) -> {
            throw new InterruptedException();
        };
        assertThrows(InterruptedException.class, () -> SageAgent.run(blocked, request(all()), (k, a) -> {}));
    }

    @Test
    void chatCompletionsRoundTrip() throws Exception {
        var body = OpenAiChat.request("m", List.of(SageModel.Message.system("s"),
                SageModel.Message.assistant("", List.of(new SageModel.ToolCall("x", "compile", "{\"source\":\"(spell)\"}"))),
                SageModel.Message.tool("x", "ok")), SageAgent.TOOLS);
        assertEquals("compile", body.getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("tool_calls").get(0)
                .getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
        assertEquals("x", body.getAsJsonArray("messages").get(2).getAsJsonObject().get("tool_call_id").getAsString());
        assertEquals(SageAgent.TOOLS.size(), body.getAsJsonArray("tools").size());

        // Arguments as a string (OpenAI) and as an object (some local runners); content may be null.
        var reply = OpenAiChat.reply("""
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"1","type":"function","function":{"name":"compile","arguments":"{\\"source\\":\\"a\\"}"}},
                  {"type":"function","function":{"name":"lookup_rune","arguments":{"name":"ray"}}}]}}]}
                """);
        assertEquals("", reply.content());
        assertEquals("{\"source\":\"a\"}", reply.toolCalls().get(0).arguments());
        assertEquals("{\"name\":\"ray\"}", reply.toolCalls().get(1).arguments());
        assertThrows(java.io.IOException.class, () -> OpenAiChat.reply("{\"error\":{\"message\":\"bad key\"}}"));
    }

    @Test
    void historyTravelsTrimmedToTheLatestTurns() {
        List<SageAgent.Turn> turns = new ArrayList<>();
        for (int i = 0; i < 40; i++) turns.add(new SageAgent.Turn(i % 2 == 0, "问 " + i, i % 2 == 0 ? "" : FIREBALL));
        List<SageAgent.Turn> sent = SageServer.readHistory(SageServer.writeHistory(turns));
        assertEquals(24, sent.size());
        assertEquals("问 39", sent.getLast().text());
        assertEquals(FIREBALL, sent.getLast().source());
        // Kept chats are not trimmed.
        assertEquals(40, SageServer.readTurns(SageServer.writeTurns(turns), Integer.MAX_VALUE).size());
        // Turns too long for the packet drop out from the oldest.
        String big = "长".repeat(10_000);
        List<SageAgent.Turn> huge = List.of(new SageAgent.Turn(true, big, ""), new SageAgent.Turn(true, big, ""),
                new SageAgent.Turn(true, big, ""), new SageAgent.Turn(true, big, ""), new SageAgent.Turn(true, big, ""));
        assertTrue(SageServer.writeHistory(huge).length() <= dev.logos.net.SageAskPayload.MAX_HISTORY);
        assertTrue(SageServer.readHistory("not json").isEmpty());
    }

    @Test
    void theKeyStaysOutOfStrings() {
        SageEndpoint e = new SageEndpoint("https://example.test/v1/", "gpt", "sk-secret-123456789");
        assertFalse(e.toString().contains("secret"));
        assertTrue(e.toString().contains("****6789"));
        assertEquals("https://example.test/v1/chat/completions", e.completionsUrl());
        assertEquals("http://localhost:11434/v1/chat/completions", new SageEndpoint("http://localhost:11434/v1/chat/completions", "q", "").completionsUrl());
        assertFalse(new SageEndpoint("https://relay.test/v1?key=abc", "m", "").toString().contains("abc"));
    }

    @Test
    void environmentVariablesWinAndAreNotWrittenToTheFile() throws Exception {
        Path dir = Files.createTempDirectory("logos-sage");
        var oldDir = SageSettings.dir;
        var oldEnv = SageSettings.env;
        try {
            SageSettings.dir = dir;
            Map<String, String> env = new HashMap<>();
            SageSettings.env = env::get;
            SageSettings.saveClient(new SageSettings.Client(new SageEndpoint("https://a.test/v1", "m1", "sk-file-key-0000"), false));
            assertEquals("sk-file-key-0000", SageSettings.client().endpoint().apiKey());
            assertFalse(SageSettings.client().preferServer());

            env.put("LOGOS_AI_KEY", "sk-env-key-1111");
            assertEquals("sk-env-key-1111", SageSettings.client().endpoint().apiKey());
            assertTrue(SageSettings.clientEndpoint().keyFromEnv());
            SageSettings.saveClient(SageSettings.client());
            String file = Files.readString(dir.resolve(SageSettings.Client.FILE));
            assertFalse(file.contains("sk-env-key"), "an environment key was copied into the file");

            // The server's file and variables are its own.
            env.put("LOGOS_AI_SERVER_URL", "https://server.test/v1");
            SageSettings.saveServer(new SageSettings.Server(true, 5, 10, 2, new SageEndpoint("", "m2", "sk-server-2222")));
            SageSettings.Server s = SageSettings.server();
            assertTrue(s.offered());
            assertEquals("https://server.test/v1", s.endpoint().url());
            assertEquals("sk-server-2222", s.endpoint().apiKey());
            assertEquals("sk-env-key-1111", SageSettings.client().endpoint().apiKey());
        } finally {
            SageSettings.dir = oldDir;
            SageSettings.env = oldEnv;
        }
    }
}
