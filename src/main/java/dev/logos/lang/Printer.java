package dev.logos.lang;

import java.util.List;

import dev.logos.lang.SExpr.Atom;
import dev.logos.lang.SExpr.ListExpr;

/** Pretty-prints S-expressions as readable, indented Logos source. */
public final class Printer {
    private static final int WIDTH = 56;

    private Printer() {}

    public static String print(List<SExpr> top) {
        StringBuilder sb = new StringBuilder();
        for (SExpr e : top) {
            print(e, 0, sb);
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void print(SExpr e, int indent, StringBuilder sb) {
        String flat = e.toString();
        if (!(e instanceof ListExpr l) || flat.length() + indent <= WIDTH || l.items().size() < 2) {
            sb.append(flat);
            return;
        }
        // Head, then atoms and ":key value" pairs stay inline; nested lists go on their own lines.
        List<SExpr> items = l.items();
        sb.append('(').append(items.getFirst());
        int i = 1;
        int col = indent + 1 + items.getFirst().toString().length();
        while (i < items.size()) {
            SExpr it = items.get(i);
            boolean kwPair = it instanceof Atom a && a.text().startsWith(":") && i + 1 < items.size();
            String chunk = kwPair ? it + " " + items.get(i + 1) : it.toString();
            if (it instanceof ListExpr || chunk.length() + col > WIDTH + 12) break;
            sb.append(' ').append(chunk);
            col += chunk.length() + 1;
            i += kwPair ? 2 : 1;
        }
        for (; i < items.size(); i++) {
            SExpr it = items.get(i);
            sb.append('\n').append("  ".repeat(indent / 2 + 1));
            if (it instanceof Atom a && a.text().startsWith(":") && i + 1 < items.size()) {
                sb.append(a.text()).append(' ');
                print(items.get(++i), indent + 2 + a.text().length() + 1, sb);
            } else {
                print(it, indent + 2, sb);
            }
        }
        sb.append(')');
    }
}
