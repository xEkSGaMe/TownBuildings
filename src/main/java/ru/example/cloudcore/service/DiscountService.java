package ru.example.cloudcore.service;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.config.ConfigManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Скидки на апгрейды/постройки через LuckPerms-пермишены.
 * Скидка считается по игроку-инициатору (мэру/ассистенту, который кликнул).
 * Применяется МАКСИМУМ процентов отдельно по монетам и ресурсам.
 */
public final class DiscountService {

    public record Applied(long coins,
                          @NotNull Map<Material, Integer> items,
                          double coinsPercent,
                          double itemsPercent) {
        public boolean hasDiscount() { return coinsPercent > 0 || itemsPercent > 0; }
    }

    private final ConfigManager config;

    public DiscountService(@NotNull ConfigManager config) {
        this.config = config;
    }

    public @NotNull Applied apply(@Nullable Player player,
                                  long baseCoins,
                                  @NotNull Map<Material, Integer> baseItems) {
        if (player == null || !player.isOnline() || !config.discounts().enabled()) {
            return new Applied(baseCoins, baseItems, 0, 0);
        }

        double bestCoinsPercent = 0, bestItemsPercent = 0;
        for (ConfigManager.DiscountTier tier : config.discounts().tiers()) {
            if (!player.hasPermission(tier.permission())) continue;
            if (tier.coinsPercent() > bestCoinsPercent) bestCoinsPercent = tier.coinsPercent();
            if (tier.itemsPercent() > bestItemsPercent) bestItemsPercent = tier.itemsPercent();
        }
        if (bestCoinsPercent <= 0 && bestItemsPercent <= 0) {
            return new Applied(baseCoins, baseItems, 0, 0);
        }

        long newCoins = baseCoins;
        if (bestCoinsPercent > 0) {
            long discount = Math.round(baseCoins * bestCoinsPercent / 100.0);
            newCoins = Math.max(0, baseCoins - discount);
        }

        Map<Material, Integer> newItems = new HashMap<>(baseItems.size());
        for (Map.Entry<Material, Integer> e : baseItems.entrySet()) {
            int amount = e.getValue();
            if (bestItemsPercent > 0) {
                int discount = (int) Math.round(amount * bestItemsPercent / 100.0);
                amount = Math.max(0, amount - discount);
            }
            if (amount > 0) newItems.put(e.getKey(), amount);
        }
        return new Applied(newCoins, Map.copyOf(newItems), bestCoinsPercent, bestItemsPercent);
    }
}