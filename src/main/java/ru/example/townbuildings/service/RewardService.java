package ru.example.townbuildings.service;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.config.BuildingDefinition;
import ru.example.townbuildings.config.BuildingRewards;
import ru.example.townbuildings.database.model.BuildingData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Расчёт и выдача почасовых наград.
 * 11-C4 — mintIncomeBonusPercent вместо хардкода по TREASURY_ID.
 * 11-D7 — MAX_HOURS_PER_BATCH читается из config.yml.
 * 10    — stockItemsPerHour суммируется кумулятивно (как EffectService),
 *         остальные бонусы — только текущий уровень.
 * 1.2   — flushTownSync сессий /t stock ПЕРЕД depositBatch.
 * 1.3   — refundCityHours / refundPlayerHours при race-потере адресата.
 * 4.4   — maxHoursPerBatch volatile + reloadSettings().
 * P6    — вход в checkAndGiveCityRewards/checkAndGivePlayerRewards
 *         обёрнут в main-таск: методы могут быть вызваны из io-потока,
 *         а первое же обращение — к TownyAPI/Bukkit.
 * P21   — checkAndGivePlayerRewards: игрок должен всё ещё быть в том
 *         городе, за который накопил часы.
 * P7    — giveCityRewards: flushTownSync → flushTownAsync.
 * R1    — ни одна списанная награда не теряется:
 *         • depositToTown/depositToPlayer вернули false (исключение Towny
 *           проглатывается внутри EconomyService) → рефанд часов;
 *         • цепочки thenAccept с мутацией БД обёрнуты в try/catch →
 *           рефанд часов при любом unexpected-исключении;
 *         • exceptionally на каждом refund — ошибка рефанда логируется,
 *           но не роняет поток.
 * MINT-SCOPE — mintIncomeBonusPercent применяется ТОЛЬКО к монетам
 *         самого монетного двора, а не ко всем town-coins зданий.
 * P6b   — consumePlayerHours.complete() завершается на io-потоке БД.
 *         Внутри его thenAccept раньше дёргались Bukkit.getPlayer и
 *         TownyAPI.getTownOf — оба main-only → падало с
 *         "[Threading] getTownOf вызван из async-потока".
 *         Теперь всё тело колбэка перенесено в GlobalRegionScheduler.run.
 */
public final class RewardService {

    /** ★ Фиксированный ID монетного двора в buildings.yml. */
    private static final String MINT_BUILDING_ID = "mint";

    private final TownBuildingsPlugin plugin;

    /** 11-D7 / 4.4 — volatile, чтобы reloadSettings мог переписать. */
    private volatile int maxHoursPerBatch;

    public RewardService(@NotNull TownBuildingsPlugin plugin) {
        this.plugin = plugin;
        this.maxHoursPerBatch = plugin.getConfigManager().online().maxHoursPerBatch();
    }

    /**
     * 4.4 — пересчитать max-hours-per-batch из нового config.yml.
     */
    public void reloadSettings() {
        this.maxHoursPerBatch = plugin.getConfigManager().online().maxHoursPerBatch();
        plugin.getLogger().info("[Reward] max-hours-per-batch=" + maxHoursPerBatch);
    }

    // ============================================================
    //   ГОРОД
    // ============================================================

