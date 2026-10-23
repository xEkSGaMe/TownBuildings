package ru.example.cloudcore.service;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * Тонкая обёртка над Towny API.
 * Методы НЕ потокобезопасны — вызывать только из главного потока.
 * В режиме debug логирует вызовы из async-потоков.
 */
public final class TownyIntegrationService {

    private final CloudCorePlugin plugin;

    public TownyIntegrationService(@NotNull CloudCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Логирует, если вызов произошёл не из главного потока. */
    private void warnIfAsync(@NotNull String method) {
        if (Bukkit.isPrimaryThread()) return;

        // Всегда логируем — это ошибка архитектуры, а не debug-инфо.
        plugin.getLogger().severe("[Threading] " + method
                + " вызван из async-потока: " + Thread.currentThread().getName());
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < Math.min(st.length, 8); i++) {
            sb.append("\n    at ").append(st[i]);
        }
        plugin.getLogger().severe(sb.toString());
    }

    // ============================================================
    //   Публичные методы
    // ============================================================

    public @Nullable Town getTownOf(@NotNull Player player) {
        warnIfAsync("getTownOf");
        Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
        return resident != null ? resident.getTownOrNull() : null;
    }

    public @Nullable Town getTownByPlayerUuid(@NotNull UUID playerUuid) {
        warnIfAsync("getTownByPlayerUuid");
        Player p = Bukkit.getPlayer(playerUuid);
        if (p == null) return null;
        return getTownOf(p);
    }

    public @Nullable Town getTownByUuid(@NotNull UUID townUuid) {
        warnIfAsync("getTownByUuid");
        return TownyAPI.getInstance().getTown(townUuid);
    }

    public @Nullable Town getTownByName(@NotNull String name) {
        warnIfAsync("getTownByName");
        return TownyAPI.getInstance().getTown(name);
    }

    public @NotNull UUID getTownUuid(@NotNull Town town) {
        warnIfAsync("getTownUuid");
        return town.getUUID();
    }

    public @NotNull String getTownName(@NotNull Town town) {
        warnIfAsync("getTownName");
        return town.getName();
    }

    public @Nullable Resident getMayor(@NotNull Town town) {
        warnIfAsync("getMayor");
        return town.getMayor();
    }

    public @Nullable UUID getMayorUuid(@NotNull Town town) {
        warnIfAsync("getMayorUuid");
        Resident mayor = town.getMayor();
        return mayor != null ? mayor.getUUID() : null;
    }

    public @NotNull List<Resident> getResidents(@NotNull Town town) {
        warnIfAsync("getResidents");
        return List.copyOf(town.getResidents());
    }

    public @NotNull List<Player> getOnlineMembers(@NotNull Town town) {
        warnIfAsync("getOnlineMembers");
        return town.getResidents().stream()
                .map(Resident::getPlayer)
                .filter(Objects::nonNull)
                .filter(Player::isOnline)
                .toList();
    }

    public boolean hasOnlineMembers(@NotNull Town town) {
        warnIfAsync("hasOnlineMembers");
        for (Resident r : town.getResidents()) {
            Player p = r.getPlayer();
            if (p != null && p.isOnline()) return true;
        }
        return false;
    }

    // ============================================================
    //   Бонусы зданий
    // ============================================================

    public void applyChunkBonus(@NotNull Town town, int deltaChunks) {
        warnIfAsync("applyChunkBonus");
        if (deltaChunks == 0) return;
        try {
            town.addBonusBlocks(deltaChunks);
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[Towny] Не удалось применить чанк-бонус для "
                    + town.getName() + ": " + t.getMessage());
        }
    }

    public double calculateTaxRefund(@NotNull Town town, double taxReductionPercent) {
        warnIfAsync("calculateTaxRefund");
        if (taxReductionPercent <= 0) return 0.0;
        try {
            double dailyTax = TownySettings.getTownUpkeepCost(town);
            return dailyTax * (taxReductionPercent / 100.0);
        } catch (Throwable t) {
            return 0.0;
        }
    }
}