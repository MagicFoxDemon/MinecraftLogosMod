package dev.logos.lang;

import net.minecraft.ChatFormatting;

/**
 * The grammatical families of runes, after the alchemical Tria Prima.
 * Glyphs: ☉ gold/sun (invocation), 🜔 salt (form), ☿ mercury (motion), ☌ conjunction (binding),
 * 🜍 sulfur (change), ♄ saturn/lead (law), ⚖ balance (measure).
 */
public enum Category {
    INVOCATION("☉", "启式", ChatFormatting.GOLD, 0xFFE8B84A),
    FORM("🜔", "盐·形", ChatFormatting.WHITE, 0xFFE6E2D3),
    MOTION("☿", "汞·流", ChatFormatting.AQUA, 0xFF6FD3E0),
    BINDING("☌", "汞·指向", ChatFormatting.DARK_AQUA, 0xFF3FA7B5),
    EFFECT("🜍", "硫·变", ChatFormatting.RED, 0xFFE0603F),
    LOGIC("♄", "律式", ChatFormatting.LIGHT_PURPLE, 0xFFB57FE0),
    VALUE("⚖", "数·量", ChatFormatting.YELLOW, 0xFFE0D25F);

    public final String glyph;
    public final String zh;
    public final ChatFormatting color;
    public final int argb;

    Category(String glyph, String zh, ChatFormatting color, int argb) {
        this.glyph = glyph;
        this.zh = zh;
        this.color = color;
        this.argb = argb;
    }

    public String label() {
        return glyph + " " + zh;
    }
}
