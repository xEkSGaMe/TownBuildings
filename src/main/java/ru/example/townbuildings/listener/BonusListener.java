package ru.example.townbuildings.listener;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingRewards;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Применяет бонусы к добыче руды, урожаю, дропу с мобов и опыту.
 * Множители берутся из BonusService (кэш суммарных бонусов города).
 *
 * 8.2 — DUP-7: не даём бонус за блоки, поставленные игроками, и за мобов
 * из спавнера.
 */
public final class BonusListener implements Listener {

    private final TownBuildingsPlugin plugin;

    public BonusListener(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //   Опыт
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExpChange(@NotNull PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        UUID townUuid = resolveTownUuid(player);
        if (townUuid == null) return;

        BuildingRewards rewards = plugin.bonusService().getRewards(townUuid);
        if (rewards.expBonusPercent() <= 0) return;

        int original = event.getAmount();
        int bonus = (int) Math.round(original * rewards.expBonusPercent() / 100.0);
        if (bonus > 0) {
            event.setAmount(original + bonus);
        }
    }

    // ============================================================
    //   Добыча руды и урожай (BlockBreakEvent)
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        // 8.2.1 — DUP-7: блок поставлен игроком — бонус не даём.
        if (plugin.blockPlaceTracker().consume(event.getBlock())) return;

        // Дешёвый фильтр по Material — до обращений к Towny.
        if (!isOreOrCrop(event.getBlock().getType())) return;

        Player player = event.getPlayer();
        UUID townUuid = resolveTownUuid(player);
        if (townUuid == null) return;

        BuildingRewards rewards = plugin.bonusService().getRewards(townUuid);
        if (rewards.harvestBonusPercent() <= 0) return;

        double bonusMultiplier = rewards.harvestBonusPercent() / 100.0;
        // getDrops возвращает только предметы (Collection<ItemStack>).
        // Опыт учитывается отдельно через PlayerExpChangeEvent, дублирования не будет.
        for (ItemStack drop : event.getBlock().getDrops(
                player.getInventory().getItemInMainHand(), player)) {
            if (drop == null || drop.getType().isAir()) continue;
            int extra = (int) Math.round(drop.getAmount() * bonusMultiplier);
            if (extra <= 0) continue;

            ItemStack extraStack = drop.clone();
            extraStack.setAmount(extra);
            event.getBlock().getWorld().dropItemNaturally(
                    event.getBlock().getLocation(), extraStack);
        }
    }

    // ============================================================
    //   8.2.2 — трекинг поставленных блоков
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        plugin.blockPlaceTracker().remember(event.getBlockPlaced());
    }

    // ============================================================
    //   Дроп с мобов
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(@NotNull EntityDeathEvent event) {
        // 8.2.3 — DUP-7: мобы из спавнера — не считаем за реальную добычу.
        // Сравнение по имени: SpawnReason переезжал между пакетами в разных
        // версиях Paper (CreatureSpawnEvent.SpawnReason → org.bukkit.entity.SpawnReason),
        // поэтому не привязываемся к конкретному import.
        var spawnReason = event.getEntity().getEntitySpawnReason();
        if (spawnReason != null && "SPAWNER".equals(spawnReason.name())) {
            return;
        }

        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        UUID townUuid = resolveTownUuid(killer);
        if (townUuid == null) return;

        BuildingRewards rewards = plugin.bonusService().getRewards(townUuid);
        if (rewards.harvestBonusPercent() <= 0) return;

        double bonusMultiplier = rewards.harvestBonusPercent() / 100.0;
        List<ItemStack> drops = event.getDrops();
        List<ItemStack> extraDrops = new ArrayList<>();

        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir()) continue;
            int extra = (int) Math.round(drop.getAmount() * bonusMultiplier);
            if (extra <= 0) continue;
            ItemStack extraStack = drop.clone();
            extraStack.setAmount(extra);
            extraDrops.add(extraStack);
        }
        drops.addAll(extraDrops);
    }

    // ============================================================
    //   Хелперы
    // ============================================================

    private UUID resolveTownUuid(@NotNull Player player) {
        Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
        if (resident == null || !resident.hasTown()) return null;
        Town town = resident.getTownOrNull();
        return town != null ? town.getUUID() : null;
    }

    private boolean isOreOrCrop(@NotNull org.bukkit.Material material) {
        String name = material.name();
        return name.endsWith("_ORE")
                || name.equals("ANCIENT_DEBRIS")
                || name.equals("WHEAT")
                || name.equals("CARROTS")
                || name.equals("POTATOES")
                || name.equals("BEETROOTS")
                || name.equals("NETHER_WART")
                || name.equals("COCOA")
                || name.equals("MELON")
                || name.equals("PUMPKIN")
                || name.equals("SUGAR_CANE")
                || name.equals("BAMBOO")
                || name.equals("SWEET_BERRY_BUSH")
                || name.equals("GLOW_BERRIES")
                || name.equals("KELP")
                || name.equals("SEA_PICKLE");
    }
}