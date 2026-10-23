package ru.example.cloudcore.institutes.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public enum Religion {
    ORTHODOX("orthodox"),
    CATHOLIC("catholic"),
    PROTESTANT("protestant"),
    ISLAM("islam"),
    HINDUISM("hinduism"),
    BUDDHISM("buddhism"),
    JUDAISM("judaism"),
    CONFUCIANISM("confucianism");

    private final String key;
    Religion(String key) { this.key = key; }
    public @NotNull String key() { return key; }

    public static @Nullable Religion byKey(@Nullable String key) {
        if (key == null || key.isBlank()) return null;
        String normalized = key.toLowerCase(Locale.ROOT);
        for (Religion r : values()) if (r.key.equals(normalized)) return r;
        return null;
    }
}