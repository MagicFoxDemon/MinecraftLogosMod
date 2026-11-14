package dev.logos.client;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.client.font.LDFonts;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import com.lowdragmc.lowdraglib2.utils.TextUtilities;

import dev.logos.LogosMod;
import dev.logos.graph.Sealed;
import dev.logos.guide.GuideBook;
import dev.logos.guide.GuideDoc;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;

/**
 * One page of 逻各斯手记 drawn in ink: its title, then each block of its Markdown as an element. Links in the text are
 * followed by clicking them; items show their tooltips; recipes are drawn from those the server sent.
 */
public final class GuidePage {
    /** What a link carries in its text's style: clicking it follows {@link GuideDoc.Span#link()}. */
    static final Identifier LINK = Identifier.fromNamespaceAndPath(LogosMod.MODID, "guide");
    /** Rune words written as code, in the teal of the workbench's mercury. */
    private static final int CODE = 0xFF1F5E66;
    private static final int GOLD = 0xFF8A6418;

    private GuidePage() {}

    /** Draws the entry {@code id} of {@code book} into {@code into}; {@code follow} is handed the link a reader clicks. */
    static void render(GuideBook book, @Nullable String id, Consumer<String> follow, UIElement into) {
        GuideBook.Entry entry = id == null ? null : book.entry(id);
        if (entry == null) {
            into.addChild(text(Component.translatable(book.isEmpty() ? "logos.guide.empty" : "logos.guide.missing", String.valueOf(id))
                    .withColor(Manuscript.INK_FADED), follow));
            return;
        }
        if (!entry.shownTo(Sealed.known)) {
            // A word not learned yet: a link may still lead here, but its page is blank, its name unwritten.
            into.addChild(lockedTitle());
            into.addChild(text(Component.translatable("logos.guide.runes.locked_page").withColor(Manuscript.INK_FADED), follow));
            return;
        }
        into.addChild(title(entry));
        for (GuideDoc.Block block : entry.doc().blocks()) {
            UIElement element = switch (block) {
                case GuideDoc.Heading h -> heading(h, follow);
                case GuideDoc.Paragraph p -> text(component(p.text()), follow);
                case GuideDoc.Bullet b -> bullet(b, follow);
                case GuideDoc.Rule r -> rule();
                case GuideDoc.Tag t -> tag(t, follow);
            };
            into.addChild(element);
        }
    }

    // ---- text ----------------------------------------------------------------------------

    /** The page's title: its icon (a rune's own drawing on a rune's page) and its name over a gilt rule. */
    private static UIElement title(GuideBook.Entry entry) {
        UIElement row = titleRow();
        GuideRunes.Word word = GuideRunes.word(entry.id());
        ItemStack icon = stack(entry.icon());
        if (word != null) {
            UIElement shown = runeSquare(word, true, s -> {});
            shown.setId("guide_title_icon");
            row.addChild(shown);
        } else if (!icon.isEmpty()) {
            UIElement shown = item(List.of(icon), false);
            shown.setId("guide_title_icon");
            row.addChild(shown);
        }
        Label name = new Label();
        name.setText(Component.literal(entry.title()).withStyle(ChatFormatting.BOLD));
        name.textStyle(s -> s.textColor(Manuscript.INK).textShadow(false).textWrap(TextWrap.WRAP).adaptiveHeight(true));
        name.layout(l -> l.flex(1));
        row.addChild(name);
        return row;
    }

