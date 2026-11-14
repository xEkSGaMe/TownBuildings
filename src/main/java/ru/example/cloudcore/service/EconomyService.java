package ru.example.cloudcore.service;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.economy.TownyServerAccount;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Работа с казной города и балансом игрока через Towny Account API.
 *
 * ВАЖНО: методы вызываются только из главного потока
 * (Towny Account не является thread-safe).
 *
 * E1 — getTownBalanceSafe(): попытка прочитать баланс с одним повтором;
 *      при обеих неудачах — OptionalDouble.empty() вместо 0.0.
 *
 * ★ Closed economy — интеграция с системным аккаунтом towny-server.
 *   Контролируется флагом economy.use-server-account в config.yml.
 *   При включении:
 *     • расходы на здания/чудеса: town → towny-server
 *     • награды городу/игроку:    towny-server → town/player
 *       (если на towny-server не хватает — молча не выдаём)
 *
 *   ⚠ TownyServerAccount помечен @Internal — риск при обновлении Towny.
 *     Все вызовы обёрнуты в try/catch, при недоступности аккаунта
 *     fallback на town-режим.
 */
public final class EconomyService {

    private final JavaPlugin plugin;

    /** ★ Предупреждение о недоступности towny-server выводим один раз за сессию. */
    private final AtomicBoolean warnedServerUnavailable = new AtomicBoolean(false);

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

    // ============================================================
    //   Town account (как было)
    // ============================================================

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

    /**
     * E1 — безопасное чтение баланса с одним повтором.
     * @return OptionalDouble.empty() — баланс недоступен (ошибка Towny);
     *         иначе — актуальное значение.
     */
    public @NotNull OptionalDouble getTownBalanceSafe(@NotNull Town town) {
        warnIfAsync("getTownBalanceSafe");
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return OptionalDouble.of(town.getAccount().getHoldingBalance());
            } catch (Throwable t) {
                if (attempt == 1) {
                    plugin.getLogger().warning("[Economy] Баланс города "
                            + town.getName() + " недоступен после 2 попыток: "
                            + t.getMessage());
                    return OptionalDouble.empty();
                }
            }
        }
        return OptionalDouble.empty(); // недостижимо
    }

    public boolean hasTownBalance(@NotNull Town town, long amount) {
        warnIfAsync("hasTownBalance");
        if (amount <= 0) return true;
        return getTownBalance(town) >= amount;
    }

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

    // ============================================================
    //   ★ towny-server account (closed economy)
    // ============================================================

    /**
     * Флаг economy.use-server-account. Читается динамически — /tadmin reload
     * подхватывает изменения без рестарта.
     */
    public boolean useServerAccount() {
        try {
            return plugin.getConfig().getBoolean("economy.use-server-account", true);
        } catch (Throwable t) {
            return true;
        }
    }

    private @Nullable TownyServerAccount serverAccount() {
        try {
            return TownyServerAccount.ACCOUNT;
        } catch (Throwable t) {
            if (warnedServerUnavailable.compareAndSet(false, true)) {
                plugin.getLogger().warning("[Economy] towny-server аккаунт недоступен: "
                        + t.getMessage() + " — closed economy отключена (fallback на town).");
            }
            return null;
        }
    }

    /**
     * Проверка, что на towny-server достаточно средств.
     * Используется в RewardService перед выдачей награды.
     * @return false при нехватке/ошибке (молча).
     */
    public boolean canPayFromServer(long amount) {
        if (amount <= 0) return true;
        if (!useServerAccount()) return true;
        warnIfAsync("canPayFromServer");
        TownyServerAccount acc = serverAccount();
        if (acc == null) return false;
        try {
            return acc.getHoldingBalance() >= amount;
        } catch (Throwable t) {
            plugin.getLogger().warning("[Economy] canPayFromServer: " + t.getMessage());
            return false;
        }
    }

    public boolean withdrawFromServer(long amount, @NotNull String reason) {
        if (amount <= 0) return true;
        if (!useServerAccount()) return true;
        warnIfAsync("withdrawFromServer");
        TownyServerAccount acc = serverAccount();
        if (acc == null) return false;
        try {
            return acc.withdraw((double) amount, reason);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Economy] withdrawFromServer: " + t.getMessage());
            return false;
        }
    }

    public boolean depositToServer(long amount, @NotNull String reason) {
        if (amount <= 0) return true;
        if (!useServerAccount()) return true;
        warnIfAsync("depositToServer");
        TownyServerAccount acc = serverAccount();
        if (acc == null) return false;
        try {
            return acc.deposit((double) amount, reason);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Economy] depositToServer: " + t.getMessage());
            return false;
        }
    }

    // ============================================================
    //   ★ Высокоуровневые операции (учитывают closed economy)
    // ============================================================

    /**
     * Расход на апгрейд здания / постройку чуда.
     *
     * В closed economy: town.withdraw → server.deposit.
     * При неудаче deposit — откат town.withdraw.
     *
     * @return false, если town не смог заплатить.
     */
    public boolean withdrawForUpgrade(@NotNull Town town, long amount, @NotNull String reason) {
        if (amount <= 0) return true;
        if (!useServerAccount()) {
            return withdrawFromTown(town, amount, reason);
        }
        if (!withdrawFromTown(town, amount, reason)) return false;
        if (!depositToServer(amount, reason)) {
            // server не принял — вернём town, чтобы не потерять деньги
            depositToTown(town, amount, "rollback:" + reason);
            return false;
        }
        return true;
    }

    /**
     * Возврат средств при фейле апгрейда.
     *
     * В closed economy: server.withdraw → town.deposit.
     * Best-effort: если server не может отдать — всё равно возвращаем town
     * (пишем SEVERE для ручного разбора).
     */
    public boolean refundUpgrade(@NotNull Town town, long amount, @NotNull String reason) {
        if (amount <= 0) return true;
        if (!useServerAccount()) {
            return depositToTown(town, amount, reason);
        }
        boolean serverOk = withdrawFromServer(amount, "rollback:" + reason);
        if (!serverOk) {
            plugin.getLogger().severe("[Economy] refundUpgrade: towny-server не смог отдать "
                    + amount + " (город " + town.getName()
                    + ") — closed economy нарушена, требуется ручной фикс");
        }
        return depositToTown(town, amount, reason);
    }

    /**
     * Награда городу (монетный двор, городской банк и т.д.).
     *
     * В closed economy: проверка server → withdraw → town.deposit.
     * Если server не может заплатить — false, тихо.
     */
    public boolean payCityReward(@NotNull Town town, long amount, @NotNull String reason) {
        if (amount <= 0) return true;
        if (!useServerAccount()) {
            return depositToTown(town, amount, reason);
        }
        if (!canPayFromServer(amount)) return false;
        if (!withdrawFromServer(amount, reason)) return false;
        if (!depositToTown(town, amount, reason)) {
            depositToServer(amount, "rollback:" + reason);
            return false;
        }
        return true;
    }

    /**
     * Награда игроку (city_bank и т.п.).
     *
     * В closed economy: проверка server → withdraw → player.deposit.
     * Если server не может заплатить — false, тихо.
     */
    public boolean payPlayerReward(@NotNull Player player, long amount, @NotNull String reason) {
        if (amount <= 0) return true;
        if (!useServerAccount()) {
            return depositToPlayer(player, amount, reason);
        }
        if (!canPayFromServer(amount)) return false;
        if (!withdrawFromServer(amount, reason)) return false;
        if (!depositToPlayer(player, amount, reason)) {
            depositToServer(amount, "rollback:" + reason);
            return false;
        }
        return true;
    }
}