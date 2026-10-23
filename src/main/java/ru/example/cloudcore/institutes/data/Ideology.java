package ru.example.cloudcore.institutes.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public enum Ideology {
    SOCIALISM("socialism"),
    COMMUNISM("communism"),
    EXPANSIONISM("expansionism"),
    LIBERALISM("liberalism"),
    CONSERVATISM("conservatism");

    private final String key;
    Ideology(String key) { this.key = key; }
    public @NotNull String key() { return key; }

    public static @Nullable Ideology byKey(@Nullable String key) {
        if (key == null || key.isBlank()) return null;
        String normalized = key.toLowerCase(Locale.ROOT);
        for (Ideology i : values()) if (i.key.equals(normalized)) return i;
        return null;
    }
}