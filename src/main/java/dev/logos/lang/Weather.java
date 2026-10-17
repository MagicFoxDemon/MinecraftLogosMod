package dev.logos.lang;

import java.util.Locale;

/** A kind of weather, for the weather runes. Plugged into their slot like a shape. */
public enum Weather {
    /** No rain or snow, even under a storm elsewhere. */
    CLEAR("晴"),
    /** Rain; snow in a cold biome, as vanilla rain is. */
    RAIN("雨"),
    /** Rain with lightning. */
    THUNDER("雷暴"),
    /** Snow whatever the biome (area weather only: vanilla weather cannot snow everywhere). */
    SNOW("雪");

    public final String zh;

    Weather(String zh) {
        this.zh = zh;
    }

    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Weather parse(String s) {
        for (Weather v : values()) if (v.word().equals(s)) return v;
        return null;
    }
}
