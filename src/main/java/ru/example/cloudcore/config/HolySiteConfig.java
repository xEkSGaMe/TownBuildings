package ru.example.cloudcore.config;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.institutes.data.Religion;

import java.util.List;

/**
 * Одно Священное место.
 *
 * Отличие от {@link WonderConfig}: вместо цены в монетах/ресурсах требуется
 * особый предмет — Таинственный Свиток (см. HolyScrollItem), а для постройки
 * в городе должна быть выбрана конкретная религия.
 */
public record HolySiteConfig(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull WonderRarity rarity,
        @NotNull Material icon,
        @NotNull String symbol,
        @NotNull String color,
        @NotNull String gradFrom,
        @NotNull String gradTo,
        int guiSlot,
        @NotNull Religion religion,
        @NotNull BuildingRewards rewards,
        @NotNull List<TimedItem> timedItems,
        @NotNull HolySiteEffects effects,
        @NotNull List<String> lore,
        @NotNull List<String> history
) {}
