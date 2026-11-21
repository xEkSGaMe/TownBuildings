package ru.example.cloudcore.gui.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.config.MessagesConfig;
import ru.example.cloudcore.service.ResourceCheck;
import ru.example.cloudcore.util.Numbers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Детальные сообщения о нехватке ресурсов для улучшения/постройки.
 *
 * Вместо одного общего «Недостаточно ресурсов» игрок видит в чате:
 *  • сколько именно монет не хватает (с тем же значком ⛃ и цветом, что и цена в лоре);
 *  • какие именно ресурсы и в каком количестве не хватило.
 *
 * Тексты берутся из messages.yml по префиксу секции,
 * например {@code buildings.upgrade} или {@code wonders.upgrade}.
 */
public final class ResourceShortageMessages {

    private ResourceShortageMessages() {}

    public static void send(@NotNull CloudCorePlugin plugin,
                            @NotNull Player player,
                            @NotNull String keyPrefix,
                            @Nullable ResourceCheck check) {
        MessagesConfig mc = plugin.getMessagesConfig();

        if (check == null) {
            player.sendMessage(mc.get("general.internal-error"));
            return;
        }

        boolean coinsMissing = !check.coins().ok();
        List<Map.Entry<Material, ResourceCheck.ItemCheck>> missingItems = new ArrayList<>();
        for (Map.Entry<Material, ResourceCheck.ItemCheck> e : check.items().entrySet()) {
            if (!e.getValue().ok()) missingItems.add(e);
        }
        // Крупные нехватки сверху, рядом — по названию, чтобы список был предсказуемым.
        missingItems.sort(Comparator
                .<Map.Entry<Material, ResourceCheck.ItemCheck>>comparingInt(e -> e.getValue().missing())
                .reversed()
                .thenComparing(e -> itemName(mc, e.getKey())));

        if (!coinsMissing && missingItems.isEmpty()) return;

        if (coinsMissing) {
            ResourceCheck.CoinCheck coins = check.coins();
            player.sendMessage(mc.get(keyPrefix + ".not-enough-coins", Map.of(
                    "missing", fmt(coins.missing()))));
        }

        if (!missingItems.isEmpty()) {
            player.sendMessage(mc.get(keyPrefix + ".not-enough-items"));
            for (Map.Entry<Material, ResourceCheck.ItemCheck> e : missingItems) {
                ResourceCheck.ItemCheck ic = e.getValue();
                player.sendMessage(mc.get(keyPrefix + ".not-enough-item", Map.of(
                        "item",    itemName(mc, e.getKey()),
                        "missing", fmt(ic.missing()))));
            }
        }
    }

    private static @NotNull String fmt(long value) {
        return Numbers.withSeparators(value);
    }

    private static @NotNull String itemName(@NotNull MessagesConfig mc, @NotNull Material mat) {
        String custom = mc.getRaw("gui.item-names." + mat.name());
        if (!custom.isEmpty()) return custom;
        return mat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
