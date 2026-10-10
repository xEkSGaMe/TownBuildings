package ru.example.townbuildings.service;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Снимок проверки ресурсов для одного уровня здания.
 * Используется и для GUI-lore, и для логики прокачки.
 */
public record ResourceCheck(
        @NotNull CoinCheck coins,
        @NotNull Map<Material, ItemCheck> items
) {

    public record CoinCheck(long need, long have) {
        public boolean ok() { return have >= need; }
        public long missing() { return Math.max(0, need - have); }
    }

    public record ItemCheck(int need, int have) {
        public boolean ok() { return have >= need; }
        public int missing() { return Math.max(0, need - have); }
    }

    public boolean allOk() {
        if (!coins.ok()) return false;
        for (ItemCheck ic : items.values()) if (!ic.ok()) return false;
        return true;
    }

    public static @NotNull ResourceCheck trivial() {
        return new ResourceCheck(new CoinCheck(0, 0), Map.of());
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** Удобный конструктор для случая «нужно/есть» без пересчёта. */
    public static final class Builder {
        private long coinsNeed;
        private long coinsHave;
        private final Map<Material, ItemCheck> items = new LinkedHashMap<>();

        public Builder coins(long need, long have) {
            this.coinsNeed = need;
            this.coinsHave = have;
            return this;
        }

        public Builder item(@NotNull Material mat, int need, int have) {
            items.put(mat, new ItemCheck(need, have));
            return this;
        }

        public @NotNull ResourceCheck build() {
            return new ResourceCheck(new CoinCheck(coinsNeed, coinsHave), Map.copyOf(items));
        }
    }
}