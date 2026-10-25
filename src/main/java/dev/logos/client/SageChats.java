package dev.logos.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.logos.LogosMod;
import dev.logos.sage.SageAgent;
import dev.logos.sage.SageServer;
import dev.logos.sage.SageSettings;

/**
 * The player's conversations with the sage, newest first, kept beside the sage's settings (outside
 * the game folder, so they stay the player's own when a modpack is shared).
 */
final class SageChats {
    static final String FILE = "sage-chats.json";
    static final int MAX_CHATS = 50;

    /** One conversation; its title is the first thing the player asked. */
    static final class Chat {
        final String id;
        String title;
        final List<SageAgent.Turn> turns = new ArrayList<>();

        Chat(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private static List<Chat> chats;
    /** The folder {@link #chats} came from; when the settings folder moves (client scenarios), they are read again. */
    private static Path loadedFrom;

    private SageChats() {}

    static List<Chat> all() {
        if (chats == null || !SageSettings.dir.equals(loadedFrom)) {
            loadedFrom = SageSettings.dir;
            chats = load();
        }
        return chats;
    }

    static Chat create() {
        Chat c = new Chat(UUID.randomUUID().toString(), "");
        all().addFirst(c);
        while (chats.size() > MAX_CHATS) chats.removeLast();
        return c;
    }

    static void delete(Chat c) {
        all().remove(c);
        save();
    }

    static Chat find(String id) {
        for (Chat c : all()) if (c.id.equals(id)) return c;
        return null;
    }

    /** Adds a turn and moves the chat to the top; the first question names it. */
    static void add(Chat c, SageAgent.Turn t) {
        c.turns.add(t);
        if (c.title.isEmpty() && t.player()) c.title = t.text().length() > 18 ? t.text().substring(0, 18) + "…" : t.text();
        all().remove(c);
        chats.addFirst(c);
        save();
    }

    static void save() {
        JsonArray a = new JsonArray();
        for (Chat c : all()) {
            if (c.turns.isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id);
            o.addProperty("title", c.title);
            o.add("turns", JsonParser.parseString(SageServer.writeTurns(c.turns)));
            a.add(o);
        }
        try {
            Files.createDirectories(SageSettings.dir);
            Files.writeString(SageSettings.dir.resolve(FILE), a.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LogosMod.LOGGER.warn("Could not save sage chats: {}", ex.toString());
        }
    }

    private static List<Chat> load() {
        List<Chat> out = new ArrayList<>();
        Path p = SageSettings.dir.resolve(FILE);
        if (!Files.isRegularFile(p)) return out;
        try {
            JsonElement e = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
            if (!e.isJsonArray()) return out;
            for (JsonElement it : e.getAsJsonArray()) {
                if (!it.isJsonObject()) continue;
                JsonObject o = it.getAsJsonObject();
                Chat c = new Chat(o.has("id") ? o.get("id").getAsString() : UUID.randomUUID().toString(), o.has("title") ? o.get("title").getAsString() : "");
                if (o.has("turns")) c.turns.addAll(SageServer.readTurns(o.get("turns").toString(), Integer.MAX_VALUE));
                out.add(c);
            }
        } catch (IOException | RuntimeException ex) {
            LogosMod.LOGGER.warn("Could not read sage chats: {}", ex.toString());
        }
        return out;
    }
}
