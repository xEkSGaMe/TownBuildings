package ru.example.cloudcore.service;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.config.HolyScrollType;
import ru.example.cloudcore.config.HolySiteConfig;
import ru.example.cloudcore.config.HolySiteRegistry;
import ru.example.cloudcore.gui.util.ItemBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Свиток, необходимый для постройки Священного места.
 *
 * Поддерживаются два типа:
 *   • MYSTERY — «Таинственный Свиток» (FLOW_BANNER_PATTERN);
 *   • ANCIENT — «Древний Свиток» (MOJANG_BANNER_PATTERN).
 *
 * В NBT (PersistentDataContainer) хранятся site-id и подпись. Подпись
 * считается от site-id, типа свитка и секрета из holy-sites.yml, поэтому
 * свиток нельзя подделать, просто переименовав предмет.
 */
public final class HolyScrollItem {

    /**
     * Разобранное содержимое свитка.
     */
    public record ScrollRef(@NotNull String siteId, @NotNull HolyScrollType type) {}

    private final CloudCorePlugin plugin;
    private final NamespacedKey keySite;
    private final NamespacedKey keySignature;

    public HolyScrollItem(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
        this.keySite = new NamespacedKey(plugin, "holy_site_id");
        this.keySignature = new NamespacedKey(plugin, "holy_site_signature");
    }

    public @NotNull ItemStack create(@NotNull HolySiteConfig site, int amount) {
        return create(site, site.scrollType(), amount);
    }

    public @NotNull ItemStack create(@NotNull HolySiteConfig site,
                                     @NotNull HolyScrollType type,
                                     int amount) {
        var mc = plugin.getMessagesConfig();

        Map<String, String> ph = Map.of(
                "site", site.displayName(),
                "icon-color", site.color());

        ItemStack item = new ItemBuilder(type.material())
                .name(mc.getRaw(nameKey(type), ph))
                .lore(buildLore(site, type, ph))
                .glow(true)
                .amount(amount)
                .build();

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keySite, PersistentDataType.STRING, site.id());
        pdc.set(keySignature, PersistentDataType.STRING, sign(site.id(), type));
        item.setItemMeta(meta);
        return item;
    }

    private @NotNull List<Component> buildLore(@NotNull HolySiteConfig site,
                                               @NotNull HolyScrollType type,
                                               @NotNull Map<String, String> ph) {
        var mc = plugin.getMessagesConfig();
        List<Component> lore = new ArrayList<>(4);
        lore.add(mc.getRaw(loreKey(type, "site"), ph));
        lore.add(mc.getRaw(loreKey(type, "accent"), ph));
        lore.add(Component.empty());
        lore.add(mc.getRaw(loreKey(type, "hint"), ph));
        return lore;
    }

    /** Ключ названия свитка в messages.yml. */
    public @NotNull String nameKey(@NotNull HolyScrollType type) {
        return type == HolyScrollType.ANCIENT
                ? "holy-sites.scroll.ancient.name"
                : "holy-sites.scroll.name";
    }

    private static @NotNull String loreKey(@NotNull HolyScrollType type, @NotNull String suffix) {
        return type == HolyScrollType.ANCIENT
                ? "holy-sites.scroll.ancient.lore." + suffix
                : "holy-sites.scroll.lore." + suffix;
    }

    /** Валиден ли предмет как свиток конкретного места. */
    public boolean isFor(@Nullable ItemStack stack, @NotNull HolySiteConfig site) {
        ScrollRef ref = read(stack);
        return ref != null
                && ref.siteId().equals(site.id())
                && ref.type() == site.scrollType();
    }

    /** site-id и тип из валидного свитка либо null. */
    public @Nullable ScrollRef read(@Nullable ItemStack stack) {
        if (stack == null) return null;
        HolyScrollType type = typeFor(stack);
        if (type == null) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String siteId = pdc.get(keySite, PersistentDataType.STRING);
        String sig    = pdc.get(keySignature, PersistentDataType.STRING);
        if (siteId == null || sig == null) return null;
        if (!constantTimeEquals(sig, sign(siteId, type))) return null;
        return new ScrollRef(siteId, type);
    }

    public int count(@NotNull Player player, @NotNull HolySiteConfig site) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isFor(stack, site)) total += stack.getAmount();
        }
        return total;
    }

    /** Забирает ровно один свиток нужного места и типа. @return true, если найден. */
    public boolean takeOne(@NotNull Player player, @NotNull HolySiteConfig site) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (!isFor(stack, site)) continue;
            int amount = stack.getAmount();
            if (amount <= 1) {
                player.getInventory().setItem(i, null);
            } else {
                stack.setAmount(amount - 1);
                player.getInventory().setItem(i, stack);
            }
            return true;
        }
        return false;
    }

    /** Выдаёт свитки игроку; не поместившиеся — выбрасывает под ноги. */
    public void give(@NotNull Player player, @NotNull HolySiteConfig site, int amount) {
        if (amount <= 0) return;
        for (int i = 0; i < amount; i++) {
            // Баннерные узоры стакаются только по 1 — каждый свиток
            // кладём отдельным addItem, иначе инвентарь «потеряет» остаток.
            ItemStack stack = create(site, 1);
            var leftovers = player.getInventory().addItem(stack);
            if (!leftovers.isEmpty()) {
                for (ItemStack drop : leftovers.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
        }
    }

    private static @Nullable HolyScrollType typeFor(@NotNull ItemStack stack) {
        for (HolyScrollType type : HolyScrollType.values()) {
            if (type.material() == stack.getType()) return type;
        }
        return null;
    }

    private @NotNull String sign(@NotNull String siteId, @NotNull HolyScrollType type) {
        // Для старых «Таинственных» свитков сохраняем прежнюю подпись,
        // чтобы выданные ранее предметы не стали невалидными.
        String payload = type == HolyScrollType.MYSTERY
                ? siteId
                : siteId + "|" + type.key();
        return sign(payload);
    }

    private @NotNull String sign(@NotNull String payload) {
        HolySiteRegistry registry = plugin.holySiteRegistry();
        String secret = registry != null ? registry.scrollSecret() : "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((payload + "|" + secret)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 обязан быть в любой JVM; если нет — свитки не выдаём.
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static boolean constantTimeEquals(@NotNull String a, @NotNull String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