    private static UIElement titleRow() {
        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER)
                .widthPercent(100).gapAll(4).paddingBottom(3).marginBottom(2));
        row.setId("guide_title");
        row.style(s -> s.backgroundTexture((GuiTexture) (context, x, y, w, h) -> {
            DrawerHelperClient.drawSolidRect(context, x, y + h - 2, w, 1, GOLD);
            DrawerHelperClient.drawSolidRect(context, x, y + h - 1, w, 1, 0x553B2614);
        }));
        return row;
    }

    /** The title of a page whose word is not learned: a black square and no name. */
    private static UIElement lockedTitle() {
        UIElement row = titleRow();
        row.addChild(lockedSquare());
        Label name = new Label();
        name.setText(Component.literal("？？？").withStyle(ChatFormatting.BOLD));
        name.textStyle(s -> s.textColor(Manuscript.INK_FADED).textShadow(false));
        row.addChild(name);
        return row;
    }

    private static UIElement heading(GuideDoc.Heading h, Consumer<String> follow) {
        MutableComponent text = component(h.text()).withStyle(ChatFormatting.BOLD);
        int color = h.level() == 1 ? Manuscript.INK : h.level() == 2 ? Manuscript.SANGUINE : Manuscript.INK_FADED;
        Label label = text(text.withColor(color), follow);
        label.layout(l -> l.marginTop(h.level() == 1 ? 6 : 4));
        return label;
    }

    private static UIElement bullet(GuideDoc.Bullet b, Consumer<String> follow) {
        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).gapAll(3).paddingLeft(4));
        Label mark = new Label();
        mark.setText(Component.literal(b.number() > 0 ? b.number() + "." : "•").withColor(b.number() > 0 ? Manuscript.INK_FADED : Manuscript.SANGUINE));
        mark.textStyle(s -> s.textShadow(false).adaptiveWidth(true));
        Label text = text(component(b.text()), follow);
        text.layout(l -> l.flex(1).minWidth(0));
        row.addChildren(mark, text);
        return row;
    }

    private static UIElement rule() {
        UIElement rule = new UIElement().layout(l -> l.widthPercent(100).height(1).marginVertical(3));
        rule.style(s -> s.backgroundTexture((GuiTexture) (context, x, y, w, h) -> DrawerHelperClient.drawSolidRect(context, x + w * 0.1f, y, w * 0.8f, 1, 0x887A5A3A)));
        return rule;
    }

    /** A paragraph's runs as one text: links in red chalk and underlined, items in gold, rune words in teal. */
    static MutableComponent component(List<GuideDoc.Span> spans) {
        MutableComponent out = Component.empty();
        for (GuideDoc.Span s : spans) {
            Style style = Style.EMPTY.withBold(s.bold() ? true : null).withItalic(s.italic() ? true : null);
            if (s.code()) style = style.withColor(CODE);
            if (s.link() != null) {
                style = style.withClickEvent(new ClickEvent.Custom(LINK, Optional.of(StringTag.valueOf(s.link()))));
                style = GuideBook.isItem(s.link()) ? style.withColor(GOLD) : style.withColor(Manuscript.SANGUINE).withUnderlined(true);
            }
            out.append(Component.literal(s.text()).withStyle(style));
        }
        return out;
    }

    private static Label text(Component text, Consumer<String> follow) {
        Label label = new LinkedText(follow);
        label.setText(text);
        label.textStyle(s -> s.textColor(Manuscript.INK).textShadow(false).textWrap(TextWrap.WRAP).adaptiveHeight(true));
        label.layout(l -> l.widthPercent(100));
        return label;
    }

    /**
     * Text whose links are followed: the line under the pointer is found by wrapping the text exactly as the label
     * draws it, and the run under the pointer by measuring along that line.
     */
    public static final class LinkedText extends Label {
        LinkedText(Consumer<String> follow) {
            addEventListener(UIEvents.MOUSE_DOWN, e -> {
                if (e.button != 0) return;
                String link = link(styleAt(e.x, e.y));
                if (link != null) follow.accept(link);
            });
            addEventListener(UIEvents.HOVER_TOOLTIPS, e -> {
                String link = link(styleAt(e.x, e.y));
                if (link == null || !GuideBook.isItem(link)) return;
                ItemStack stack = stack(link.substring("item:".length()));
                if (!stack.isEmpty()) e.hoverTooltips = tooltips(stack);
            });
        }

        public static @Nullable String link(@Nullable Style style) {
            if (style != null && style.getClickEvent() instanceof ClickEvent.Custom custom && custom.id().equals(LINK)) {
                return custom.payload().flatMap(tag -> tag.asString()).orElse(null);
            }
            return null;
        }

        /** The style of the text at the screen point {@code x, y}, or null off the text. */
        public @Nullable Style styleAt(float x, float y) {
            Font font = LDFonts.font();
            float size = getTextStyle().fontSize();
            var lines = TextUtilities.computeFormattedLines(font, TextUtilities.withFont(getText(), getTextStyle().font()), size, getContentWidth());
            if (lines.isEmpty()) return null;
            int row = (int) Math.floor((y - getContentY()) / (size + getTextStyle().lineSpacing()));
            if (row < 0 || row >= lines.size()) return null;
            float scale = size / font.lineHeight;
            var finder = new ActiveTextCollector.ClickableStyleFinder(font, (int) ((x - getContentX()) / scale), font.lineHeight / 2);
            finder.accept(0, 0, lines.get(row).getA());
            return finder.result();
        }
    }

    // ---- tags ----------------------------------------------------------------------------

    private static UIElement tag(GuideDoc.Tag t, Consumer<String> follow) {
        return switch (t.name()) {
            case "items" -> items(t.args());
            case "image" -> image(t.args());
            case "recipe" -> recipes(t.args());
            case "runes" -> runes(t.args(), follow);
            case "rune_overview" -> overview(follow);
            default -> text(Component.translatable("logos.guide.unknown_tag", t.name()).withColor(Manuscript.SANGUINE), follow);
        };
    }

    /** {@code {{items <id> …}}}: a row of items, each showing its tooltip. */
    private static UIElement items(List<String> ids) {
        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP).widthPercent(100).gapAll(2).marginVertical(2));
        row.addClass("guide-items");
        for (String id : ids) {
            ItemStack stack = stack(id);
            if (!stack.isEmpty()) row.addChild(item(List.of(stack), true));
        }
        return row;
    }

    // ---- 符文图鉴 ------------------------------------------------------------------------

    /** Ink for a square whose word is not learned: nearly black, so the gap in the collection shows. */
    private static final int UNLEARNED = 0xFF1E160F, UNLEARNED_EDGE = 0xFF3B2A1C;

    /** {@code {{runes <word> …}}}: a row of words, each a square that opens its page, or a black one not yet learned. */
    private static UIElement runes(List<String> names, Consumer<String> follow) {
        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP).widthPercent(100).gapAll(2).marginVertical(2));
        row.addClass("guide-runes");
        for (String name : names) {
            GuideRunes.Word word = GuideRunes.named(name);
            if (word != null) row.addChild(GuideRunes.known(word) ? runeSquare(word, false, follow) : lockedSquare());
        }
        return row;
    }

    /** {@code {{rune_overview}}}: every family with how much of it is learned, and a square for each of its words. */
    private static UIElement overview(Consumer<String> follow) {
        UIElement column = new UIElement().layout(l -> l.widthPercent(100).gapAll(3).marginVertical(2));
        column.setId("rune_overview");
        for (GuideRunes.Family family : GuideRunes.families()) {
            long known = family.words().stream().filter(GuideRunes::known).count();
            UIElement head = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).widthPercent(100).gapAll(6).marginTop(4));
            Label name = new Label();
            name.setText(Component.literal(family.label()).withStyle(ChatFormatting.BOLD));
            name.textStyle(s -> s.textColor(Manuscript.INK).textShadow(false).adaptiveWidth(true));
            Label count = new Label();
            count.setText(Component.translatable("logos.guide.runes.count", known, family.words().size()));
            count.textStyle(s -> s.textColor(known == family.words().size() ? GOLD : Manuscript.INK_FADED).textShadow(false).adaptiveWidth(true));
            head.addChildren(name, count);
            UIElement grid = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP).widthPercent(100).gapAll(2));
            for (GuideRunes.Word word : family.words()) grid.addChild(GuideRunes.known(word) ? runeSquare(word, false, follow) : lockedSquare());
            column.addChildren(head, grid);
        }
        return column;
    }

    /** A learned word: its drawing on its family's wash; it names itself when pointed at and opens its page when clicked. */
    private static UIElement runeSquare(GuideRunes.Word word, boolean title, Consumer<String> follow) {
        UIElement square = new UIElement().layout(l -> l.width(20).height(20).flexShrink(0));
        square.addClass("guide-rune");
        if (!title) square.setId("rune_" + GuideScreen.safe(word.page()));
        int wash = ManuscriptNode.wash(word.ink());
        GuiTexture icon = word.icon();
        square.style(s -> s.backgroundTexture((GuiTexture) (c, x, y, w, h) -> {
            DrawerHelperClient.drawSolidRect(c, x, y, w, h, 0xFFF3E3BD);
            DrawerHelperClient.drawSolidRect(c, x, y, w, h, wash);
            if (!title && square.isHover()) DrawerHelperClient.drawSolidRect(c, x, y, w, h, 0x40FFFFFF);
            DrawerHelperClient.drawSolidRect(c, x, y, w, 1, 0x887A5A3A);
            DrawerHelperClient.drawSolidRect(c, x, y + h - 1, w, 1, 0x887A5A3A);
            DrawerHelperClient.drawSolidRect(c, x, y, 1, h, 0x887A5A3A);
            DrawerHelperClient.drawSolidRect(c, x + w - 1, y, 1, h, 0x887A5A3A);
            icon.draw(c, x, y, w, h);
        }).tooltips(Component.literal(word.name())));
        if (!title) {
            // On a whole click, so the page it opens does not take the release meant for the square.
            square.addEventListener(UIEvents.CLICK, e -> {
                if (e.button == 0) follow.accept(word.page());
            });
        }
        return square;
    }

    /** A word not learned yet: a black square, with nothing to say what it is. */
    private static UIElement lockedSquare() {
        UIElement square = new UIElement().layout(l -> l.width(20).height(20).flexShrink(0));
        square.addClass("guide-rune-locked");
        square.style(s -> s.backgroundTexture((GuiTexture) (c, x, y, w, h) -> {
            DrawerHelperClient.drawSolidRect(c, x, y, w, h, UNLEARNED_EDGE);
            DrawerHelperClient.drawSolidRect(c, x + 1, y + 1, w - 2, h - 2, UNLEARNED);
        }).tooltips(Component.translatable("logos.guide.runes.locked")));
        return square;
    }

    /** {@code {{image <texture> <width> <height>}}}: a picture from a texture, at its size in GUI pixels. */
    private static UIElement image(List<String> args) {
        Identifier texture = args.isEmpty() ? null : Identifier.tryParse(args.getFirst());
        int width = args.size() > 1 ? number(args.get(1), 64) : 64, height = args.size() > 2 ? number(args.get(2), width) : width;
        UIElement image = new UIElement().layout(l -> l.width(width).height(height).maxWidthPercent(100).alignSelf(AlignItems.CENTER).marginVertical(2));
        if (texture != null) image.style(s -> s.backgroundTexture(SpriteTexture.of(texture.toString())));
        return image;
    }

    private static int number(String text, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code {{recipe <item>}}}: every crafting and smithing recipe that makes the item, each on its own card. */
    private static UIElement recipes(List<String> args) {
        UIElement column = new UIElement().layout(l -> l.widthPercent(100).gapAll(4).marginVertical(2));
        column.addClass("guide-recipes");
        ItemStack target = args.isEmpty() ? ItemStack.EMPTY : stack(args.getFirst());
        ContextMap context = GuideRecipes.context();
        List<RecipeDisplay> found = target.isEmpty() || context == null ? List.of() : GuideRecipes.making(target.getItem());
        for (RecipeDisplay display : found) {
            UIElement card = card(display, context);
            if (card != null) column.addChild(card);
        }
        if (column.getChildren().isEmpty()) {
            Label none = new Label();
            none.setText(Component.translatable("logos.guide.recipe.none", target.isEmpty() ? String.join(" ", args) : target.getHoverName().getString()));
            none.textStyle(s -> s.textColor(Manuscript.INK_FADED).textShadow(false).textWrap(TextWrap.WRAP).adaptiveHeight(true));
            none.layout(l -> l.widthPercent(100));
            column.addChild(none);
        }
        return column;
    }

    /** A recipe on a card: what it is made on, its ingredients as it lays them out, an arrow and what it makes. */
    private static @Nullable UIElement card(RecipeDisplay display, ContextMap context) {
        UIElement grid;
        String kind;
        switch (display) {
            case ShapedCraftingRecipeDisplay shaped -> {
                kind = "logos.guide.recipe.crafting";
                grid = grid(shaped.ingredients(), shaped.width(), context);
            }
            case ShapelessCraftingRecipeDisplay shapeless -> {
                kind = "logos.guide.recipe.shapeless";
                grid = grid(shapeless.ingredients(), Math.min(3, Math.max(1, shapeless.ingredients().size())), context);
            }
            case SmithingRecipeDisplay smithing -> {
                kind = "logos.guide.recipe.smithing";
                grid = grid(List.of(smithing.template(), smithing.base(), smithing.addition()), 3, context);
            }
            default -> {
                return null;
            }
        }
        UIElement card = new UIElement().layout(l -> l.alignSelf(AlignItems.FLEX_START).gapAll(3).paddingAll(5));
        card.addClass("guide-recipe");
        card.style(s -> s.backgroundTexture((GuiTexture) (c, x, y, w, h) -> {
            DrawerHelperClient.drawSolidRect(c, x, y, w, h, 0x40F8ECCF);
            DrawerHelperClient.drawSolidRect(c, x, y, w, 1, 0x887A5A3A);
            DrawerHelperClient.drawSolidRect(c, x, y + h - 1, w, 1, 0x887A5A3A);
            DrawerHelperClient.drawSolidRect(c, x, y, 1, h, 0x887A5A3A);
            DrawerHelperClient.drawSolidRect(c, x + w - 1, y, 1, h, 0x887A5A3A);
        }));
        Label what = new Label();
        what.setText(Component.translatable(kind));
        what.textStyle(s -> s.textColor(Manuscript.INK_FADED).textShadow(false).adaptiveWidth(true));
        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(6));
        Label arrow = new Label();
        arrow.setText(Component.literal("→"));
        arrow.textStyle(s -> s.textColor(Manuscript.INK).textShadow(false).adaptiveWidth(true));
        row.addChildren(grid, arrow, item(display.result().resolveForStacks(context), true));
        card.addChildren(what, row);
        return card;
    }

    /** Ingredients in rows of {@code width}; an empty place is an empty square. */
    private static UIElement grid(List<SlotDisplay> slots, int width, ContextMap context) {
        UIElement grid = new UIElement().layout(l -> l.gapAll(1));
        UIElement row = null;
        for (int i = 0; i < slots.size(); i++) {
            if (i % width == 0) {
                row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(1));
                grid.addChild(row);
            }
            row.addChild(item(slots.get(i).resolveForStacks(context), true));
        }
        return grid;
    }

    /**
     * An item in a square: with several, it shows each in turn, a second apiece (a tag's items, say). It shows the
     * tooltip of the one shown; {@code framed} draws the square as a slot pressed into the paper.
     */
    private static UIElement item(List<ItemStack> stacks, boolean framed) {
        List<ItemStack> shown = stacks.stream().filter(s -> !s.isEmpty()).toList();
        UIElement slot = new UIElement().layout(l -> l.width(18).height(18).flexShrink(0).paddingAll(1));
        slot.addClass("guide-item");
        if (framed) {
            slot.style(s -> s.backgroundTexture((GuiTexture) (c, x, y, w, h) -> {
                DrawerHelperClient.drawSolidRect(c, x, y, w, h, 0x30000000);
                DrawerHelperClient.drawSolidRect(c, x, y, w, 1, 0x667A5A3A);
                DrawerHelperClient.drawSolidRect(c, x, y, 1, h, 0x667A5A3A);
                DrawerHelperClient.drawSolidRect(c, x, y + h - 1, w, 1, 0x66FFF6DC);
                DrawerHelperClient.drawSolidRect(c, x + w - 1, y, 1, h, 0x66FFF6DC);
            }));
        }
        if (shown.isEmpty()) return slot;
        UIElement icon = new UIElement().layout(l -> l.width(16).height(16));
        ItemStackTexture texture = new ItemStackTexture(shown.getFirst());
        icon.style(s -> s.backgroundTexture(texture));
        icon.setAllowHitTest(false);
        slot.addChild(icon);
        if (shown.size() > 1) {
            slot.addEventListener(UIEvents.TICK, e -> texture.setItems(now(shown)));
        }
        slot.addEventListener(UIEvents.HOVER_TOOLTIPS, e -> e.hoverTooltips = tooltips(now(shown)));
        return slot;
    }

    private static ItemStack now(List<ItemStack> stacks) {
        return stacks.get((int) (System.currentTimeMillis() / 1000 % stacks.size()));
    }

    static HoverTooltips tooltips(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        return HoverTooltips.create(Screen.getTooltipFromItem(mc, stack).toArray())
                .append(stack.getTooltipImage().orElse(null)).stack(stack);
    }

    /** The item named by {@code id}, or nothing when no item has that id. */
    static ItemStack stack(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) return ItemStack.EMPTY;
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(key);
        return item.map(ItemStack::new).orElse(ItemStack.EMPTY);
    }
}
