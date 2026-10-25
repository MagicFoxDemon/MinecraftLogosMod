package dev.logos.sage;

import java.io.IOException;
import java.util.List;

/**
 * A chat model that can call tools: the one thing the sage needs from a provider. Kept this small so
 * the OpenAI-compatible client can be swapped for another format, or for a fake one in tests.
 */
public interface SageModel {
    /** One message of the conversation, in the chat-completions shape every compatible provider speaks. */
    record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        public static Message system(String text) {
            return new Message("system", text, List.of(), null);
        }

        public static Message user(String text) {
            return new Message("user", text, List.of(), null);
        }

        public static Message assistant(String text, List<ToolCall> calls) {
            return new Message("assistant", text, calls, null);
        }

        public static Message tool(String callId, String result) {
            return new Message("tool", result, List.of(), callId);
        }
    }

    /** A tool the model asked to run; {@code arguments} is the JSON object it wrote. */
    record ToolCall(String id, String name, String arguments) {}

    /** A tool offered to the model; {@code parameters} is its JSON schema. */
    record Tool(String name, String description, String parameters) {}

    /** What the model answered: some text, some tool calls, or both. */
    record Reply(String content, List<ToolCall> toolCalls) {}

    /** Runs one round on the calling thread; interrupting it abandons the request. */
    Reply complete(List<Message> messages, List<Tool> tools) throws IOException, InterruptedException;
}
