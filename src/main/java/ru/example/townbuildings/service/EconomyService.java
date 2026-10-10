package ru.example.townbuildings.service;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Работа с казной города и балансом игрока через Towny Account API.
 *
 * ВАЖНО: методы вызываются только из главного потока
 * (Towny Account не является thread-safe).
 */
public final class EconomyService {

    private final JavaPlugin plugin;

    public EconomyService(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private void warnIfAsync(@NotNull String method) {
        if (Bukkit.isPrimaryThread()) return;
        plugin.getLogger().severe("[Threading] EconomyService." + method
                + " вызван из async-потока: " + Thread.currentThread().getName());
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < Math.min(st.length, 8); i++) {
            sb.append("\n    at ").append(st[i]);
        }
        plugin.getLogger().severe(sb.toString());
    }

    /** @return текущий баланс казны города. */
    public double getTownBalance(@NotNull Town town) {
        warnIfAsync("getTownBalance");
        try {
            return town.getAccount().getHoldingBalance();
        } catch (Throwable t) {
            plugin.getLogger().warning("[Economy] Не удалось прочитать баланс города "
                    + town.getName() + ": " + t.getMessage());
            return 0.0D;
        }
    }

    /** @return true, если в казне достаточно средств. */
    public boolean hasTownBalance(@NotNull Town town, long amount) {
        warnIfAsync("hasTownBalance");
        if (amount <= 0) return true;
        return getTownBalance(town) >= amount;
    }

    /**
     * Списать монеты с казны города.
     * @return true — успех, false — не хватает / ошибка.
     */
    public boolean withdrawFromTown(@NotNull Town town, long amount, @NotNull String reason) {
        warnIfAsync("withdrawFromTown");
        if (amount <= 0) return true;
        try {
            return town.getAccount().withdraw((double) amount, reason);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Economy] Ошибка списания " + amount
                    + " у города " + town.getName() + ": " + t.getMessage());
            return false;
        }
    }

    /** Вернуть монеты в казну (rollback). */
    public boolean depositToTown(@NotNull Town town, long amount, @NotNull String reason) {
        warnIfAsync("depositToTown");
        if (amount <= 0) return true;
        try {
            return town.getAccount().deposit((double) amount, reason);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Economy] Ошибка возврата " + amount
                    + " городу " + town.getName() + ": " + t.getMessage());
            return false;
        }
    }

    /** Начислить монеты конкретному игроку (Городской банк, Этап 8). */
    public boolean depositToPlayer(@NotNull Player player, long amount, @NotNull String reason) {
        warnIfAsync("depositToPlayer");
        if (amount <= 0) return true;
        try {
            Resident res = TownyAPI.getInstance().getResident(player.getUniqueId());
            if (res == null) return false;
            res.getAccount().deposit((double) amount, reason);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[Economy] Ошибка начисления " + amount
                    + " игроку " + player.getName() + ": " + t.getMessage());
            return false;
        }
    }
}