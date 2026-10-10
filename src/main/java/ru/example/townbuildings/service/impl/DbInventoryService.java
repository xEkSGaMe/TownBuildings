package ru.example.townbuildings.service.impl;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.database.dao.InventoryDao;
import ru.example.townbuildings.service.InventoryService;
import ru.example.townbuildings.service.ResourceCheck;
import ru.example.townbuildings.service.WithdrawResult;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Реализация InventoryService поверх InventoryDao. */
public final class DbInventoryService implements InventoryService {

    private final InventoryDao dao;

    public DbInventoryService(@NotNull InventoryDao dao) {
        this.dao = dao;
    }

    @Override
    public @NotNull CompletableFuture<ResourceCheck> check(
            @NotNull UUID townUuid, @NotNull Map<Material, Integer> required) {
        return dao.countAll(townUuid).thenApply(have -> {
            ResourceCheck.Builder b = ResourceCheck.builder();
            for (Map.Entry<Material, Integer> e : required.entrySet()) {
                int need = e.getValue();
                int avail = have.getOrDefault(e.getKey(), 0);
                b.item(e.getKey(), need, avail);
            }
            return b.build();
        });
    }

    @Override
    public @NotNull CompletableFuture<Boolean> withdraw(
            @NotNull UUID townUuid, @NotNull Map<Material, Integer> required) {
        if (required.isEmpty()) return CompletableFuture.completedFuture(true);
        return dao.withdrawAll(townUuid, required);
    }

    @Override
    public @NotNull CompletableFuture<WithdrawResult> withdrawIfAvailable(
            @NotNull UUID townUuid, @NotNull Map<Material, Integer> required) {
        if (required.isEmpty()) {
            return CompletableFuture.completedFuture(WithdrawResult.success());
        }
        return dao.withdrawIfAvailable(townUuid, required);
    }

    @Override
    public @NotNull CompletableFuture<Map<Material, Integer>> countAll(@NotNull UUID townUuid) {
        return dao.countAll(townUuid);
    }
}