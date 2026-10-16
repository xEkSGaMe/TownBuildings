package ru.example.townbuildings.listener;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.database.model.BuildingData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * headquarters lvl 2+: стакинг зелий до 3 (lvl 2) или 4 (lvl 3) в слоте.
 *
 * Логика:
 *  - ЛКМ зельем по такому же зелью → объединяем до maxStack, остаток в cursor.
 *  - ПКМ по стаку зелья (выпить) → предотвращаем потерю всего стака:
 *    отменяем ванильную обработку, вручную снимаем 1 шт и накладываем эффекты.
 *
 * Работает только в инвентаре игрока (InventoryType.PLAYER).
 * Правый/shift/drag/swap — стандарт.
 *
 * 2.3 — фиксы onConsume:
 *   1) base и custom эффекты теперь не взаимоисключающие (был else if);
 *   2) проверяем held.isSimilar(item) перед декрементом — иначе теряли зелье,
 *      если предмет в руке подменили;
 *   3) ★ A2 — даже при пустых эффектах (water bottle / thick / mundane)
 *      перехватываем событие и снимаем 1 шт вручную: ванилла при
 *      maxStackSize=1 удалила бы весь стак, а HQ разрешает стек 3–4.
 */
public final class HeadquartersStackListener implements Listener {

    private static final String BUILDING_ID = "headquarters";

    private final TownBuildingsPlugin plugin;

    public HeadquartersStackListener(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
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

        ItemStack updated = current.clone();
        updated.setAmount(newInSlot);
        event.setCurrentItem(updated);

        if (remainingOnCursor > 0) {
            ItemStack cursorCopy = cursor.clone();
            cursorCopy.setAmount(remainingOnCursor);
            event.getView().setCursor(cursorCopy);
        } else {
            event.getView().setCursor(null);
        }

        player.updateInventory();
    }

    // ============================================================
    //   17.10 / 2.3 / A2 — защита от потери стака при выпивании
    // ============================================================

    /**
     * При выпивании POTION из стака >1 MC удаляет весь стак
     * (maxStackSize=1). Перехватываем и снимаем ровно 1 шт.
     *
     * SPLASH_POTION / LINGERING_POTION (броски) MC обрабатывает корректно
     * — amount уменьшается на 1. Их не трогаем.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(@NotNull PlayerItemConsumeEvent event) {
        if (!plugin.getConfigManager().features().headquartersStacking()) return;

        ItemStack item = event.getItem();
        if (item.getType() != Material.POTION) return;
        if (item.getAmount() <= 1) return;  // обычный кейс — MC справится сам

        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();

        // 2.3 — защита от race: подменили предмет между ванильным стартом
        //        потребления и нашим событием.
        ItemStack held = getHandItem(player, hand);
        if (held == null || !held.isSimilar(item) || held.getAmount() != item.getAmount()) {
            // Не наш кейс — отдаём ванильной логике (она корректно обработает).
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

        // ★ A2 — всегда перехватываем. Раньше при effects.isEmpty() мы возвращались
        // в ванильную обработку, а она при maxStackSize=1 удаляет ВЕСЬ стак (HQ
        // разрешает стек 3–4) → игрок терял 3 зелья за одно выпитое. Пустые
        // эффекты (water bottle / thick / mundane) — просто не начисляем, но
        // decrement на 1 шт делаем вручную.
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
    //   Внутреннее
    // ============================================================

    private int resolveMaxPotionStack(@NotNull Player player) {
        Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
        if (resident == null || !resident.hasTown()) return 1;

        Town town = resident.getTownOrNull();
        if (town == null) return 1;
        UUID townUuid = town.getUUID();

        List<BuildingData> buildings = plugin.townService().cache().getBuildings(townUuid);
        if (buildings == null) return 1;

        int level = buildings.stream()
                .filter(b -> BUILDING_ID.equals(b.buildingId()))
                .mapToInt(BuildingData::level)
                .findFirst()
                .orElse(0);

        if (level >= 3) return 4;
        if (level == 2) return 3;
        return 1;
    }

    private boolean isSamePotion(@Nullable ItemStack a, @Nullable ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getType().isAir() || b.getType().isAir()) return false;
        if (!isPotion(a.getType()) || !isPotion(b.getType())) return false;
        return a.isSimilar(b);
    }

    private boolean isPotion(@NotNull Material mat) {
        return mat == Material.POTION
                || mat == Material.SPLASH_POTION
                || mat == Material.LINGERING_POTION;
    }
}