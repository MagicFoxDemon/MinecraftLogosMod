package dev.logos.lang;

import java.util.ArrayList;
import java.util.List;

/** Tokenizes and parses Logos source text into S-expressions. ';' starts a line comment. */
public final class Reader {
    private final String src;
    private int pos;
    private int line = 1;
    private int col = 1;

    private Reader(String src) {
        this.src = src;
    }

    public static List<SExpr> read(String src) {
        Reader r = new Reader(src);
        List<SExpr> out = new ArrayList<>();
        while (true) {
            r.skipWs();
            if (r.pos >= r.src.length()) return out;
            out.add(r.readExpr());
        }
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ';') {
                while (pos < src.length() && src.charAt(pos) != '\n') advance();
            } else if (Character.isWhitespace(c)) {
                advance();
            } else {
                return;
            }
        }
    }

    private char advance() {
        char c = src.charAt(pos++);
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        return c;
    }

    private SExpr readExpr() {
        int l = line, c = col;
        char ch = src.charAt(pos);
        if (ch == '(' || ch == '[') {
            char close = ch == '(' ? ')' : ']';
            advance();
            List<SExpr> items = new ArrayList<>();
            while (true) {
                skipWs();
                if (pos >= src.length()) throw new LogosError("括号未闭合：缺少 '" + close + "'", l, c);
                char n = src.charAt(pos);
                if (n == close) {
                    advance();
                    return new SExpr.ListExpr(items, l, c);
                }
                if (n == ')' || n == ']') throw new LogosError("括号不匹配：多余的 '" + n + "'", line, col);
                items.add(readExpr());
            }
        }
        if (ch == ')' || ch == ']') throw new LogosError("多余的 '" + ch + "'", l, c);
        if (ch == '"') {
            advance();
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) throw new LogosError("字符串未闭合", l, c);
                char n = advance();
                if (n == '"') break;
                sb.append(n);
            }
            return new SExpr.Str(sb.toString(), l, c);
        }
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char n = src.charAt(pos);
            if (Character.isWhitespace(n) || n == '(' || n == ')' || n == '[' || n == ']' || n == ';' || n == '"') break;
            sb.append(advance());
        }
        return new SExpr.Atom(sb.toString(), l, c);
    }
}
