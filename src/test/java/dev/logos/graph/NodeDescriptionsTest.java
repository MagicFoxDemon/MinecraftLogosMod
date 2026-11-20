package dev.logos.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import dev.logos.lang.Category;
import dev.logos.lang.Type;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/** Bilingual completeness and quality checks for {@link NodeDescriptions}, categories, and language files. */
class NodeDescriptionsTest {
    @Test
    void languageFilesStayAlignedAndEnglishFreeOfCjk() throws IOException {
        Set<String> zhKeys = JsonParser.parseString(Files.readString(langFile("zh_cn"), StandardCharsets.UTF_8)).getAsJsonObject().keySet();
        var enJson = JsonParser.parseString(Files.readString(langFile("en_us"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(zhKeys, enJson.keySet(), "zh_cn and en_us must define the exact same key set");

        for (String key : enJson.keySet()) {
            String value = enJson.get(key).getAsString();
            assertFalse(value.isEmpty(), "empty en_us entry for " + key);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\u3030') continue; // wavy dash glyph used on Motion.SNAKE
                boolean cjk = (c >= '\u4e00' && c <= '\u9fff')
                        || (c >= '\u3000' && c <= '\u303f')
                        || (c >= '\uff00' && c <= '\uffef');
                assertFalse(cjk, "unexpected CJK character in en_us.json at " + key + ": " + value);
            }
        }
    }

    @Test
    void everySupportedNodeAndParameterIsDescribedInBothLanguages() throws Exception {
        LTypes.init();
        Set<LogosNode> nodes = new LinkedHashSet<>();
        for (var type : SpellGraph.NODES.getNodeClasses()) {
            if (!LogosNode.class.isAssignableFrom(type)
                    || Modifier.isAbstract(type.getModifiers())
                    || EnumValues.Value.class.isAssignableFrom(type)) continue;
            nodes.add((LogosNode) type.getDeclaredConstructor().newInstance());
        }
        assertFalse(nodes.isEmpty(), "expected registered spell nodes");
        assertTrue(nodes.stream().anyMatch(n -> n.word().equals("imbue")), "expected imbue node to be registered");

        Language previous = Language.getInstance();
        try {
            for (String lang : List.of("zh_cn", "en_us")) {
                Map<String, String> entries = loadLang(lang);
                Language.inject(mapLanguage(entries));

                for (Category c : Category.values()) {
                    assertTrue(entries.containsKey("logos.category." + c.name().toLowerCase(Locale.ROOT)),
                            lang + " missing category name for " + c);
                    assertFalse(c.localizedName().isBlank(), lang + " blank category name for " + c);
                }

                String missingText = entries.get("logos.desc.missing");
                assertNotNull(missingText, lang + " missing logos.desc.missing");

                for (LogosNode node : nodes) {
                    String word = node.word();
                    assertTrue(entries.containsKey(node.nameKey()), lang + " missing node name key " + node.nameKey());

                    NodeDescriptions.Help help = NodeDescriptions.help(node);
                    assertNotNull(help, lang + " missing help for node " + word);
                    assertFalse(help.intro().isBlank(), lang + " blank intro for node " + word);
                    for (NodeDescriptions.Line line : help.lines()) {
                        assertFalse(line.label().isBlank(), lang + " blank line label on " + word);
                        assertFalse(line.text().isBlank(), lang + " blank line text on " + word + "/" + line.label());
                        assertFalse(line.text().equals(missingText),
                                lang + " fell back to missing placeholder on " + word + "/" + line.label());
                    }
                    String described = NodeDescriptions.describe(node);
                    assertFalse(described.isBlank(), lang + " empty describe() for " + word);
                    assertFalse(described.contains(missingText), lang + " missing param fallback in describe() for " + word);

                    if (node instanceof RuneNode runeNode) {
                        var rune = runeNode.rune();
                        if (rune.returns == Type.ANY) {
                            assertHasParam(entries, lang, word, RuneNode.KIND);
                        }
                        for (var param : rune.params) {
                            if (param.rest()) assertHasParam(entries, lang, word, "argument-count");
                            assertHasParam(entries, lang, word, param.name());
                        }
                        for (var keyword : rune.keywords.values()) {
                            assertHasParam(entries, lang, word, keyword.name());
                        }
                        if (rune.body) assertHasParam(entries, lang, word, RuneNode.BODY);
                        if (rune.yields != null) assertHasParam(entries, lang, word, RuneNode.YIELD);
                        for (var event : rune.events.values()) {
                            assertHasParam(entries, lang, word, event.name());
                        }
                    }
                }
            }
        } finally {
            Language.inject(previous);
        }
    }

    private static void assertHasParam(Map<String, String> entries, String lang, String word, String id) {
        String key = "logos.desc." + word + ".param." + id;
        assertTrue(entries.containsKey(key), lang + " missing description key " + key);
        assertFalse(entries.get(key).isBlank(), lang + " blank description key " + key);
    }

    private static Map<String, String> loadLang(String lang) throws IOException {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = Files.newInputStream(langFile(lang))) {
            Language.loadFromJson(in, map::put);
        }
        return map;
    }

    private static Language mapLanguage(Map<String, String> entries) {
        return new Language() {
            @Override
            public String getOrDefault(String id, String fallback) {
                return entries.getOrDefault(id, fallback);
            }

            @Override
            public boolean has(String id) {
                return entries.containsKey(id);
            }

            @Override
            public boolean isDefaultRightToLeft() {
                return false;
            }

            @Override
            public FormattedCharSequence getVisualOrder(FormattedText text) {
                return sink -> false;
            }
        };
    }

    private static Path langFile(String lang) {
        Path relative = Path.of("src/main/resources/assets/logos/lang/" + lang + ".json");
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(relative))) return dir.resolve(relative);
        }
        throw new IllegalStateException("no " + relative + " above " + Path.of("").toAbsolutePath());
    }
}
