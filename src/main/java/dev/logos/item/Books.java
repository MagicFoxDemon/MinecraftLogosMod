package dev.logos.item;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

/** Spell source lives in vanilla books: book & quill to edit, the wand to compile. */
public final class Books {
    private static final int LINES_PER_PAGE = 11;

    private Books() {}

    /** The full text of a writable or written book, or null if the stack is not a book. */
    public static String read(ItemStack stack) {
        WritableBookContent w = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (w != null) {
            List<String> pages = new ArrayList<>();
            w.getPages(false).forEach(pages::add);
            String s = String.join("\n", pages);
            return s.isBlank() ? null : s;
        }
        WrittenBookContent wr = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (wr != null) {
            List<String> pages = new ArrayList<>();
            for (Component c : wr.getPages(false)) pages.add(c.getString());
            String s = String.join("\n", pages);
            return s.isBlank() ? null : s;
        }
        return null;
    }

    public static ItemStack create(String title, String source) {
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        List<Filterable<String>> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        int lines = 0;
        for (String line : source.split("\n", -1)) {
            if (lines == LINES_PER_PAGE) {
                pages.add(Filterable.passThrough(page.toString()));
                page.setLength(0);
                lines = 0;
            }
            if (lines > 0) page.append('\n');
            page.append(line);
            lines++;
        }
        if (!page.isEmpty()) pages.add(Filterable.passThrough(page.toString()));
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages));
        book.set(DataComponents.CUSTOM_NAME, Component.literal("术式 · " + title));
        return book;
    }
}
