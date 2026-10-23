package ru.example.cloudcore.institutes.service;

import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.CloudCorePlugin;
import ru.example.cloudcore.institutes.data.*;
import ru.example.cloudcore.service.TownyIntegrationService;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Обработка выбора религии / формы / идеологии.
 *
 * Гарантии от дюпов:
 *  - inFlight per town: только одна операция на город в полёте.
 *  - dao.save выполняется ДО addBonusBlocks(delta). Если краш между
 *    ними — в БД applied==desired, при следующем reconcile diff=0
 *    → нет повторного начисления. Недобор допустим, дюп — нет.
 *  - XP списывается в main-thread ПОСЛЕ успеха dao.save.
 *
 * ★ 5a: после успешной смены выбора инвалидируем BonusService-cache,
 *        иначе refreshHealth() прочитает старый healthBonusHearts
 *        (religion/ideology/form — institutes-часть BuildingRewards).
 */
public final class InstituteSelectionService {

    private static final int CATHOLIC_CHUNK_BONUS = 75;
    private static final int HINDU_CHUNK_BONUS    = 50;

    public enum Result {
        FIRST_CHOICE, SAME_CHOICE, CHANGED, NOT_ENOUGH_XP, NO_TOWN, BUSY, ERROR
    }

    private final CloudCorePlugin plugin;
    private final InstitutesService institutes;
    private final TownyIntegrationService towny;

