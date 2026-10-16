package ru.example.townbuildings.service;

import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingDefinition;
import ru.example.townbuildings.config.BuildingLevel;
import ru.example.townywar.api.TownyWarAPI;

/**
 * Интеграция с TownyWar.
 *
 * Стартовые очки/лимит флагов применяются ДЕЛЬТОЙ при каждом изменении
 * уровня здания (апгрейд/даунгрейд/админский setlevel/remove). Это
 * сохраняет вклад других плагинов в tb_starting_bonus — мы никогда не
 * вызываем setStartingBonus, только addStartingBonus(delta).
 *
 * Дельту считаем по yml-определению уровня — не нужно хранить «наш
 * последний вклад» между рестартами: он всегда восстанавливается
 * из суммы rewards[1..level].
 *
 * E10 — deltaPoints clamp'ится в int-диапазон: API TownyWar принимает int,
 *       а из admin-yml с starting-points > 2^31 могло прийти переполнение.
 */
public final class TownyWarService {

    private static final String ACTOR = "plugin:TownBuildings";
    private static final int MAX_FETCH_ATTEMPTS = 5;

    private final TownBuildingsPlugin plugin;
    private volatile @Nullable TownyWarAPI api;

    public TownyWarService(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
    }

    public void onEnable() {
        tryFetch(0);
    }

    private void tryFetch(int attempt) {
        var reg = Bukkit.getServicesManager().getRegistration(TownyWarAPI.class);
        if (reg != null) {
            this.api = reg.getProvider();
            int v = api.getApiVersion();
            if (v != 1) {
                plugin.getLogger().warning("[TownyWar] Неожиданная версия API: "
                        + v + " (ожидается 1) — продолжаем с best-effort.");
            }
            plugin.getLogger().info("[TownyWar] API v" + v + " подключён.");
            return;
        }
        if (attempt >= MAX_FETCH_ATTEMPTS) {
            plugin.getLogger().info("[TownyWar] API не найден после "
                    + MAX_FETCH_ATTEMPTS + " попыток — интеграция отключена.");
            return;
        }
        long delay = 20L * (attempt + 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> tryFetch(attempt + 1), delay);
    }

    public boolean available() { return api != null; }

    // ============================================================
    //   Изменение уровня здания
    // ============================================================

    public void onBuildingLevelChanged(@NotNull Town town,
                                       @NotNull String buildingId,
                                       int oldLevel, int newLevel) {
        if (api == null || oldLevel == newLevel) return;
        BuildingDefinition def = plugin.buildingRegistry().byId(buildingId).orElse(null);
        if (def == null) return;

        Totals oldT = computeTotals(def, oldLevel);
        Totals newT = computeTotals(def, newLevel);
        if (oldT.equals(newT)) return;

        String townName = town.getName();

        // Стартовые очки — дельта, суммируется в TownyWar.
        long deltaPoints = newT.startingPoints() - oldT.startingPoints();
        if (deltaPoints != 0) {
            // ★ E10 — clamp в int-диапазон: API TownyWar принимает int.
            // Без защиты админский yml со starting-points > 2^31 при апгрейде
            // мог бы отправить отрицательное число.
            long clamped = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, deltaPoints));
            int d = (int) clamped;
            boolean ok = api.addStartingBonus(townName, d, ACTOR);
            if (!ok) {
                plugin.getLogger().warning("[TownyWar] addStartingBonus("
                        + townName + ", " + d + ") отклонён (лимит/город).");
            }
        }

        // Лимит одновременных флагов — абсолютное значение, но мы храним
        // только СВОЙ вклад как дельту и аддитивно правим текущее.
        int deltaFlags = newT.maxFlagsBonus() - oldT.maxFlagsBonus();
        if (deltaFlags != 0) {
            try {
                int current = api.getMaxConcurrentFlags(townName);
                int target = Math.max(1, Math.min(64, current + deltaFlags));
                boolean ok = api.setMaxConcurrentFlags(townName, target, ACTOR);
                if (!ok) {
                    plugin.getLogger().warning("[TownyWar] setMaxConcurrentFlags("
                            + townName + ", " + target + ") отклонён.");
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("[TownyWar] flags error: " + t.getMessage());
            }
        }
    }

    // ============================================================
    //   Kill-очки (Форт)
    // ============================================================

    /** Противник города в активной войне, или null. */
    public @Nullable String opposingTown(@NotNull String townName) {
        if (api == null) return null;
        try {
            var war = api.getWar(townName);
            return war == null ? null : war.opposing(townName);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Добавить бонусные очки городу (только во время активной войны). */
    public void addKillPoints(@NotNull String townName, int amount) {
        if (api == null || amount <= 0) return;
        try {
            boolean ok = api.addPoints(townName, amount, ACTOR + ":fort");
            if (!ok && plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[TownyWar] addPoints("
                        + townName + ", " + amount + ") отклонён (нет активной войны).");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[TownyWar] addPoints error: " + t.getMessage());
        }
    }

    // ============================================================
    //   Внутреннее
    // ============================================================

    private @NotNull Totals computeTotals(@NotNull BuildingDefinition def, int currentLevel) {
        if (currentLevel <= 0) return new Totals(0L, 0, 1.0);
        int maxLvl = Math.min(currentLevel, def.maxLevel());
        long points = 0L;
        int flags = 0;
        double mult = 1.0;
        for (int lvl = 1; lvl <= maxLvl; lvl++) {
            BuildingLevel bl = def.level(lvl).orElse(null);
            if (bl == null) continue;
            var r = bl.rewards();
            points += r.townyWarStartingPoints();
            flags  += r.townyWarMaxFlagsBonus();
            if (r.townyWarKillPointsMultiplier() > mult) {
                mult = r.townyWarKillPointsMultiplier();
            }
        }
        return new Totals(points, flags, mult);
    }

    private record Totals(long startingPoints, int maxFlagsBonus, double killMultiplier) {}
}