    public void checkAndGiveCityRewards(@NotNull UUID townUuid) {
        // P6 — метод может быть вызван из io-потока (OnlineTimeService.tick),
        // но первое же обращение идёт к TownyAPI. Оборачиваем весь вход
        // в main-таск; consumeCityHours остаётся async.
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            // 1.3 — проверяем существование города ДО списания часов.
            Town town = TownyAPI.getInstance().getTown(townUuid);
            if (town == null) return;

            plugin.townService().consumeCityHours(townUuid, maxHoursPerBatch).thenAccept(hours -> {
                if (hours <= 0) return;
                // P6b — колбэк io-поток. Ничего, кроме планирования main-таска,
                // здесь делать нельзя (TownyAPI/Bukkit — main-only).
                Bukkit.getGlobalRegionScheduler().run(plugin,
                        task2 -> giveCityRewards(townUuid, hours));
            });
        });
    }

    private void giveCityRewards(@NotNull UUID townUuid, int hours) {
        Town town = TownyAPI.getInstance().getTown(townUuid);
        if (town == null) {
            // 1.3 — race: город удалён между consume и выдачей.
            //       Возвращаем часы, чтобы не потерять.
            plugin.townDao().refundCityHours(townUuid, hours)
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("[Reward] refundCityHours("
                                + townUuid + "," + hours + ") упал: " + ex.getMessage());
                        return null;
                    });
            return;
        }

        plugin.townService().getBuildings(townUuid).thenAccept(buildings -> {
            try {
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    // ★ Разделяем монеты: mint и non-mint.
                    //   mintIncomeBonusPercent применяется ТОЛЬКО к монетам
                    //   самого монетного двора. Раньше процент неожиданно
                    //   бустил и town-coins других зданий (если бы админ
                    //   добавил такое в buildings.yml).
                    long nonMintCoinsPerHour = 0L;
                    long mintCoinsPerHour = 0L;
                    Map<Material, Integer> stockPerHour = new HashMap<>();
                    double mintIncomeBonusPercent = 0;                    // 11-C4

                    for (BuildingData bd : buildings) {
                        BuildingDefinition def = plugin.buildingRegistry().byId(bd.buildingId()).orElse(null);
                        if (def == null) continue;

                        // ★ Абсолютные бонусы — только текущий уровень.
                        BuildingRewards rCur = def.level(bd.level())
                                .map(l -> l.rewards())
                                .orElse(BuildingRewards.EMPTY);
                        if (!rCur.isEmpty()) {
                            if (MINT_BUILDING_ID.equals(bd.buildingId())) {
                                mintCoinsPerHour += rCur.townCoinsPerHour();
                            } else {
                                nonMintCoinsPerHour += rCur.townCoinsPerHour();
                            }
                            mintIncomeBonusPercent += rCur.mintIncomeBonusPercent();
                        }

                        // ★ Кумулятивно только для stockItemsPerHour.
                        int maxLvl = Math.min(bd.level(), def.maxLevel());
                        for (int lvl = 1; lvl <= maxLvl; lvl++) {
                            BuildingRewards r = def.level(lvl)
                                    .map(l -> l.rewards())
                                    .orElse(BuildingRewards.EMPTY);
                            for (var e : r.stockItemsPerHour().entrySet()) {
                                stockPerHour.merge(e.getKey(), e.getValue(), Integer::sum);
                            }
                        }
                    }

                    // ★ Бонус применяется только к монетам монетного двора.
                    long mintWithBonus = mintCoinsPerHour;
                    if (mintIncomeBonusPercent > 0 && mintCoinsPerHour > 0) {
                        mintWithBonus += Math.round(
                                mintCoinsPerHour * mintIncomeBonusPercent / 100.0);
                    }
                    long totalTownCoinsPerHour = nonMintCoinsPerHour + mintWithBonus;

                    long finalTownCoins = totalTownCoinsPerHour * hours;
                    // R1 — EconomyService.depositToTown глотает исключения Towny и
                    // возвращает false при неудаче. Раньше награда терялась молча:
                    // часы уже списаны, монеты не зачислены. Теперь — рефанд часов.
                    boolean coinsOk = true;
                    if (finalTownCoins > 0) {
                        coinsOk = plugin.economyService().depositToTown(town, finalTownCoins,
                                "TownBuildings: hourly reward");
                        if (!coinsOk) {
                            plugin.getLogger().warning("[Reward] depositToTown(" + town.getName()
                                    + ", " + finalTownCoins + ") не выполнен — возврат "
                                    + hours + " ч. городу " + townUuid);
                        }
                    }

                    if (!coinsOk) {
                        refundCityHours(townUuid, hours);
                        return;
                    }

                    if (!stockPerHour.isEmpty()) {
                        int maxPages = resolveStockPages(buildings);
                        Map<Material, Integer> toDeposit = new HashMap<>(stockPerHour.size());
                        for (var e : stockPerHour.entrySet()) {
                            toDeposit.put(e.getKey(), e.getValue() * hours);
                        }

                        // P7 — flush сессий /t stock асинхронно, не блокируя main,
                        // затем depositBatch одной цепочкой.
                        plugin.stockSessions().flushTownAsync(townUuid)
                                .thenCompose(v -> plugin.stockService()
                                        .depositBatch(townUuid, maxPages, toDeposit))
                                .thenAccept(ok -> {
                                    if (!ok) {
                                        plugin.getLogger().info(
                                                "[Reward] /t stock переполнен для города "
                                                        + townUuid + " — часть ресурсов не влезла");
                                        return;
                                    }
                                    // ★ Хранилище пополнено в БД — инвалидируем сессии,
                                    //   чтобы открытые GUI подгрузили свежий снапшот.
                                    Bukkit.getGlobalRegionScheduler().run(plugin, invalidateTask ->
                                            plugin.stockSessions().invalidateTown(townUuid));
                                })
                                // R1 — исключение в цепочке склада (в т.ч. из flushTownAsync):
                                // монетная часть награды уже зачислена, поэтому часы НЕ
                                // возвращаем (иначе дубликаты при частичном успехе), но
                                // ошибку обязаны логировать — раньше она глохла в future.
                                .exceptionally(ex -> {
                                    plugin.getLogger().warning("[Reward] depositBatch("
                                            + townUuid + ") упал: " + ex.getMessage());
                                    return null;
                                });
                    }
                });
            } catch (Throwable t) {
                // R1 — любой unexpected-сбой до/внутри планирования main-таска:
                // часы ещё не «потрачены» на успешную выдачу → рефандим.
                plugin.getLogger().warning("[Reward] giveCityRewards(" + townUuid
                        + ") сбой: " + t + " — возврат " + hours + " ч.");
                refundCityHours(townUuid, hours);
            }
        }).exceptionally(ex -> {
            // R1 — getBuildings упал (БД): награда не выдана вовсе → рефанд часов.
            plugin.getLogger().warning("[Reward] getBuildings(" + townUuid
                    + ") упал: " + ex.getMessage() + " — возврат " + hours + " ч.");
            refundCityHours(townUuid, hours);
            return null;
        });
    }

    /** R1 — рефанд городских часов с обязательным логом ошибки. */
    private void refundCityHours(@NotNull UUID townUuid, int hours) {
        plugin.townDao().refundCityHours(townUuid, hours)
                .exceptionally(ex -> {
                    plugin.getLogger().severe("[Reward] refundCityHours("
                            + townUuid + "," + hours + ") упал: " + ex.getMessage());
                    return null;
                });
    }

    // ============================================================
    //   ИГРОК
    // ============================================================

    public void checkAndGivePlayerRewards(@NotNull UUID playerUuid, @NotNull UUID townUuid) {
        // P6 — как и у city-версии: первое обращение к Bukkit/Towny должно
        // произойти в main. Оборачиваем вход.
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            // 1.3 — проверяем онлайн ДО списания.
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) return;

            // P21 — игрок должен всё ещё быть в том городе, за который
            // накопил часы. Иначе бывший житель получит выплату из старого
            // города, параллельно копя в новом. В этом случае НЕ списываем
            // и НЕ выдаём; осиротевшая строка tb_player_online будет
            // вычищена отдельным механизмом (или останется как история).
            Town currentTown = plugin.towny().getTownOf(player);
            if (currentTown == null || !currentTown.getUUID().equals(townUuid)) {
                return;
            }

            plugin.onlineTimeDao().consumePlayerHours(playerUuid, townUuid, maxHoursPerBatch)
                    .thenAccept(hours -> {
                        if (hours <= 0) return;
                        // ★ P6b — колбэк приходит на io-потоке БД
                        //   (DatabaseManager.supplyAsync). Bukkit.getPlayer и
                        //   TownyAPI.getTownOf — main-only; раньше это
                        //   стреляло "[Threading] getTownOf вызван из
                        //   async-потока: TownBuildings-DB". Переносим всё
                        //   тело проверки и планирования в main-таск.
                        Bukkit.getGlobalRegionScheduler().run(plugin, mainTask -> {
                            // P2/R1 — до планирования выдачи проверяем повторно:
                            // если игрок уже вышел или сменил город, награда не
                            // будет выдана вовсе — возвращаем списанные часы
                            // сразу, не дожидаясь givePlayerRewards.
                            Player p = Bukkit.getPlayer(playerUuid);
                            Town nowTown = (p != null && p.isOnline())
                                    ? plugin.towny().getTownOf(p) : null;
                            if (p == null || !p.isOnline() || nowTown == null
                                    || !nowTown.getUUID().equals(townUuid)) {
                                plugin.onlineTimeDao().refundPlayerHours(playerUuid, townUuid, hours)
                                        .exceptionally(ex -> {
                                            plugin.getLogger().warning("[Reward] refundPlayerHours(pre-give,"
                                                    + playerUuid + "," + townUuid + "," + hours
                                                    + ") упал: " + ex.getMessage());
                                            return null;
                                        });
                                return;
                            }
                            givePlayerRewards(playerUuid, townUuid, hours);
                        });
                    })
                    // R1 — consumePlayerHours упал (БД): логируем вместо тихой потери.
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("[Reward] consumePlayerHours("
                                + playerUuid + "," + townUuid + ") упал: " + ex.getMessage());
                        return null;
                    });
        });
    }

    private void givePlayerRewards(@NotNull UUID playerUuid, @NotNull UUID townUuid, int hours) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            // 1.3 — race: игрок вышел между consume и выдачей.
            //       Возвращаем часы, чтобы не потерять.
            plugin.onlineTimeDao().refundPlayerHours(playerUuid, townUuid, hours)
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("[Reward] refundPlayerHours("
                                + playerUuid + "," + townUuid + "," + hours
                                + ") упал: " + ex.getMessage());
                        return null;
                    });
            return;
        }

        plugin.townService().getBuildings(townUuid).thenAccept(buildings -> {
            try {
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    long citizenCoinsPerHour = 0L;
                    for (BuildingData bd : buildings) {
                        BuildingDefinition def = plugin.buildingRegistry().byId(bd.buildingId()).orElse(null);
                        if (def == null) continue;
                        citizenCoinsPerHour += def.level(bd.level())
                                .map(l -> l.rewards().citizenCoinsPerHour())
                                .orElse(0L);
                    }
                    if (citizenCoinsPerHour <= 0) return;
                    long total = citizenCoinsPerHour * hours;
                    // R1 — при неудаче депозита возвращаем списанные часы.
                    boolean ok = plugin.economyService().depositToPlayer(player, total,
                            "TownBuildings: bank reward");
                    if (!ok) {
                        plugin.getLogger().warning("[Reward] depositToPlayer(" + player.getName()
                                + ", " + total + ") не выполнен — возврат " + hours + " ч.");
                        refundPlayerHours(playerUuid, townUuid, hours);
                    }
                });
            } catch (Throwable t) {
                plugin.getLogger().warning("[Reward] givePlayerRewards(" + playerUuid
                        + ") сбой: " + t + " — возврат " + hours + " ч.");
                refundPlayerHours(playerUuid, townUuid, hours);
            }
        }).exceptionally(ex -> {
            // R1 — getBuildings упал (БД): награда не выдана вовсе → рефанд часов.
            plugin.getLogger().warning("[Reward] getBuildings(player," + townUuid
                    + ") упал: " + ex.getMessage() + " — возврат " + hours + " ч.");
            refundPlayerHours(playerUuid, townUuid, hours);
            return null;
        });
    }

    /** R1 — рефанд часов игрока с обязательным логом ошибки. */
    private void refundPlayerHours(@NotNull UUID playerUuid, @NotNull UUID townUuid, int hours) {
        plugin.onlineTimeDao().refundPlayerHours(playerUuid, townUuid, hours)
                .exceptionally(ex -> {
                    plugin.getLogger().severe("[Reward] refundPlayerHours("
                            + playerUuid + "," + townUuid + "," + hours
                            + ") упал: " + ex.getMessage());
                    return null;
                });
    }

    // ============================================================
    //   Хелперы
    // ============================================================

    private int resolveStockPages(@NotNull List<BuildingData> buildings) {
        int level = buildings.stream()
                .filter(b -> b.buildingId().equals(StockService.WAREHOUSE_STOCK_BUILDING_ID))
                .mapToInt(BuildingData::level)
                .findFirst().orElse(0);
        return 1 + Math.max(0, Math.min(3, level));
    }

    public @NotNull CompletableFuture<BuildingRewards> sumRewards(@NotNull UUID townUuid) {
        return plugin.townService().getBuildings(townUuid).thenApply(buildings -> {
            long townCoins = 0, citizenCoins = 0;
            int chunks = 0;
            double tax = 0, exp = 0, harvest = 0, mintIncome = 0;
            Map<Material, Integer> stock = new HashMap<>();

            for (BuildingData bd : buildings) {
                BuildingDefinition def = plugin.buildingRegistry().byId(bd.buildingId()).orElse(null);
                if (def == null) continue;

                BuildingRewards rCur = def.level(bd.level())
                        .map(l -> l.rewards())
                        .orElse(BuildingRewards.EMPTY);

                townCoins += rCur.townCoinsPerHour();
                citizenCoins += rCur.citizenCoinsPerHour();
                chunks += rCur.townChunksBonus();
                tax += rCur.taxReductionPercent();
                exp += rCur.expBonusPercent();
                harvest += rCur.harvestBonusPercent();
                mintIncome += rCur.mintIncomeBonusPercent();

                int maxLvl = Math.min(bd.level(), def.maxLevel());
                for (int lvl = 1; lvl <= maxLvl; lvl++) {
                    BuildingRewards r = def.level(lvl)
                            .map(l -> l.rewards())
                            .orElse(BuildingRewards.EMPTY);
                    for (var e : r.stockItemsPerHour().entrySet()) {
                        stock.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                }
            }

            return new BuildingRewards(townCoins, citizenCoins, Map.copyOf(stock),
                    chunks, tax, exp, harvest, mintIncome);
        });
    }
}