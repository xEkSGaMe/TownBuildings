package ru.example.townbuildings.gui.session;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.townbuildings.TownBuildingsPlugin;
import ru.example.townbuildings.gui.holder.PageHolder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

// P7: импорты для async-flush.
import java.util.ArrayList;
import java.util.List;

/**
 * Реестр сессий страниц.
 * Один экземпляр на /t inventory, второй — на /t stock.
 *
 * 11-D7 — SAVE_DEBOUNCE_TICKS / SESSION_TTL_MS берутся из config.yml.
 * 1.2   — flushTownSync / invalidateTown гарантируют, что изменения
 *         из GUI записаны в БД ДО внешней операции над складом/хранилищем.
 * 3.2   — doFlushSync с таймаутом 2 сек, чтобы shutdown не висел
 *         бесконечно на зависшей БД.
 * 4.4   — reloadSettings(): пересчёт debounce/ttl без рестарта.
 * P7    — flushTownAsync: async-аналог flushTownSync, не блокирует main.
 */
public final class PageSessionRegistry {

    @FunctionalInterface
    public interface Loader {
        @NotNull CompletableFuture<Map<Integer, byte[]>> load(@NotNull UUID townUuid, int page);
    }

    @FunctionalInterface
    public interface Saver {
        @NotNull CompletableFuture<Void> save(@NotNull UUID townUuid, int page,
                                              @NotNull Map<Integer, byte[]> diff);
    }

    @FunctionalInterface
    public interface HolderFactory {
        @NotNull PageHolder create(@NotNull UUID townUuid, int page, int totalPages,
                                   @NotNull String townName);
    }

    @FunctionalInterface
    public interface TitleFactory {
        @NotNull Component build(@NotNull String townName, int page, int totalPages);
    }

    /** 3.2 — таймаут синхронного flush. */
    private static final long FLUSH_SYNC_TIMEOUT_SECONDS = 2L;

    private final TownBuildingsPlugin plugin;
    private final Loader loader;
    private final Saver saver;
    private final HolderFactory holderFactory;
    private final TitleFactory titleFactory;

    /** 11-D7 / 4.4 — volatile, чтобы reloadSettings мог их переписать. */
    private volatile long saveDebounceTicks;
    private volatile long sessionTtlMs;

    private final Map<Key, PageSession> sessions = new ConcurrentHashMap<>();
    private final Map<Key, Integer> pendingSaveTaskIds = new ConcurrentHashMap<>();

    public PageSessionRegistry(@NotNull TownBuildingsPlugin plugin,
                               @NotNull Loader loader,
                               @NotNull Saver saver,
                               @NotNull HolderFactory holderFactory,
                               @NotNull TitleFactory titleFactory) {
        this.plugin = plugin;
        this.loader = loader;
        this.saver = saver;
        this.holderFactory = holderFactory;
        this.titleFactory = titleFactory;

        var s = plugin.getConfigManager().sessions();
        this.saveDebounceTicks = s.saveDebounceTicks();
        this.sessionTtlMs      = s.sessionTtlSeconds() * 1000L;

        Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate((Plugin) plugin, t -> sweep(), 20L * 60, 20L * 60);
    }

    /**
     * 4.4 — пересчитать debounce/ttl из нового config.yml.
     * Уже открытые сессии не трогаем — применяется к следующим операциям.
     */
    public void reloadSettings() {
        var s = plugin.getConfigManager().sessions();
        this.saveDebounceTicks = s.saveDebounceTicks();
        this.sessionTtlMs      = s.sessionTtlSeconds() * 1000L;
        plugin.getLogger().info("[Sessions] Пересчитано: debounce="
                + saveDebounceTicks + "t, ttl=" + (sessionTtlMs / 1000) + "s");
    }

    // ============================================================
    //   Публичный API
    // ============================================================

    public @Nullable PageSession get(@NotNull UUID townUuid, int page) {
        return sessions.get(new Key(townUuid, page));
    }

    public @NotNull CompletableFuture<PageSession> getOrCreate(
            @NotNull UUID townUuid, int page, int totalPages, @NotNull String townName,
            @NotNull BiConsumer<PageSession, Map<Integer, byte[]>> onLoaded) {

        Key key = new Key(townUuid, page);
        PageSession existing = sessions.get(key);
        if (existing != null) return existing.readyFuture();

        PageHolder holder = holderFactory.create(townUuid, page, totalPages, townName);
        Component title = titleFactory.build(townName, page, totalPages);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);
        PageSession fresh = new PageSession(holder, inv);

        PageSession winner = sessions.putIfAbsent(key, fresh);
        if (winner != null) return winner.readyFuture();

