package dev.logos;

import static dev.logos.CompiledClasses.MOD;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A dedicated server has none of the game's client classes, yet it loads and runs everything in this mod outside
 * {@code dev.logos.client}: blocks, items, the spell runtime, and every screen in {@code dev.logos.ui}, which a server
 * builds too, to hold the screen's data. A development server has the client classes all the same, so a slip only shows
 * as a crash on a player's server (the alchemy table once did this).
 *
 * <p>So this reads the compiled classes and lists every place where code that a server may run names a client-only
 * class. Each such place must be known here, with the reason it is safe.
 */
class ClientBoundaryTest {
    private static final Path RESOURCES = Path.of("src/main/resources");

    /** Classes of other mods and the game that only a client has. */
    private static final List<String> CLIENT_LIBRARIES = List.of(
            "net/minecraft/client/", "com/mojang/blaze3d/", "net/neoforged/neoforge/client/");

    /**
     * Parts of this mod that only a client loads, besides {@code dev.logos.client} and the mixins that the mixin
     * configs list under {@code client}.
     */
    private static final List<String> CLIENT_ONLY = List.of(
            // JEI finds its plugins by annotation and loads them on a client only.
            "dev/logos/compat/jei/",
            // AI Player, a client-side mod, loads this by the name in assets/logos/ai_usage when one of its actions is used.
            "dev/logos/compat/aiplayer/AiPlayerActions");

    /** Never in the released jar, and free to use both sides: the in-world tests. */
    private static final String DEV_ONLY = "dev/logos/gametest/";

    /**
     * The one class of {@code dev.logos.client} that any other code may call: each method is an entry point for a
     * client, and the caller checks which side it is on first ({@code level.isClientSide()}), or runs from a click.
     */
    private static final String GATE = "dev/logos/client/ClientHooks";

    /** Every other place where server-side code names a client-only class, and why that is safe. */
    private static final Map<String, Set<String>> KNOWN = Map.of(
            // GeckoLib's way to give an item its renderer: the provider is made on a client only.
            "dev/logos/item/AlchemyTableItem", Set.of("dev/logos/client/AlchemyTableItemRenderer"),
            // The parameter of a drawing method, which only a client's frame calls.
            "dev/logos/ui/Manuscript", Set.of("net/minecraft/client/gui/GuiGraphicsExtractor"),
            // The width of a word in the client's font, asked only after EffectiveSide says this is not a server.
            "dev/logos/ui/ManuscriptControls", Set.of("net/minecraft/client/Minecraft", "net/minecraft/client/gui/Font"));

    @Test
    void serverSideCodeNamesNoClientClass() throws IOException {
        Set<String> clientMixins = clientMixins(CompiledClasses.project().resolve(RESOURCES));
        Map<String, Set<String>> found = new TreeMap<>();
        CompiledClasses.references().forEach((from, named) -> {
            if (from.startsWith(DEV_ONLY) || clientOnly(from, clientMixins)) return;
            for (String to : named) {
                if (to.equals(GATE) || !clientOnly(to, clientMixins) && CLIENT_LIBRARIES.stream().noneMatch(to::startsWith)) continue;
                found.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
            }
        });
        Map<String, Set<String>> known = new TreeMap<>();
        KNOWN.forEach((from, to) -> known.put(from, new TreeSet<>(to)));
        assertEquals(describe(known), describe(found), """
                Code that a dedicated server runs names a client-only class (or a note below is out of date).
                A server has no such class and crashes when it reaches one, though a development server does not.
                Move the client part into dev.logos.client and call it through ClientHooks behind a side check;
                or, if this place is safe as it is, add it to KNOWN in this test with the reason.
                Left: what this test knows about. Right: what the compiled classes hold.""");
    }

    private static String describe(Map<String, Set<String>> edges) {
        StringBuilder out = new StringBuilder();
        edges.forEach((from, to) -> to.forEach(t -> out.append(from).append(" -> ").append(t).append('\n')));
        return out.toString();
    }

    private static boolean clientOnly(String name, Set<String> clientMixins) {
        return name.startsWith(MOD + "client/") || clientMixins.contains(name) || CLIENT_ONLY.stream().anyMatch(name::startsWith);
    }

    /** The mixins that the mixin configs apply on a client only. */
    private static Set<String> clientMixins(Path resources) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.list(resources)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".mixins.json")).toList()) {
                JsonObject config = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                String pkg = config.get("package").getAsString().replace('.', '/') + "/";
                if (config.has("client")) {
                    for (JsonElement mixin : config.getAsJsonArray("client")) out.add(pkg + mixin.getAsString());
                }
            }
        }
        return out;
    }
}
