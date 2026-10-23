package ru.example.cloudcore.institutes.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public enum GovernmentForm {
    MONARCHY("monarchy"),
    REPUBLIC("republic"),
    DICTATORSHIP("dictatorship"),
    OLIGARCHY("oligarchy"),
    THEOCRACY("theocracy");

    private final String key;
    GovernmentForm(String key) { this.key = key; }
    public @NotNull String key() { return key; }

    public static @Nullable GovernmentForm byKey(@Nullable String key) {
        if (key == null || key.isBlank()) return null;
        String normalized = key.toLowerCase(Locale.ROOT);
        for (GovernmentForm f : values()) if (f.key.equals(normalized)) return f;
        return null;
    }
}