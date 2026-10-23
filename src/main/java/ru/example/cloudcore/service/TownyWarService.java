package ru.example.cloudcore.service;

import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.config.BuildingDefinition;
import ru.example.cloudcore.config.BuildingLevel;
import ru.example.cloudcore.config.WonderConfig;
import ru.example.townywar.api.TownyWarAPI;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * Интеграция с TownyWar.
 *
 * M1 — растянутый backoff: 5с, 10с, 30с, 60с, 120с×3. Плюс retryFetch()
 *      для /tadmin reload — без рестарта можно подхватить API,
 *      если TownyWar загрузился позже нас.
 */
public final class TownyWarService {

    private static final String ACTOR = "plugin:TownBuildings";

    /** ★ M1 — растянутый backoff (тики): 5с, 10с, 30с, 60с, 120с×3. */
    private static final long[] RETRY_DELAYS_TICKS = {
            20L * 5, 20L * 10, 20L * 30, 20L * 60, 20L * 120, 20L * 120, 20L * 120
    };

    private final CloudCorePlugin plugin;
    private volatile @Nullable TownyWarAPI api;

    public TownyWarService(@NotNull CloudCorePlugin plugin) {
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

        if (attempt >= RETRY_DELAYS_TICKS.length) {
            plugin.getLogger().warning("[TownyWar] API не найден после "
                    + (RETRY_DELAYS_TICKS.length + 1) + " попыток — интеграция "
                    + "отключена до рестарта сервера. Если TownyWar загрузится "
                    + "позже, используйте /tadmin reload.");
            return;
        }

        long delay = RETRY_DELAYS_TICKS[attempt];
        Bukkit.getScheduler().runTaskLater(plugin, () -> tryFetch(attempt + 1), delay);
    }

    /** ★ M1 — повторная попытка подхватить API (вызывается из /tadmin reload). */
    public void retryFetch() {
        if (this.api != null) return;
        tryFetch(0);
    }

    public boolean available() { return api != null; }

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

        long deltaPoints = newT.startingPoints() - oldT.startingPoints();
        if (deltaPoints != 0) {
            long clamped = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, deltaPoints));
            int d = (int) clamped;
            boolean ok = api.addStartingBonus(townName, d, ACTOR);
            if (!ok) {
                plugin.getLogger().warning("[TownyWar] addStartingBonus("
                        + townName + ", " + d + ") отклонён (лимит/город).");
            }
        }

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

    /**
     * ★ Вызывается после успешной постройки чуда (tryBuild и adminGive).
     * Применяет townywar-награды чудес: стартовые очки и/или флаги.
     */
    public void onWonderBuilt(@NotNull Town town, @NotNull WonderConfig wc) {
        if (api == null) return;
        var r = wc.rewards();
        String townName = town.getName();

        long deltaPoints = r.townyWarStartingPoints();
        if (deltaPoints != 0) {
            long clamped = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, deltaPoints));
            int d = (int) clamped;
            boolean ok = api.addStartingBonus(townName, d, ACTOR + ":wonder:" + wc.id());
            if (!ok) {
                plugin.getLogger().warning("[TownyWar] wonder addStartingBonus("
                        + townName + ", +" + d + ", " + wc.id() + ") отклонён.");
            }
        }

        int deltaFlags = r.townyWarMaxFlagsBonus();
        if (deltaFlags != 0) {
            try {
                int current = api.getMaxConcurrentFlags(townName);
                int target = Math.max(1, Math.min(64, current + deltaFlags));
                api.setMaxConcurrentFlags(townName, target, ACTOR + ":wonder:" + wc.id());
            } catch (Throwable t) {
                plugin.getLogger().warning("[TownyWar] wonder flags error: " + t.getMessage());
            }
        }
    }

    /**
     * ★ Вызывается после удаления чуда (admin remove).
     * Снимает бонусы, ранее применённые onWonderBuilt.
     */
    public void onWonderRemoved(@NotNull Town town, @NotNull WonderConfig wc) {
        if (api == null) return;
        var r = wc.rewards();
        String townName = town.getName();

        long deltaPoints = -r.townyWarStartingPoints();
        if (deltaPoints != 0) {
            long clamped = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, deltaPoints));
            int d = (int) clamped;
            api.addStartingBonus(townName, d, ACTOR + ":wonder-remove:" + wc.id());
        }

        int deltaFlags = -r.townyWarMaxFlagsBonus();
        if (deltaFlags != 0) {
            try {
                int current = api.getMaxConcurrentFlags(townName);
                int target = Math.max(1, Math.min(64, current + deltaFlags));
                api.setMaxConcurrentFlags(townName, target, ACTOR + ":wonder-remove:" + wc.id());
            } catch (Throwable t) {
                plugin.getLogger().warning("[TownyWar] wonder-remove flags error: " + t.getMessage());
            }
        }
    }

    public @Nullable String opposingTown(@NotNull String townName) {
        if (api == null) return null;
        try {
            var war = api.getWar(townName);
            return war == null ? null : war.opposing(townName);
        } catch (Throwable t) {
            return null;
        }
    }

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