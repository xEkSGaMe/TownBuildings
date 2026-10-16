package ru.example.townbuildings.listener;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.database.model.BuildingData;

import java.util.List;
import java.util.UUID;

/**
 * training_camp lvl 6: TNT после смерти.
 *
 * Особенности:
 *  - спавнится в точке смерти;
 *  - не ломает блоки (metadata tb_no_blocks + EntityExplodeEvent.clear);
 *  - не поджигает (setIsIncendiary(false));
 *  - обычный урон по энтитям (yield дефолтный);
 *  - НЕ спавнится, если причина смерти — взрыв (anti-recursion).
 *
 * 2.2 — убран setYield(0f).
 * C3  — при холодном кэше buildings греем его async для будущих событий.
 */
public final class TrainingCampListener implements Listener {

    private static final String BUILDING_ID = "training_camp";
    private static final int REQUIRED_LEVEL = 6;
    private static final String META_NO_BLOCKS = "tb_no_blocks";

    private final TownBuildingsPlugin plugin;

    public TrainingCampListener(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //   Spawn on death
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        if (!plugin.getConfigManager().features().trainingCampTnt()) return;

        Player player = event.getEntity();

        // Anti-recursion: не спавним, если умер от взрыва.
        EntityDamageEvent lastDmg = player.getLastDamageCause();
        if (lastDmg != null) {
            EntityDamageEvent.DamageCause cause = lastDmg.getCause();
            if (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                    || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
                return;
            }
        }

        // Towny: только резиденты с городом.
        Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
        if (resident == null || !resident.hasTown()) return;
        Town town = resident.getTownOrNull();
        if (town == null) return;
        UUID townUuid = town.getUUID();

        // Кэш buildings (прогрет в CacheWarmup / PlayerConnectionListener).
        List<BuildingData> buildings = plugin.townService().cache().getBuildings(townUuid);
        if (buildings == null) {
            // ★ C3 — холодный кэш: не спавним TNT в этот раз, но греем
            // кэш, чтобы следующее событие сработало.
            plugin.townService().getBuildings(townUuid)
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("[TrainingCamp] warmup (" + townUuid
                                + "): " + ex.getMessage());
                        return null;
                    });
            return;
        }

        boolean hasCamp6 = buildings.stream()
                .anyMatch(b -> BUILDING_ID.equals(b.buildingId())
                        && b.level() >= REQUIRED_LEVEL);
        if (!hasCamp6) return;

        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        int fuseTicks = plugin.getConfigManager().trainingCamp().fuseTicks();

        world.spawn(loc, TNTPrimed.class, tnt -> {
            tnt.setFuseTicks(fuseTicks);
            tnt.setIsIncendiary(false);
            tnt.setMetadata(META_NO_BLOCKS, new FixedMetadataValue(plugin, true));
        });
    }

    // ============================================================
    //   Защита от разрушения блоков
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(@NotNull EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;
        if (!tnt.hasMetadata(META_NO_BLOCKS)) return;

        // Никаких разрушений. Урон по энтитям — стандартный (Event не трогает).
        event.blockList().clear();
    }
}