        loader.load(townUuid, page).whenComplete((items, ex) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ex != null || items == null) {
                    plugin.getLogger().severe("[Sessions] load(" + townUuid + ","
                            + page + "): " + (ex != null ? ex.getMessage() : "null"));
                    sessions.remove(key, fresh);
                    fresh.failReady(ex != null ? ex : new IllegalStateException("empty"));
                    return;
                }
                onLoaded.accept(fresh, items);
                fresh.completeReady();
            });
        });

        return fresh.readyFuture();
    }

    public void addViewer(@NotNull PageSession session, @NotNull UUID playerUuid) {
        session.addViewer(playerUuid);
        cancelPendingSave(session);
    }

    public void removeViewer(@NotNull PageSession session, @NotNull UUID playerUuid) {
        session.removeViewer(playerUuid);
        if (!session.hasViewers() && session.isDirty()) {
            scheduleSave(session);
        }
    }

    public void markDirty(@NotNull PageSession session) {
        session.markDirty();
    }

    /** Синхронно сбросить всё (при выключении плагина). */
    public void flushAllSync() {
        for (PageSession s : sessions.values()) {
            if (!s.isDirty()) continue;
            try {
                doFlushSync(s);
            } catch (Throwable t) {
                plugin.getLogger().severe("[Sessions] flushAllSync: " + t.getMessage());
            }
        }
    }

    /**
     * 1.2 — синхронно сбросить все dirty-сессии города в БД и дождаться завершения.
     *
     * Вызывается ПЕРЕД БД-операциями (withdraw / deposit / setBuildingLevel),
     * чтобы незаписанные изменения игрока не «перетёрли» операцию и не оставили дюпа.
     *
     * ВНИМАНИЕ: вызывать только из главного потока и когда идёт внешняя
     * операция (не в hot-path) — метод делает join() на CompletableFuture.
     */
    public void flushTownSync(@NotNull UUID townUuid) {
        for (PageSession s : sessions.values()) {
            if (!s.holder().townUuid().equals(townUuid)) continue;
            if (!s.isDirty()) continue;
            try {
                doFlushSync(s);
            } catch (Throwable t) {
                plugin.getLogger().severe("[Sessions] flushTownSync(" + townUuid
                        + "," + s.holder().page() + "): " + t.getMessage());
            }
        }
    }

    /**
     * P7 — асинхронный аналог flushTownSync: не блокирует main ожиданием
     * ответа БД. Возвращает future, завершающийся, когда все dirty-сессии
     * города записаны в БД.
     *
     * ВАЖНО: вызывать из main. holder.capture()/diff() читают Inventory —
     * это main-only. В io уходит только saver.save(...).
     *
     * Использование в hot-path (апгрейд, выдача награды):
     *   flushTownAsync(uuid)
     *       .thenCompose(v -> doSomethingInDb())
     *       .orTimeout(...)
     */
    public @NotNull CompletableFuture<Void> flushTownAsync(@NotNull UUID townUuid) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (PageSession s : sessions.values()) {
            if (!s.holder().townUuid().equals(townUuid)) continue;
            if (!s.isDirty()) continue;
            futures.add(doFlushAsync(s));
        }
        if (futures.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * P7 — одна итерация async-flush для сессии. capture/diff/commit —
     * в main; saver.save — в io. Промежуточные шаги согласованы через
     * цепочку CompletableFuture.
     */
    private @NotNull CompletableFuture<Void> doFlushAsync(@NotNull PageSession session) {
        PageHolder holder = session.holder();
        holder.capture();
        Map<Integer, byte[]> diff = holder.diff();
        if (diff.isEmpty()) {
            holder.commit();
            session.markClean();
            return CompletableFuture.completedFuture(null);
        }

        UUID townUuid = holder.townUuid();
        int page = holder.page();
        CompletableFuture<Void> done = new CompletableFuture<>();

        saver.save(townUuid, page, diff).whenComplete((v, ex) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null) {
                        plugin.getLogger().severe("[Sessions] flush async(" + townUuid
                                + "," + page + "): " + ex.getMessage());
                        // Оставляем dirty — sweep повторит.
                        done.completeExceptionally(ex);
                        return;
                    }
                    holder.commit();
                    session.markClean();
                    done.complete(null);
                }));

        return done;
    }

    public void drop(@NotNull PageSession session) {
        sessions.remove(new Key(session.holder().townUuid(), session.holder().page()), session);
    }

    /**
     * Полная инвалидация всех сессий города.
     *
     * 1.2 — ПЕРЕД удалением обязательно делаем flushTownSync, иначе
     * незаписанные dirty-изменения потеряются, а следом БД-операция
     * (например, withdraw апгрейда) увидит старое состояние → дюп.
     */
    public void invalidateTown(@NotNull UUID townUuid) {
        // 1. Сохраняем всё, что успело накопиться.
        flushTownSync(townUuid);

        // 2. Теперь безопасно дропаем.
        java.util.List<PageSession> toClose = new java.util.ArrayList<>();

        sessions.entrySet().removeIf(e -> {
            if (!e.getKey().townUuid().equals(townUuid)) return false;
            PageSession s = e.getValue();
            if (s.hasViewers()) toClose.add(s);
            return true;
        });

        pendingSaveTaskIds.entrySet().removeIf(e -> {
            if (!e.getKey().townUuid().equals(townUuid)) return false;
            Integer id = e.getValue();
            if (id != null) Bukkit.getScheduler().cancelTask(id);
            return true;
        });

        if (toClose.isEmpty()) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (PageSession s : toClose) {
                for (UUID viewerUuid : s.viewers()) {
                    Player p = Bukkit.getPlayer(viewerUuid);
                    if (p != null && p.isOnline()) p.closeInventory();
                }
            }
        });
    }

    // ============================================================
    //   Внутреннее
    // ============================================================

    private void scheduleSave(@NotNull PageSession session) {
        Key key = new Key(session.holder().townUuid(), session.holder().page());
        Integer old = pendingSaveTaskIds.remove(key);
        if (old != null) Bukkit.getScheduler().cancelTask(old);

        int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingSaveTaskIds.remove(key);
            flushAsync(session);
        }, saveDebounceTicks).getTaskId();

        pendingSaveTaskIds.put(key, taskId);
    }

    private void cancelPendingSave(@NotNull PageSession session) {
        Key key = new Key(session.holder().townUuid(), session.holder().page());
        Integer id = pendingSaveTaskIds.remove(key);
        if (id != null) Bukkit.getScheduler().cancelTask(id);
    }

    private void flushAsync(@NotNull PageSession session) {
        if (session.hasViewers()) return;
        if (!session.isDirty()) return;

        PageHolder holder = session.holder();
        holder.capture();
        Map<Integer, byte[]> diff = holder.diff();

        if (diff.isEmpty()) {
            holder.commit();
            session.markClean();
            return;
        }

        UUID townUuid = holder.townUuid();
        int page = holder.page();

        saver.save(townUuid, page, diff).whenComplete((v, ex) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ex != null) {
                    plugin.getLogger().severe("[Sessions] save(" + townUuid + ","
                            + page + "): " + ex.getMessage());
                    return; // оставляем dirty — повтор в sweep()
                }
                holder.commit();
                session.markClean();
            });
        });
    }

    /**
     * 3.2 — синхронный flush с таймаутом. Раньше был .join() без ограничения:
     *       при зависшей БД shutdown/migrate висел бесконечно. Теперь
     *       ждём максимум 2 сек; при таймауте сессия остаётся dirty, sweep
     *       попробует снова.
     */
    private void doFlushSync(@NotNull PageSession session) {
        PageHolder holder = session.holder();
        holder.capture();
        Map<Integer, byte[]> diff = holder.diff();
        if (diff.isEmpty()) {
            holder.commit();
            session.markClean();
            return;
        }
        try {
            saver.save(holder.townUuid(), holder.page(), diff)
                    .get(FLUSH_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);     // 3.2
            holder.commit();
            session.markClean();
        } catch (TimeoutException te) {
            plugin.getLogger().warning("[Sessions] doFlushSync timeout для "
                    + holder.townUuid() + "," + holder.page()
                    + " — данные остаются dirty, попытаемся при следующем flush");
            // Оставляем dirty — при следующем sweep попробуем снова.
        } catch (Exception e) {
            plugin.getLogger().severe("[Sessions] doFlushSync: " + e.getMessage());
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();

        // 1. Retry несохранённых — с throttle, чтобы не забить io-pool
        //    и не вызвать CallerRunsPolicy в main.
        int maxPerSweep = Math.max(4, plugin.getConfigManager()
                .database().pool().ioThreads() * 4);
        int flushed = 0;
        for (PageSession s : sessions.values()) {
            if (flushed >= maxPerSweep) break;
            if (!s.hasViewers() && s.isDirty()) {
                flushAsync(s);
                flushed++;
            }
        }

        // 2. Удаляем пустые и чистые с TTL (из конфига)
        sessions.entrySet().removeIf(e -> {
            PageSession s = e.getValue();
            if (s.hasViewers()) return false;
            if (s.isDirty())    return false;
            return now - s.lastAccessMs() > sessionTtlMs;
        });
    }

    private record Key(UUID townUuid, int page) {}
}