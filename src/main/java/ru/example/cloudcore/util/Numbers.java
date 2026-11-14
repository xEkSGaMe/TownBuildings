package ru.example.cloudcore.util;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Форматирование чисел для GUI-лоров.
 *
 * Locale.US — гарантирует запятую как разделитель групп:
 *   1_000_000  → "1,000,000"
 *   1_000      → "1,000"
 *   999        → "999"
 *   -12_345    → "-12,345"
 */
public final class Numbers {

    private Numbers() {}

    public static @NotNull String withSeparators(long value) {
        if (value > -1000L && value < 1000L) {
            return Long.toString(value);
        }
        return String.format(Locale.US, "%,d", value);
    }

    public static @NotNull String withSeparators(int value) {
        return withSeparators((long) value);
    }
}