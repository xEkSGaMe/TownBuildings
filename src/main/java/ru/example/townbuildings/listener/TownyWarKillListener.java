package ru.example.townbuildings.listener;

import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;

/**
 * Форт lvl 1: очки за убийство во время войны удваиваются.
 *
 * Базовые значения задаются самим TownyWar: 5 за обычного игрока,
 * 10 за мэра. Мы добавляем «вторую половину»:
 *   extra = base × (multiplier - 1)
 * multiplier берётся из суммарных TownyWar-наград города убийцы
 * (Форт даёт 2.0 → extra = base).
 */
public final class TownyWarKillListener implements Listener {

    private static final int BASE_KILL_POINTS       = 5;
    private static final int BASE_MAYOR_KILL_POINTS = 10;

    private final TownBuildingsPlugin plugin;

    public TownyWarKillListener(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(@NotNull PlayerDeathEvent event) {
        if (!plugin.townyWarService().available()) return;

        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;

        Town killerTown = plugin.towny().getTownOf(killer);
        Town victimTown = plugin.towny().getTownOf(victim);
        if (killerTown == null || victimTown == null) return;
        if (killerTown.getUUID().equals(victimTown.getUUID())) return;

        // Города должны быть противниками в ОДНОЙ активной войне.
        String opp = plugin.townyWarService().opposingTown(killerTown.getName());
        if (opp == null || !opp.equalsIgnoreCase(victimTown.getName())) return;

        double mult = plugin.bonusService()
                .getRewards(killerTown.getUUID())
                .townyWarKillPointsMultiplier();
        if (mult <= 1.0) return;

        int base = isMayor(victim, victimTown)
                ? BASE_MAYOR_KILL_POINTS
                : BASE_KILL_POINTS;
        int extra = (int) Math.round(base * (mult - 1.0));
        if (extra <= 0) return;

        plugin.townyWarService().addKillPoints(killerTown.getName(), extra);
    }

    private boolean isMayor(@NotNull Player victim, @NotNull Town victimTown) {
        try {
            Resident mayor = victimTown.getMayor();
            return mayor != null && victim.getUniqueId().equals(mayor.getUUID());
        } catch (Throwable t) {
            return false;
        }
    }
}