package dev.logos.sage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A model behind the OpenAI-compatible chat-completions API, which most providers and local
 * runners (Ollama, LM Studio, vLLM) speak. Plain {@link HttpClient}: no SDK to shade into the jar.
 */
public final class OpenAiChat implements SageModel {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final SageEndpoint endpoint;
    private final Duration timeout;

    public OpenAiChat(SageEndpoint endpoint, Duration timeout) {
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    @Override
    public Reply complete(List<Message> messages, List<Tool> tools) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint.completionsUrl()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request(endpoint.model(), messages, tools).toString(), StandardCharsets.UTF_8));
        if (!endpoint.apiKey().isEmpty()) req.header("Authorization", "Bearer " + endpoint.apiKey());
        CompletableFuture<HttpResponse<String>> sent = HTTP.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> res;
        try {
            res = sent.get();
        } catch (InterruptedException ex) {
            sent.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            throw cause instanceof IOException io ? io : new IOException(cause == null ? "request failed" : cause.getClass().getSimpleName());
        }
        if (res.statusCode() / 100 != 2) throw new IOException("HTTP " + res.statusCode() + ": " + errorMessage(res.body()));
        return reply(res.body());
    }

    /** The request body: the conversation, and the tools when there are any. */
    static JsonObject request(String model, List<Message> messages, List<Tool> tools) {
        JsonObject o = new JsonObject();
        o.addProperty("model", model);
        JsonArray ms = new JsonArray();
        for (Message m : messages) {
            JsonObject j = new JsonObject();
            j.addProperty("role", m.role());
            j.addProperty("content", m.content() == null ? "" : m.content());
            if (!m.toolCalls().isEmpty()) {
                JsonArray calls = new JsonArray();
                for (ToolCall c : m.toolCalls()) {
                    JsonObject fn = new JsonObject();
                    fn.addProperty("name", c.name());
                    fn.addProperty("arguments", c.arguments());
                    JsonObject call = new JsonObject();
                    call.addProperty("id", c.id());
                    call.addProperty("type", "function");
                    call.add("function", fn);
                    calls.add(call);
                }
                j.add("tool_calls", calls);
            }
            if (m.toolCallId() != null) j.addProperty("tool_call_id", m.toolCallId());
            ms.add(j);
        }
        o.add("messages", ms);
        if (!tools.isEmpty()) {
            JsonArray ts = new JsonArray();
            for (Tool t : tools) {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", t.name());
                fn.addProperty("description", t.description());
                fn.add("parameters", JsonParser.parseString(t.parameters()));
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.add("function", fn);
                ts.add(tool);
            }
            o.add("tools", ts);
        }
        return o;
    }

    /** The first choice's message: its text and its tool calls. */
    static Reply reply(String body) throws IOException {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = o.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) throw new IOException("the reply has no choices");
            JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            String content = text(msg.get("content"));
            List<ToolCall> calls = new ArrayList<>();
            JsonArray tc = msg.has("tool_calls") && msg.get("tool_calls").isJsonArray() ? msg.getAsJsonArray("tool_calls") : new JsonArray();
            for (int i = 0; i < tc.size(); i++) {
                JsonObject c = tc.get(i).getAsJsonObject();
                JsonObject fn = c.getAsJsonObject("function");
                JsonElement args = fn.get("arguments");
                // Most send the arguments as a JSON string; a few send the object itself.
                String a = args == null || args.isJsonNull() ? "{}" : args.isJsonPrimitive() ? args.getAsString() : args.toString();
                String id = c.has("id") && !c.get("id").isJsonNull() ? c.get("id").getAsString() : "call_" + i;
                calls.add(new ToolCall(id, text(fn.get("name")), a));
            }
            return new Reply(content, calls);
        } catch (RuntimeException ex) {
            throw new IOException("unreadable reply: " + ex.getClass().getSimpleName());
        }
    }

    private static String text(JsonElement e) {
        return e == null || e.isJsonNull() ? "" : e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    /** The provider's own error message when it sent one, kept short. */
    private static String errorMessage(String body) {
        String m = body == null ? "" : body;
        try {
            JsonObject o = JsonParser.parseString(m).getAsJsonObject();
            JsonElement err = o.get("error");
            if (err != null && err.isJsonObject() && err.getAsJsonObject().has("message")) m = text(err.getAsJsonObject().get("message"));
            else if (err != null && err.isJsonPrimitive()) m = err.getAsString();
        } catch (RuntimeException ignored) {
            // not JSON: keep the raw text
        }
        m = m.strip();
        return m.length() > 300 ? m.substring(0, 300) + "…" : m;
    }
}
