package dev.logos.client;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;

import dev.logos.LogosMod;
import dev.logos.graph.EnumValues;
import dev.logos.graph.LTypes;
import dev.logos.graph.LogosNode;
import dev.logos.graph.NodeDescriptions;
import dev.logos.graph.Sealed;
import dev.logos.graph.SpecialNodes;
import dev.logos.graph.SpellGraph;
import dev.logos.guide.GuideBook;
import dev.logos.guide.GuideDoc;
import dev.logos.guide.GuideDoc.Block;
import dev.logos.guide.GuideDoc.Span;
import dev.logos.guide.RuneCodex;
import dev.logos.item.RuneBookItem;
import dev.logos.lang.Category;
import dev.logos.lang.Compiler;
import dev.logos.lang.Element;
import dev.logos.lang.Examples;
import dev.logos.lang.Motion;
import dev.logos.lang.Node;
import dev.logos.lang.Unlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 符文图鉴: a page of 逻各斯手记 for every node the workbench offers, every element and every motion, made from the
 * editor's own help ({@link NodeDescriptions}) and names, so it never falls behind them. A page whose word the reader
 * has not learned is not in the book at all ({@link GuideBook.Entry#lock()}); the overview shows it as a black
 * square. Made again when the language changes.
 */
public final class GuideRunes {
    /** One square of the overview: the page it opens, the word to learn (null when no one has to), its name and picture. */
    public record Word(String page, @Nullable String lock, String name, Category ink, GuiTexture icon) {}

    /** A family of words, grouped as the workbench's library groups them. */
    public record Family(String label, List<Word> words) {}

    private static @Nullable String language;
    private static List<Family> families = List.of();
    private static List<GuideBook.Entry> entries = List.of();
    private static Map<String, Word> byPage = Map.of();

    private GuideRunes() {}

    public static List<Family> families() {
        ensure();
        return families;
    }

    public static List<GuideBook.Entry> entries() {
        ensure();
        return entries;
    }

    /** The word whose page is {@code page}, or null for a page that is not one. */
    public static @Nullable Word word(String page) {
        ensure();
        return byPage.get(page);
    }

    /** The word a {@code {{runes}}} tag names: a rune by its word ({@code damage}), or {@code element:fire}, {@code motion:arc}. */
    public static @Nullable Word named(String name) {
        ensure();
        if (name.contains(":")) return byPage.get(RuneCodex.page(name));
        Word rune = byPage.get(RuneCodex.page(Unlocks.rune(name)));
        if (rune != null) return rune;
        Word plain = byPage.get(RuneCodex.unlockedPage(name, false));
        return plain != null ? plain : byPage.get(RuneCodex.unlockedPage(name, true));
    }

    /** Whether the reader has learned {@code word}'s word, or need not. */
    public static boolean known(Word word) {
        return word.lock() == null || Sealed.known.test(word.lock());
    }

    private static synchronized void ensure() {
        String now = Minecraft.getInstance().getLanguageManager().getSelected();
        if (now.equals(language)) return;
        build();
        language = now;
    }

    private static String t(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static void build() {
        Map<String, List<String>> examples = examplesByWord();
        List<Family> outFamilies = new ArrayList<>();
        List<GuideBook.Entry> outEntries = new ArrayList<>();
        Map<String, Word> outPages = new HashMap<>();

        // The workbench's families, in the library's order; contexts are a family of their own.
        Map<String, List<LogosNode>> byFamily = new LinkedHashMap<>();
        for (Category c : new Category[]{Category.INVOCATION, Category.FORM, Category.BINDING}) byFamily.put(c.name(), new ArrayList<>());
        byFamily.put("CONTEXT", new ArrayList<>());
        for (Category c : new Category[]{Category.EFFECT, Category.LOGIC, Category.VALUE}) byFamily.put(c.name(), new ArrayList<>());
        LTypes.init();
        for (var type : SpellGraph.NODES.getNodeClasses()) {
            if (!LogosNode.class.isAssignableFrom(type) || Modifier.isAbstract(type.getModifiers()) || EnumValues.Value.class.isAssignableFrom(type)) continue;
            LogosNode node;
            try {
                node = (LogosNode) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | RuntimeException e) {
                LogosMod.LOGGER.debug("No 符文图鉴 page for {}: {}", type.getName(), e.toString());
                continue;
            }
            List<LogosNode> family = byFamily.get(node instanceof SpecialNodes.ContextValue ? "CONTEXT" : node.category().name());
            if (family != null) family.add(node);
        }
        int index = 0;
        for (var family : byFamily.entrySet()) {
            index++;
            boolean context = family.getKey().equals("CONTEXT");
            Category ink = context ? Category.BINDING : Category.valueOf(family.getKey());
            String label = context ? t("logos.editor.library.context") : ink.zh;
            List<Word> words = new ArrayList<>();
            int n = 0;
            for (LogosNode node : family.getValue()) {
                String lock = Sealed.key(node);
                String page = lock != null ? RuneCodex.page(lock) : RuneCodex.unlockedPage(node.word(), context);
                if (outPages.containsKey(page)) continue;
                String name = Component.translatableWithFallback(node.nameKey(), node.word()).getString();
                Word word = new Word(page, lock, name, ink, ManuscriptNode.emblem(node));
                words.add(word);
                outPages.put(page, word);
                outEntries.add(entry(word, index * 1000 + n++, label, node.word(), runeBlocks(node, label, lock, examples)));
            }
            if (!words.isEmpty()) outFamilies.add(new Family(label, List.copyOf(words)));
        }

        index++;
        String elements = t("logos.guide.runes.elements");
        List<Word> elementWords = new ArrayList<>();
        for (Element e : Element.values()) {
            String id = e.name().toLowerCase(Locale.ROOT), lock = Unlocks.element(e);
            Word word = new Word(RuneCodex.page(lock), lock, EnumCatalog.name(EnumValues.Kind.ELEMENT, id).getString(), Category.EFFECT,
                    EnumPreviews.cardIcon(EnumValues.Kind.ELEMENT, id));
            List<Block> blocks = new ArrayList<>();
            blocks.add(heading(elements, id));
            blocks.add(paragraph(t("logos.guide.runes.element.cost", Node.fmt(e.costFactor))));
            learn(lock, blocks);
            uses(examples.get(lock), blocks);
            elementWords.add(word);
            outPages.put(word.page(), word);
            outEntries.add(entry(word, index * 1000 + e.ordinal(), elements, id, blocks));
        }
        outFamilies.add(new Family(elements, List.copyOf(elementWords)));

        index++;
        String motions = t("logos.guide.runes.motions");
        List<Word> motionWords = new ArrayList<>();
        for (Motion m : Motion.values()) {
            String id = m.name().toLowerCase(Locale.ROOT), lock = Unlocks.motion(m);
            Word word = new Word(RuneCodex.page(lock), lock, EnumCatalog.name(EnumValues.Kind.MOTION, id).getString(), Category.MOTION,
                    EnumPreviews.cardIcon(EnumValues.Kind.MOTION, id));
            List<Block> blocks = new ArrayList<>();
            blocks.add(heading(motions, id));
            blocks.add(paragraph(m.mana > 0 ? t("logos.guide.runes.motion.cost", Node.fmt(m.mana)) : t("logos.guide.runes.motion.free")));
            if (m.flies()) blocks.add(new GuideDoc.Bullet(0, List.of(Span.plain(t("logos.guide.runes.motion.flies")))));
            if (m.crawls()) blocks.add(new GuideDoc.Bullet(0, List.of(Span.plain(t("logos.guide.runes.motion.crawls")))));
            learn(lock, blocks);
            uses(examples.get(lock), blocks);
            motionWords.add(word);
            outPages.put(word.page(), word);
            outEntries.add(entry(word, index * 1000 + m.ordinal(), motions, id, blocks));
        }
        outFamilies.add(new Family(motions, List.copyOf(motionWords)));

        families = List.copyOf(outFamilies);
        entries = List.copyOf(outEntries);
        byPage = Map.copyOf(outPages);
    }

    private static GuideBook.Entry entry(Word word, int order, String group, String written, List<Block> blocks) {
        return new GuideBook.Entry(word.page(), RuneCodex.CHAPTER, word.name(), "", order, group, word.lock(),
                new GuideDoc(Map.of("title", word.name(), "word", written), List.copyOf(blocks)));
    }

    /** A node's page: its family and written word, then the workbench's help for it, how to learn it, and where it is used. */
    private static List<Block> runeBlocks(LogosNode node, String family, @Nullable String lock, Map<String, List<String>> examples) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(heading(family, node.word()));
        NodeDescriptions.Help help = NodeDescriptions.help(node);
        if (help != null) {
            blocks.add(paragraph(help.intro()));
            if (!help.lines().isEmpty()) {
                blocks.add(new GuideDoc.Heading(2, List.of(Span.plain(t("logos.guide.runes.ports")))));
                for (var line : help.lines()) {
                    blocks.add(new GuideDoc.Bullet(0, List.of(bold(line.label()), Span.plain("：" + line.text()))));
                }
            }
            if (help.result() != null) blocks.add(new GuideDoc.Paragraph(List.of(bold(t("logos.guide.runes.result") + "："), Span.plain(help.result()))));
            if (help.note() != null) blocks.add(new GuideDoc.Paragraph(List.of(bold(t("logos.guide.runes.note") + "："), Span.plain(help.note()))));
        } else if (!node.doc().isBlank()) {
            blocks.add(paragraph(node.doc()));
        }
        learn(lock, blocks);
        if (lock != null) uses(examples.get(lock), blocks);
        return blocks;
    }

    /** The line under a page's title: its family, and the word it is written as in a spell's source. */
    private static Block heading(String family, String word) {
        return new GuideDoc.Paragraph(List.of(bold(t("logos.guide.runes.family") + "："), Span.plain(family + "　"),
                bold(t("logos.guide.runes.word") + "："), new Span(word, false, false, true, null)));
    }

    /** How the word is learned: by everyone, from the start, from these books, or by none. */
    private static void learn(@Nullable String lock, List<Block> blocks) {
        blocks.add(new GuideDoc.Heading(2, List.of(Span.plain(t("logos.guide.runes.learn")))));
        if (lock == null) {
            blocks.add(paragraph(t("logos.guide.runes.learn.everyone")));
            return;
        }
        if (Unlocks.STARTING.contains(lock)) {
            blocks.add(paragraph(t("logos.guide.runes.learn.start")));
            return;
        }
        // 启悟之书 teaches everything at once, so it says nothing about where this word in particular is found.
        List<RuneBookItem.Kind> books = new ArrayList<>();
        for (RuneBookItem.Kind kind : RuneBookItem.Kind.values()) if (!kind.everything() && kind.pool().contains(lock)) books.add(kind);
        if (books.isEmpty()) {
            blocks.add(paragraph(t("logos.guide.runes.learn.none")));
            return;
        }
        blocks.add(paragraph(t("logos.guide.runes.learn.books")));
        for (RuneBookItem.Kind kind : books) {
            String name = new ItemStack(LogosMod.RUNE_BOOKS.get(kind).get()).getHoverName().getString();
            blocks.add(new GuideDoc.Bullet(0, List.of(new Span(name, false, false, false, "item:" + LogosMod.MODID + ":" + kind.id))));
        }
    }

    /** The example spells that write the word, by name. */
    private static void uses(@Nullable List<String> examples, List<Block> blocks) {
        if (examples == null || examples.isEmpty()) return;
        blocks.add(new GuideDoc.Heading(2, List.of(Span.plain(t("logos.guide.runes.examples")))));
        blocks.add(paragraph(String.join("、", examples)));
    }

    /** For every word to learn, the names of the example spells that write it. */
    private static Map<String, List<String>> examplesByWord() {
        Map<String, List<String>> out = new HashMap<>();
        for (var example : Examples.ALL.entrySet()) {
            String name = Component.translatableWithFallback("logos.example." + example.getKey(), example.getKey()).getString();
            try {
                for (String key : Unlocks.written(Compiler.compile(example.getValue())).keySet()) {
                    out.computeIfAbsent(key, k -> new ArrayList<>()).add(name);
                }
            } catch (RuntimeException e) {
                LogosMod.LOGGER.debug("Example {} did not compile for 符文图鉴: {}", example.getKey(), e.toString());
            }
        }
        return out;
    }

    private static Block paragraph(String text) {
        return new GuideDoc.Paragraph(List.of(Span.plain(text)));
    }

    private static Span bold(String text) {
        return new Span(text, true, false, false, null);
    }
}
