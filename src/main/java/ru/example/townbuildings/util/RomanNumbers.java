package ru.example.townbuildings.util;

import org.jetbrains.annotations.NotNull;

/** Перевод арабских чисел в римские (1..3999). */
public final class RomanNumbers {

    private static final int[] VALUES  = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] ROMAN = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};

    private RomanNumbers() {}

    public static @NotNull String toRoman(int n) {
        if (n <= 0) return "0";
        if (n > 3999) return String.valueOf(n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < VALUES.length; i++) {
            while (n >= VALUES[i]) {
                sb.append(ROMAN[i]);
                n -= VALUES[i];
            }
        }
        return sb.toString();
    }
}