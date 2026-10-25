package dev.logos.sage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.logos.LogosMod;

/**
 * The sage's settings, kept outside the game folder on purpose: a modpack is usually shared by
 * zipping the whole instance, and a key under {@code config/} would go along with it. They live in
 * the user's own settings folder instead ({@code %APPDATA%\logos} on Windows), and environment
 * variables override them field by field. The client and the server keep separate files, so a
 * singleplayer world's host can set up both without one touching the other.
 */
public final class SageSettings {
    /** Where the files are; tests point it elsewhere. */
    public static Path dir = defaultDir();
    /** Read environment variables through this, so tests can stand in for the real environment. */
    public static Function<String, String> env = System::getenv;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SageSettings() {}

    /** An endpoint with, for each field, whether an environment variable supplies it (the screen then locks it). */
    public record Loaded(SageEndpoint endpoint, boolean urlFromEnv, boolean modelFromEnv, boolean keyFromEnv) {}

    /** The player's own sage: their endpoint, and whether a server's sage is used first when one is offered. */
    public record Client(SageEndpoint endpoint, boolean preferServer) {
        public static final String FILE = "sage-client.json", ENV = "LOGOS_AI";
    }

    /** The server's sage: whether players may use it, its limits, and its endpoint. */
    public record Server(boolean offered, int cooldownSeconds, int dailyLimit, int maxConcurrent, SageEndpoint endpoint) {
        public static final String FILE = "sage-server.json", ENV = "LOGOS_AI_SERVER";
        public static final Server DEFAULT = new Server(false, 20, 50, 4, SageEndpoint.NONE);

        public Server {
            cooldownSeconds = Math.clamp(cooldownSeconds, 0, 3600);
            dailyLimit = Math.clamp(dailyLimit, 0, 10_000);
            maxConcurrent = Math.clamp(maxConcurrent, 1, 64);
        }
    }

    public static Client client() {
        JsonObject o = read(Client.FILE);
        return new Client(endpoint(o, Client.ENV).endpoint(), bool(o, "preferServer", true));
    }

    public static Loaded clientEndpoint() {
        return endpoint(read(Client.FILE), Client.ENV);
    }

    public static void saveClient(Client c) {
        JsonObject o = new JsonObject();
        o.addProperty("preferServer", c.preferServer());
        putEndpoint(o, c.endpoint(), Client.ENV);
        write(Client.FILE, o);
    }

    public static Server server() {
        JsonObject o = read(Server.FILE);
        return new Server(bool(o, "offered", Server.DEFAULT.offered()), integer(o, "cooldownSeconds", Server.DEFAULT.cooldownSeconds()),
                integer(o, "dailyLimit", Server.DEFAULT.dailyLimit()), integer(o, "maxConcurrent", Server.DEFAULT.maxConcurrent()),
                endpoint(o, Server.ENV).endpoint());
    }

    public static Loaded serverEndpoint() {
        return endpoint(read(Server.FILE), Server.ENV);
    }

    public static void saveServer(Server s) {
        JsonObject o = new JsonObject();
        o.addProperty("offered", s.offered());
        o.addProperty("cooldownSeconds", s.cooldownSeconds());
        o.addProperty("dailyLimit", s.dailyLimit());
        o.addProperty("maxConcurrent", s.maxConcurrent());
        putEndpoint(o, s.endpoint(), Server.ENV);
        write(Server.FILE, o);
    }

    /** The file's endpoint, each field replaced by {@code <prefix>_URL}, {@code _MODEL} or {@code _KEY} when that is set. */
    private static Loaded endpoint(JsonObject o, String prefix) {
        String url = env(prefix + "_URL"), model = env(prefix + "_MODEL"), key = env(prefix + "_KEY");
        return new Loaded(new SageEndpoint(url != null ? url : string(o, "url"), model != null ? model : string(o, "model"),
                key != null ? key : string(o, "apiKey")), url != null, model != null, key != null);
    }

    /** What an environment variable already supplies is not written to the file as well. */
    private static void putEndpoint(JsonObject o, SageEndpoint e, String prefix) {
        if (env(prefix + "_URL") == null) o.addProperty("url", e.url());
        if (env(prefix + "_MODEL") == null) o.addProperty("model", e.model());
        if (env(prefix + "_KEY") == null) o.addProperty("apiKey", e.apiKey());
    }

    private static String env(String name) {
        String v = env.apply(name);
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static JsonObject read(String file) {
        Path p = dir.resolve(file);
        if (!Files.isRegularFile(p)) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (IOException | RuntimeException ex) {
            // The message only; the file holds a key.
            LogosMod.LOGGER.warn("Could not read sage settings {}: {}", p, ex.getClass().getSimpleName());
            return new JsonObject();
        }
    }

    private static void write(String file, JsonObject o) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(file + ".tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows: the folder under the user's profile is already private to them.
            }
            Files.move(tmp, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            LogosMod.LOGGER.warn("Could not save sage settings to {}: {}", dir, ex.getClass().getSimpleName());
        }
    }

    private static String string(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
    }

    private static int integer(JsonObject o, String k, int def) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : def;
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** The user's settings folder for this mod, outside every game instance; {@code -Dlogos.sage.dir} overrides it. */
    private static Path defaultDir() {
        String override = System.getProperty("logos.sage.dir");
        if (override != null && !override.isBlank()) return Path.of(override);
        String home = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null && !appData.isBlank() ? appData : home, "logos");
        }
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "logos");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return Path.of(xdg != null && !xdg.isBlank() ? xdg : home + "/.config", "logos");
    }
}
