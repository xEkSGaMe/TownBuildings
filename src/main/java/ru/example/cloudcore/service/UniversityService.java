package ru.example.cloudcore.service;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.database.model.BuildingData;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.List;
import java.util.UUID;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * Выдаёт/снимает пермишены Essentials всем горожанам, у города которых
 * есть university нужного уровня.
 *
 * Уровни:
 *   2 → /craft (essentials.workbench)
 *   3 → /anvil (essentials.anvil)
 *   4 → /ec    (essentials.enderchest)
 *   5 → /fix   (essentials.repair)
 *
 * Вызовы:
 *   - TownAddResidentEvent  → grant(player, town)
 *   - TownRemoveResidentEvent → revoke(player)
 *   - PlayerJoinEvent       → grant(player, town) (на случай рестарта)
 *   - BuildingService.finishUpgrade → syncTown(town)
 *   - AdminBuildingHandler.applyLevel → syncTown(town)
 *   - После CacheWarmup    → syncAll()
 */
public final class UniversityService {

    private static final String UNIVERSITY_ID = "university";

    private final CloudCorePlugin plugin;

    public UniversityService(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //   Публичное API
    // ============================================================

    /** Выдать пермишены игроку по текущему уровню university в его городе. */
    public void grant(@NotNull Player player, @NotNull Town town) {
        if (!plugin.getConfigManager().features().universityPerms()) return;
        int level = resolveLevel(town);
        plugin.permissionService().grantUniversityPermissions(player.getUniqueId(), level);
    }

    /** Снять пермишены университета у игрока. */
    public void revoke(@NotNull Player player) {
        plugin.permissionService().revokeUniversityPermissions(player.getUniqueId());
    }

    /** Синхронизировать всех онлайн-резидентов города (после апгрейда). */
    public void syncTown(@NotNull Town town) {
        if (!plugin.getConfigManager().features().universityPerms()) return;
        int level = resolveLevel(town);
        for (Resident r : town.getResidents()) {
            Player p = r.getPlayer();
            if (p != null && p.isOnline()) {
                plugin.permissionService().grantUniversityPermissions(p.getUniqueId(), level);
            }
        }
    }

    /** Синхронизировать все города (при старте, после CacheWarmup). */
    public void syncAll() {
        if (!plugin.getConfigManager().features().universityPerms()) return;
        for (Town town : TownyAPI.getInstance().getTowns()) {
            syncTown(town);
        }
    }

    // ============================================================
    //   Внутреннее
    // ============================================================

    /**
     * Уровень university в городе. Читает из кэша buildings.
     * При холодном кэше возвращает 0 (не выдаём пермишены до прогрева).
     */
    private int resolveLevel(@NotNull Town town) {
        UUID townUuid = town.getUUID();
        List<BuildingData> buildings = plugin.townService().cache().getBuildings(townUuid);
        if (buildings == null) return 0;

        return buildings.stream()
                .filter(b -> UNIVERSITY_ID.equals(b.buildingId()))
                .mapToInt(BuildingData::level)
                .findFirst()
                .orElse(0);
    }
}