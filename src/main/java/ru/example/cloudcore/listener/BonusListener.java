package ru.example.cloudcore.listener;

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
import ru.example.cloudcore.config.BuildingRewards;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * Применяет бонусы к добыче руды, урожаю, дропу с мобов и опыту.
 *
 * H2 — onBlockBreak / onEntityDeath: приоритет MONITOR, а не HIGH.
 *      Раньше плагин на HIGHEST мог отменить событие (блок остался бы,
 *      а extra-дропы через dropItemNaturally уже выпали). На MONITOR
 *      мы последние и видим уже финализированный дроп.
 */
public final class BonusListener implements Listener {

    private static final long TOWN_CACHE_TTL_MS = 3_000L;

    private final CloudCorePlugin plugin;
    private final Map<UUID, CachedTown> residentTownCache = new ConcurrentHashMap<>();

    public BonusListener(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //   Опыт
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExpChange(@NotNull PlayerExpChangeEvent event) {
        if (!plugin.getConfigManager().features().bonuses()) return;    // ★ C5
        Player player = event.getPlayer();
        UUID townUuid = resolveTownUuid(player);
        if (townUuid == null) return;

        BuildingRewards rewards = plugin.bonusService().getRewards(townUuid);
        if (rewards.expBonusPercent() <= 0) return;

        int original = event.getAmount();
        if (original <= 0) return;

        int bonus = (int) Math.round(original * rewards.expBonusPercent() / 100.0);
        if (bonus <= 0) bonus = 1;

        event.setAmount(original + bonus);
    }

    // ============================================================
    //   Добыча руды и урожай (BlockBreakEvent)
    // ============================================================

    /**
     * ★ H2 — MONITOR: работает последним, отменить уже никто не может.
     * ignoreCancelled=true — если событие отменено, не дропаем.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (!plugin.getConfigManager().features().bonuses()) return;    // ★ C5
        // 8.2.1 — DUP-7: блок поставлен игроком — бонус не даём.
        if (plugin.blockPlaceTracker().consume(event.getBlock())) return;

        if (!isOreOrCrop(event.getBlock().getType())) return;

        Player player = event.getPlayer();
        UUID townUuid = resolveTownUuid(player);
        if (townUuid == null) return;

        BuildingRewards rewards = plugin.bonusService().getRewards(townUuid);
        if (rewards.harvestBonusPercent() <= 0) return;

        double bonusMultiplier = rewards.harvestBonusPercent() / 100.0;
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

    /**
     * ★ H2 — MONITOR: работает последним, отменить уже никто не может.
     * entity.getDrops() уже финализирован — добавляем extra-дропы через
     * тот же список, что и ванильные.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(@NotNull EntityDeathEvent event) {
        if (!plugin.getConfigManager().features().bonuses()) return;    // ★ C5
        if (event.getEntity() instanceof Player) return;

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
        UUID playerUuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        CachedTown cached = residentTownCache.get(playerUuid);
        if (cached != null && now < cached.expiresAt()) {
            return cached.townUuid();
        }

        Resident resident = TownyAPI.getInstance().getResident(playerUuid);
        UUID townUuid = null;
        if (resident != null && resident.hasTown()) {
            Town town = resident.getTownOrNull();
            if (town != null) townUuid = town.getUUID();
        }
        residentTownCache.put(playerUuid, new CachedTown(townUuid, now + TOWN_CACHE_TTL_MS));
        return townUuid;
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

    /** Периодическая очистка кэша resident → town. */
    public void cleanup() {
        long now = System.currentTimeMillis();
        residentTownCache.entrySet().removeIf(e -> now >= e.getValue().expiresAt());
    }

    private record CachedTown(UUID townUuid, long expiresAt) {}
}