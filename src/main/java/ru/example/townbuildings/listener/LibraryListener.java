package ru.example.townbuildings.listener;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.database.model.BuildingData;

import java.util.List;
import java.util.UUID;

/**
 * Library lvl 1+: сохранение опыта после смерти для резидентов города,
 * у которого есть библиотека.
 *
 * Реализация через PlayerDeathEvent.setNewTotalExp — БЕЗ БД и без
 * PlayerRespawnEvent. Опыт остаётся у клиента, восстановление после
 * респавна делает сам Minecraft.
 *
 * C3 — при холодном кэше buildings не сохраняем опыт, но инициируем
 *      async-прогрев кэша (обычно он уже прогрет через
 *      PlayerConnectionListener.onJoin, но событие смерти может прийти
 *      от игрока, чей город был добавлен позже).
 */
public final class LibraryListener implements Listener {

    private static final String LIBRARY_ID = "library";

    private final TownBuildingsPlugin plugin;

    public LibraryListener(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        if (!plugin.getConfigManager().features().libraryExp()) return;

        Player player = event.getEntity();

        // --- Towny: только резиденты с городом ---
        Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
        if (resident == null || !resident.hasTown()) return;
        Town town = resident.getTownOrNull();
        if (town == null) return;
        UUID townUuid = town.getUUID();

        // --- Кэш buildings (прогрет в CacheWarmup / PlayerConnectionListener) ---
        List<BuildingData> buildings = plugin.townService().cache().getBuildings(townUuid);
        if (buildings == null) {
            // ★ C3 — холодный кэш: не сохраняем опыт в этот раз, но греем кэш,
            // чтобы следующее событие сработало. Игрок теряет опыт крайне редко —
            // обычно прогрев уже сработал при PlayerJoinEvent.
            plugin.townService().getBuildings(townUuid)
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("[Library] warmup (" + townUuid
                                + "): " + ex.getMessage());
                        return null;
                    });
            return;
        }

        boolean hasLibrary = buildings.stream()
                .anyMatch(b -> LIBRARY_ID.equals(b.buildingId()) && b.level() >= 1);
        if (!hasLibrary) return;

        int exp = player.getTotalExperience();
        if (exp <= 0) return;

        // Ключевые две строки: никаких орбов, новый total = старый total.
        event.setDroppedExp(0);
        event.setNewTotalExp(exp);
    }
}