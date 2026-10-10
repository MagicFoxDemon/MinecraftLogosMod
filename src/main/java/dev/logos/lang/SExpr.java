package dev.logos.lang;

import java.util.List;

/** Raw surface syntax: an S-expression tree before typing. */
public sealed interface SExpr {
    int line();

    int col();

    record Atom(String text, int line, int col) implements SExpr {
        @Override
        public String toString() {
            return text;
        }
    }

    record Str(String text, int line, int col) implements SExpr {
        @Override
        public String toString() {
            return '"' + text + '"';
        }
    }

    record ListExpr(List<SExpr> items, int line, int col) implements SExpr {
        public String head() {
            return !items.isEmpty() && items.getFirst() instanceof Atom a ? a.text() : null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(items.get(i));
            }
            return sb.append(')').toString();
        }
    }
}
