package ru.example.cloudcore.listener;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.database.model.BuildingData;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * headquarters lvl 2+: стакинг зелий до 3 (lvl 2) или 4 (lvl 3) в слоте.
 *
 * <p>Ванильные зелья имеют {@code max_stack_size = 1}, поэтому одного
 * {@link ItemStack#setAmount(int)} недостаточно: клиент и ванильные
 * контейнеры нормализуют такой стак. Здесь мы выставляем data-component
 * {@link ItemMeta#setMaxStackSize(Integer)} на объединённый стак, иначе
 * фича визуально «не работает вовсе».</p>
 *
 * Логика:
 *  - ЛКМ зельем по такому же зелью → объединяем до maxStack, остаток в cursor.
 *  - ПКМ по стаку зелья (выпить) → если у стака уже есть max_stack_size > 1,
 *    ваниль корректно уменьшает количество на 1. Ручной перехват остаётся
 *    только для legacy-стаков без компонента (max=1, amount>1), иначе MC
 *    удалил бы весь стак.
 *  - Периодическая нормализация: если HQ понизили, стаки больше лимита
 *    аккуратно разбиваются, а не остаются эксплойтом.
 *
 * Работает только в инвентаре игрока (InventoryType.PLAYER).
 */
public final class HeadquartersStackListener implements Listener {

    private static final String BUILDING_ID = "headquarters";
    private static final long NORMALIZE_PERIOD_TICKS = 20L * 60L;

    private final CloudCorePlugin plugin;

    /** Периодическая проверка стаков после понижения HQ. */
    private ScheduledTask normalizeTask;

    public HeadquartersStackListener(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
        this.normalizeTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, t -> normalizeAll(), NORMALIZE_PERIOD_TICKS, NORMALIZE_PERIOD_TICKS);
    }

    public void stop() {
        if (normalizeTask != null) {
            normalizeTask.cancel();
            normalizeTask = null;
        }
    }

    // ============================================================
    //   Стакинг: ЛКМ зельем по зелью
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!plugin.getConfigManager().features().headquartersStacking()) return;

        // Только обычный левый клик без шифта.
        if (event.getClick() != ClickType.LEFT) return;

        // Только инвентарь игрока (не сундуки/эндер-сундуки).
        var clicked = event.getClickedInventory();
        if (clicked == null || clicked.getType() != InventoryType.PLAYER) return;

        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();

        if (!isSamePotion(cursor, current)) return;

        int maxStack = resolveMaxPotionStack(player);
        if (maxStack <= 1) return;

        event.setCancelled(true);

        int total = cursor.getAmount() + current.getAmount();
        if (total <= 0) return;

        int newInSlot = Math.min(total, maxStack);
        int remainingOnCursor = total - newInSlot;

        ItemStack updated = withMaxStack(current, maxStack);
        updated.setAmount(newInSlot);
        event.setCurrentItem(updated);

        if (remainingOnCursor > 0) {
            ItemStack cursorCopy = withMaxStack(cursor, maxStack);
            cursorCopy.setAmount(remainingOnCursor);
            event.getView().setCursor(cursorCopy);
        } else {
            event.getView().setCursor(null);
        }

        player.updateInventory();
    }

    // ============================================================
    //   Защита от потери стака при выпивании (legacy)
    // ============================================================

    /**
     * Для стаков с {@code max_stack_size > 1} ваниль сама делает shrink(1),
     * поэтому событие не трогаем. Ручной путь нужен только для старого
     * стака, у которого max=1, а amount>1: MC удалил бы весь стак.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(@NotNull PlayerItemConsumeEvent event) {
        if (!plugin.getConfigManager().features().headquartersStacking()) return;

        ItemStack item = event.getItem();
        if (item.getType() != Material.POTION) return;
        if (item.getAmount() <= 1) return;      // обычный кейс — MC справится сам
        if (item.getMaxStackSize() > 1) return; // компонент уже стоит — ваниль уменьшит на 1

        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();

        // 2.3 — защита от race: подменили предмет между ванильным стартом
        //        потребления и нашим событием.
        ItemStack held = getHandItem(player, hand);
        if (held == null || !held.isSimilar(item) || held.getAmount() != item.getAmount()) {
            return;
        }

        // 2.3 — собираем все эффекты: base + custom, не взаимоисключающе.
        List<PotionEffect> effects = new ArrayList<>(4);
        if (item.getItemMeta() instanceof PotionMeta meta) {
            if (meta.getBasePotionType() != null) {
                effects.addAll(meta.getBasePotionType().getPotionEffects());
            }
            effects.addAll(meta.getCustomEffects());
        }

        // ★ A2 — всегда перехватываем.
        event.setCancelled(true);

        for (PotionEffect eff : effects) {
            player.addPotionEffect(eff);
        }

        // Снимаем ровно 1 шт из того слота, из которого пили.
        int remaining = held.getAmount() - 1;
        if (remaining <= 0) {
            setHandItem(player, hand, null);
        } else {
            ItemStack decremented = held.clone();
            decremented.setAmount(remaining);
            setHandItem(player, hand, decremented);
        }

        player.getWorld().playSound(player.getLocation(),
                org.bukkit.Sound.ENTITY_GENERIC_DRINK, 0.5f, 1.0f);

        player.updateInventory();
    }

    private static @Nullable ItemStack getHandItem(@NotNull Player player,
                                                   @NotNull EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    private static void setHandItem(@NotNull Player player,
                                    @NotNull EquipmentSlot hand,
                                    @Nullable ItemStack item) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        } else {
            player.getInventory().setItemInMainHand(item);
        }
    }

    // ============================================================
    //   Нормализация после понижения HQ
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        if (!plugin.getConfigManager().features().headquartersStacking()) return;
        Player player = event.getPlayer();
        // Инвентарь может быть ещё не готов в момент join — на следующем тике.
        Bukkit.getScheduler().runTask(plugin, () -> normalize(player));
    }

    private void normalizeAll() {
        if (!plugin.getConfigManager().features().headquartersStacking()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            normalize(player);
        }
    }

    /**
     * Приводит стаки зелий к текущему лимиту HQ. Если лимит неизвестен
     * (кэш города холодный), ничего не трогаем — иначе можно ошибочно
     * «распаковать» нормальные стаки.
     */
    private void normalize(@NotNull Player player) {
        int allowed = resolveMaxPotionStack(player);
        if (allowed < 1) return;

        ItemStack[] contents = player.getInventory().getContents();
        boolean changed = false;

        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir() || !isPotion(stack.getType())) continue;

            int amount = stack.getAmount();
            int currentMax = stack.getMaxStackSize();

            if (currentMax == allowed && amount <= allowed) continue;

            // Компонент ниже лимита, но количество влезает — просто поднимаем
            // max_stack_size, чтобы ваниль тоже могла объединять такие зелья.
            if (currentMax < allowed && amount <= allowed) {
                player.getInventory().setItem(i, withMaxStack(stack, allowed));
                changed = true;
                continue;
            }

            int keep = Math.min(amount, allowed);
            ItemStack normalized = withMaxStack(stack, allowed);
            normalized.setAmount(keep);
            player.getInventory().setItem(i, normalized);

            int excess = amount - keep;
            while (excess > 0) {
                int chunk = Math.min(excess, allowed);
                ItemStack extra = withMaxStack(stack, allowed);
                extra.setAmount(chunk);
                var leftovers = player.getInventory().addItem(extra);
                for (ItemStack left : leftovers.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), left);
                }
                excess -= chunk;
            }
            changed = true;
        }

        if (changed) player.updateInventory();
    }

    /**
     * Выставляет data-component max_stack_size. Без него стак зелья
     * «разваливается» на клиенте, потому что ванильный максимум — 1.
     */
    private static @NotNull ItemStack withMaxStack(@NotNull ItemStack source, int max) {
        ItemStack copy = source.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            meta.setMaxStackSize(max);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    // ============================================================
    //   Внутреннее
    // ============================================================

    /**
     * @return 3/4 для HQ 2/3+, 1 если HQ нет, -1 если кэш города холодный
     *         (в этом случае операцию лучше пропустить).
     */
    private int resolveMaxPotionStack(@NotNull Player player) {
        Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
        if (resident == null || !resident.hasTown()) return 1;

        Town town = resident.getTownOrNull();
        if (town == null) return 1;
        UUID townUuid = town.getUUID();

        List<BuildingData> buildings = plugin.townService().cache().getBuildings(townUuid);
        if (buildings == null) return -1;

        int level = buildings.stream()
                .filter(b -> BUILDING_ID.equals(b.buildingId()))
                .mapToInt(BuildingData::level)
                .findFirst()
                .orElse(0);

        if (level >= 3) return 4;
        if (level == 2) return 3;
        return 1;
    }

    /**
     * Сравниваем содержимое зелья, игнорируя max_stack_size: иначе стак
     * с компонентом 3 никогда не «увидит» обычное зелье с компонентом 1.
     */
    private boolean isSamePotion(@Nullable ItemStack a, @Nullable ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getType().isAir() || b.getType().isAir()) return false;
        if (!isPotion(a.getType()) || !isPotion(b.getType())) return false;
        if (a.getType() != b.getType()) return false;

        if (!(a.getItemMeta() instanceof PotionMeta ma)
                || !(b.getItemMeta() instanceof PotionMeta mb)) return false;

        return ma.getBasePotionType() == mb.getBasePotionType()
                && Objects.equals(ma.getColor(), mb.getColor())
                && ma.getCustomEffects().equals(mb.getCustomEffects());
    }

    /** POTION / SPLASH_POTION / LINGERING_POTION. */
    private boolean isPotion(@NotNull Material mat) {
        return mat == Material.POTION
                || mat == Material.SPLASH_POTION
                || mat == Material.LINGERING_POTION;
    }
}