    /** per-town lock: одна селекция/reconcile за раз. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public InstituteSelectionService(@NotNull CloudCorePlugin plugin,
                                     @NotNull InstitutesService institutes,
                                     @NotNull TownyIntegrationService towny) {
        this.plugin = plugin;
        this.institutes = institutes;
        this.towny = towny;
    }

    // ============================================================
    //   ВЫБОР
    // ============================================================

    /**
     * Асинхронная смена выбора. Callback приходит на main-thread.
     */
    public void applyAsync(@NotNull Player player,
                           @NotNull Town town,
                           @NotNull MenuType type,
                           @NotNull Object value,
                           @NotNull Consumer<Result> callback) {

        UUID townUuid = town.getUUID();

        if (!inFlight.add(townUuid)) {
            callback.accept(Result.BUSY);
            return;
        }

        institutes.getOrLoad(townUuid, town.getName()).whenComplete((current, ex) -> {
            if (ex != null || current == null) {
                if (ex != null) plugin.getLogger().warning("[Institutes/Selection] getOrLoad: " + ex.getMessage());
                inFlight.remove(townUuid);
                callback.accept(Result.ERROR);
                return;
            }

            if (current.get(type) != null && current.get(type).equals(value)) {
                inFlight.remove(townUuid);
                callback.accept(Result.SAME_CHOICE);
                return;
            }

            boolean freeChange = isFreeChange(current, type);
            int cost = freeChange ? 0 : plugin.institutesConfig().changeCostLevels();
            boolean isFirstChoice = current.get(type) == null;

            // XP проверяем заранее, до записи в БД — не вычтем, если не хватает.
            if (!isFirstChoice && !freeChange && player.getLevel() < cost) {
                inFlight.remove(townUuid);
                callback.accept(Result.NOT_ENOUGH_XP);
                return;
            }

            // Готовим новые данные и считаем delta.
            int oldApplied = current.appliedChunkBonus();
            TownInstituteData updated = current.with(type, value);
            int desired = computeDesiredChunkBonus(updated);
            int delta = desired - oldApplied;
            TownInstituteData persisted = updated.withChunkBonus(desired, true);

            // 1. Сначала БД.
            institutes.update(persisted).whenComplete((v, dbEx) -> {
                if (dbEx != null) {
                    plugin.getLogger().severe("[Institutes/Selection] update failed for "
                            + townUuid + ": " + dbEx.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        inFlight.remove(townUuid);
                        callback.accept(Result.ERROR);
                    });
                    return;
                }
                // 2. На main-thread: XP + addBonusBlocks + invalidate + callback.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (!isFirstChoice && !freeChange && cost > 0) {
                            player.setLevel(Math.max(0, player.getLevel() - cost));
                        }
                        if (delta != 0) {
                            try {
                                town.addBonusBlocks(delta);
                            } catch (Throwable t) {
                                plugin.getLogger().warning("[Institutes/Selection] addBonusBlocks("
                                        + delta + ") для " + town.getName() + ": " + t.getMessage());
                            }
                        }
                        // ★ 5a — health-бонус мог измениться (religion/ideology/form).
                        //       Инвалидируем кэш BonusService ДО recalculateBonuses,
                        //       иначе refreshHealth() прочитает устаревшее значение.
                        //       Безусловно — даже если delta == 0 (islam→buddhism).
                        if (plugin.bonusService() != null) {
                            plugin.bonusService().invalidate(townUuid);
                        }
                        recalculateBonuses(town);
                        callback.accept(isFirstChoice ? Result.FIRST_CHOICE : Result.CHANGED);
                    } finally {
                        inFlight.remove(townUuid);
                    }
                });
            });
        });
    }

    // ============================================================
    //   RECONCILE
    // ============================================================

    /**
     * Пройтись по всем городам с institutes-данными и выровнять bonusBlocks.
     * Вызывается: на старте (после afterDatabaseReady), при reload, при admin-actions.
     *
     * ★ 5a: инвалидация BonusService здесь НЕ нужна — reconcile трогает только
     *        appliedChunkBonus, а contributeHealthBonus() читает
     *        religion/ideology/form (они не меняются).
     */
    public void reconcileAllTowns() {
        for (TownInstituteData data : institutes.getAllCached()) {
            if (data.isEmpty() && !data.chunkBonusInitialized()) continue;
            Town town = towny.getTownByUuid(data.townUuid());
            if (town == null) continue;
            reconcileChunkBonus(town, data);
        }
    }

    /**
     * Выравнивает bonusBlocks города к желаемому значению на основе
     * текущих выборов религии/формы.
     *
     * Логика:
     *  - Пока chunkBonusInitialized=false — «весь текущий бонус наш»:
     *    выставляем bonusBlocks = desired.
     *  - После миграции — применяем diff = desired - applied (может быть 0).
     *
     * Порядок: сначала updateChunkState в БД, затем addBonusBlocks на main.
     * Никогда не наоборот — иначе возможен дюп при крахе между шагами.
     */
    private void reconcileChunkBonus(@NotNull Town town, @NotNull TownInstituteData data) {
        UUID townUuid = town.getUUID();

        if (!inFlight.add(townUuid)) return; // кто-то уже reconcile'ит/меняет

        int desired = computeDesiredChunkBonus(data);
        boolean initialized = data.chunkBonusInitialized();
        int current = initialized ? data.appliedChunkBonus() : safeGetBonus(town);
        int diff = desired - current;

        if (initialized && diff == 0) {
            inFlight.remove(townUuid);
            return;
        }

        institutes.updateChunkState(townUuid, desired, true).whenComplete((v, ex) -> {
            if (ex != null) {
                plugin.getLogger().warning("[Institutes/Reconcile] updateChunkState failed for "
                        + townUuid + ": " + ex.getMessage());
                inFlight.remove(townUuid);
                return;
            }
            if (diff != 0) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        town.addBonusBlocks(diff);
                        plugin.getLogger().info("[Institutes/Reconcile] " + town.getName()
                                + ": bonus " + (diff > 0 ? "+" : "") + diff
                                + " (desired=" + desired + ")");
                    } catch (Throwable t) {
                        plugin.getLogger().warning("[Institutes/Reconcile] addBonusBlocks: " + t.getMessage());
                    } finally {
                        inFlight.remove(townUuid);
                    }
                });
            } else {
                inFlight.remove(townUuid);
            }
        });
    }

    // ============================================================
    //   ХЕЛПЕРЫ
    // ============================================================

    /** Смена религии бесплатна, если город — теократия. */
    public boolean isFreeChange(@NotNull TownInstituteData data, @NotNull MenuType type) {
        return type == MenuType.RELIGION && data.form() == GovernmentForm.THEOCRACY;
    }

    public int costFor(@Nullable Town town, @NotNull MenuType type) {
        if (town == null) return plugin.institutesConfig().changeCostLevels();
        TownInstituteData data = institutes.getCached(town.getUUID());
        if (data == null) return plugin.institutesConfig().changeCostLevels();
        return isFreeChange(data, type) ? 0 : plugin.institutesConfig().changeCostLevels();
    }

    private int computeDesiredChunkBonus(@NotNull TownInstituteData data) {
        int total = 0;
        if (data.religion() == Religion.CATHOLIC) total += CATHOLIC_CHUNK_BONUS;
        if (data.religion() == Religion.HINDUISM) total += HINDU_CHUNK_BONUS;
        if (data.form()     == GovernmentForm.MONARCHY) total += plugin.institutesConfig().monarchyChunkBonus();
        return total;
    }

    private int safeGetBonus(@NotNull Town town) {
        try { return town.getBonusBlocks(); }
        catch (Throwable t) { return 0; }
    }

    private void recalculateBonuses(@NotNull Town town) {
        UUID townUuid = town.getUUID();
        for (Player online : Bukkit.getOnlinePlayers()) {
            Town t = towny.getTownOf(online);
            if (t != null && t.getUUID().equals(townUuid)) {
                plugin.instituteBonus().refresh(online);
            }
        }
    }

    public void playDenySound(@NotNull Player p) {
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }

    public void playOkSound(@NotNull Player p) {
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.4f);
    }
}