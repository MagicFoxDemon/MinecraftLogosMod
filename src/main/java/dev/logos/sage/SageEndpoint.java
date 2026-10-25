package dev.logos.sage;

import java.util.Locale;

/**
 * Where the sage's model lives: an OpenAI-compatible URL, a model id and an API key. The key never
 * leaves the side that holds it, and {@link #toString()} hides it so it cannot slip into a log or a
 * crash report.
 */
public record SageEndpoint(String url, String model, String apiKey) {
    public static final SageEndpoint NONE = new SageEndpoint("", "", "");

    public SageEndpoint {
        url = url == null ? "" : url.strip();
        model = model == null ? "" : model.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
    }

    /** A key is optional (a local Ollama needs none); the URL and the model are not. */
    public boolean configured() {
        return !url.isEmpty() && !model.isEmpty();
    }

    /** The URL to post to: a base such as {@code https://api.openai.com/v1} gets {@code /chat/completions} added. */
    public String completionsUrl() {
        String u = url;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.toLowerCase(Locale.ROOT).endsWith("/chat/completions") ? u : u + "/chat/completions";
    }

    /** The key as it may be shown: its last four characters, or nothing when there is none. */
    public String keyHint() {
        return hint(apiKey);
    }

    public static String hint(String key) {
        if (key == null || key.isEmpty()) return "";
        return "****" + (key.length() > 8 ? key.substring(key.length() - 4) : "");
    }

    public SageEndpoint withKey(String key) {
        return new SageEndpoint(url, model, key);
    }

    @Override
    public String toString() {
        // Some relays carry the key in the query string; it stays out too.
        int q = url.indexOf('?');
        return "SageEndpoint[url=" + (q < 0 ? url : url.substring(0, q) + "?…") + ", model=" + model + ", apiKey=" + keyHint() + "]";
    }
}
