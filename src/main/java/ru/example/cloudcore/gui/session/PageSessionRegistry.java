package ru.example.cloudcore.gui.session;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.gui.holder.PageHolder;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import ru.example.cloudcore.CloudCorePlugin;

import java.util.ArrayList;
import java.util.List;

import ru.example.cloudcore.CloudCorePlugin;
/**
 * Реестр сессий страниц.
 *
 * H1 — lockTown/unlockTown: на время мутации (апгрейд/админ-операции)
 *      город блокируется: новые сессии отклоняются с PageSessionBusyException,
 *      текущие flush'атся и сбрасываются атомарно (flush+invalidate без
 *      окна между ними).
 * H3 — populate-фаза getOrCreate обёрнута в try/catch: если onLoaded
 *      бросит, readyFuture всё равно разрешится (failReady), сессия
 *      удаляется из реестра — никаких зависших future.
 * H5 — sweep() принудительно evict'ит dirty-сессию после MAX_FLUSH_FAILURES
 *      подряд идущих неудачных flush'ей (иначе «вечно dirty» залипает).
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

    /** P7 (review) — grace-период после инвалидации города. */
    private static final long INVALIDATED_GRACE_MS = 1500L;

    /** ★ H5 — сколько подряд неудачных flush'ей терпим до force-drop. */
    private static final int MAX_FLUSH_FAILURES = 5;

    private final CloudCorePlugin plugin;
    private final Loader loader;
    private final Saver saver;
    private final HolderFactory holderFactory;
    private final TitleFactory titleFactory;

    private volatile long saveDebounceTicks;
    private volatile long sessionTtlMs;

    private final Map<Key, PageSession> sessions = new ConcurrentHashMap<>();
    private final Map<Key, Integer> pendingSaveTaskIds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> recentlyInvalidated = new ConcurrentHashMap<>();

    /** ★ H1 — города, заблокированные на время мутации (апгрейд/админ-операции). */
    private final Set<UUID> lockedTowns = ConcurrentHashMap.newKeySet();

    private ScheduledTask sweepTask;

    public PageSessionRegistry(@NotNull CloudCorePlugin plugin,
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

        this.sweepTask = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, t -> sweep(), 20L * 60, 20L * 60);
    }

    public void stopSweep() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    public void dropAllSessions() {
        for (PageSession s : sessions.values()) s.forceEndFlush();
        sessions.clear();
        recentlyInvalidated.clear();
        lockedTowns.clear();
    }

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

    /**
     * ★ H1 — заблокировать город: новые сессии отклоняются, текущие
     * flush'атся и сбрасываются (flush+invalidate без окна).
     */
    public void lockTown(@NotNull UUID townUuid) {
        lockedTowns.add(townUuid);
        flushTownSync(townUuid);
        doInvalidateTown(townUuid);
    }

    /** ★ H1 — снять блокировку: reopen получит свежий снапшот из БД. */
    public void unlockTown(@NotNull UUID townUuid) {
        lockedTowns.remove(townUuid);
    }

    public @NotNull CompletableFuture<PageSession> getOrCreate(
            @NotNull UUID townUuid, int page, int totalPages, @NotNull String townName,
            @NotNull BiConsumer<PageSession, Map<Integer, byte[]>> onLoaded) {

        Key key = new Key(townUuid, page);
        PageSession existing = sessions.get(key);
        if (existing != null) return existing.readyFuture();

        // ★ H1 — во время мутации новые сессии не создаём.
        if (lockedTowns.contains(townUuid)) {
            CompletableFuture<PageSession> refused = new CompletableFuture<>();
            refused.completeExceptionally(new PageSessionBusyException(
                    "Город заблокирован на время операции"));
            return refused;
        }

        // P7 (review) — grace-период после invalidateTown.
        Long invalidatedAt = recentlyInvalidated.get(townUuid);
        if (invalidatedAt != null
                && System.currentTimeMillis() - invalidatedAt < INVALIDATED_GRACE_MS) {
            CompletableFuture<PageSession> refused = new CompletableFuture<>();
            refused.completeExceptionally(new PageSessionBusyException(
                    "Сессия временно недоступна (город недавно изменился)"));
            return refused;
        }

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
                // ★ H3 — populate-фаза обёрнута: если onLoaded бросит,
                // readyFuture всё равно разрешится (failReady).
                try {
                    onLoaded.accept(fresh, items);
                    fresh.completeReady();
                } catch (Throwable t) {
                    plugin.getLogger().severe("[Sessions] populate(" + townUuid + ","
                            + page + ") упал: " + t);
                    sessions.remove(key, fresh);
                    fresh.failReady(t);
                }
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

    public void flushTownSync(@NotNull UUID townUuid) {
        for (PageSession s : sessions.values()) {
            if (!s.holder().townUuid().equals(townUuid)) continue;
            CompletableFuture<Void> inflight = s.inFlightFlush();
            if (inflight != null) {
                try {
                    inflight.get(FLUSH_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    plugin.getLogger().warning("[Sessions] flushTownSync: ожидание "
                            + "in-flight flush(" + townUuid + "," + s.holder().page()
                            + ") — timeout, данные всё ещё в полёте");
                } catch (Exception ignored) {
                    // провалившийся flush оставляет dirty — doFlushSync ниже повторит
                }
                if (!s.isDirty()) continue;
            }
            if (!s.isDirty()) continue;
            try {
                doFlushSync(s);
            } catch (Throwable t) {
                plugin.getLogger().severe("[Sessions] flushTownSync(" + townUuid
                        + "," + s.holder().page() + "): " + t.getMessage());
            }
        }
    }

    public @NotNull CompletableFuture<Void> flushTownAsync(@NotNull UUID townUuid) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (PageSession s : sessions.values()) {
            if (!s.holder().townUuid().equals(townUuid)) continue;
            CompletableFuture<Void> inflight = s.inFlightFlush();
            if (inflight != null) {
                futures.add(inflight.exceptionally(t -> null));
            } else if (s.isDirty()) {
                futures.add(doFlushAsync(s));
            }
        }
        if (futures.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private @NotNull CompletableFuture<Void> doFlushAsync(@NotNull PageSession session) {
        CompletableFuture<Void> done = new CompletableFuture<>();

        if (!session.beginFlushIfFree(done)) {
            CompletableFuture<Void> inflight = session.inFlightFlush();
            Runnable retry = () -> Bukkit.getGlobalRegionScheduler()
                    .run(plugin, t -> doFlushAsync0(session, done));
            if (inflight != null) inflight.whenComplete((v, ex) -> retry.run());
            else retry.run();
            return done;
        }

        startFlushBody(session, done);
        return done;
    }

    private void doFlushAsync0(@NotNull PageSession session,
                               @NotNull CompletableFuture<Void> done) {
        if (!session.isDirty()) {
            CompletableFuture<Void> inflight = session.inFlightFlush();
            if (inflight == null) {
                done.complete(null);
            } else {
                inflight.whenComplete((v, ex) ->
                        Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                            if (ex != null) done.completeExceptionally(ex);
                            else            done.complete(null);
                        }));
            }
            return;
        }
        if (!session.beginFlushIfFree(done)) {
            CompletableFuture<Void> inflight = session.inFlightFlush();
            Runnable retry = () -> Bukkit.getGlobalRegionScheduler()
                    .run(plugin, t -> doFlushAsync0(session, done));
            if (inflight != null) inflight.whenComplete((v, ex) -> retry.run());
            else retry.run();
            return;
        }
        startFlushBody(session, done);
    }

    private void startFlushBody(@NotNull PageSession session,
                                @NotNull CompletableFuture<Void> done) {
        PageHolder holder = session.holder();
        holder.capture();
        Map<Integer, byte[]> diff = holder.diff();
        if (diff.isEmpty()) {
            holder.commit();
            session.markClean();
            session.onFlushSuccess();   // ★ H5
            session.endFlush(done);
            done.complete(null);
            return;
        }

        UUID townUuid = holder.townUuid();
        int page = holder.page();

        saver.save(townUuid, page, diff).whenComplete((v, ex) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null) {
                        plugin.getLogger().severe("[Sessions] flush async(" + townUuid
                                + "," + page + "): " + ex.getMessage());
                        // ★ H5 — учитываем неудачу, dirty остаётся.
                        session.onFlushFailure();
                        session.endFlush(done);
                        done.completeExceptionally(ex);
                        return;
                    }
                    // ★ H5 — успех сбрасывает счётчик.
                    session.onFlushSuccess();
                    holder.commit();
                    holder.capture();
                    if (holder.diff().isEmpty()) {
                        session.markClean();
                    } else {
                        session.markDirty();
                    }

                    session.endFlush(done);
                    done.complete(null);
                }));
    }

    public void drop(@NotNull PageSession session) {
        sessions.remove(new Key(session.holder().townUuid(), session.holder().page()), session);
        session.forceEndFlush();
    }

    public void invalidateTown(@NotNull UUID townUuid) {
        recentlyInvalidated.put(townUuid, System.currentTimeMillis());
        flushTownSync(townUuid);
        doInvalidateTown(townUuid);
    }

    public void invalidateTownAsync(@NotNull UUID townUuid) {
        recentlyInvalidated.put(townUuid, System.currentTimeMillis());
        flushTownAsync(townUuid).whenComplete((v, ex) ->
                Bukkit.getScheduler().runTask(plugin, () -> doInvalidateTown(townUuid)));
    }

    private void doInvalidateTown(@NotNull UUID townUuid) {
        List<PageSession> toClose = new ArrayList<>();

        sessions.entrySet().removeIf(e -> {
            if (!e.getKey().townUuid().equals(townUuid)) return false;
            PageSession s = e.getValue();
            s.forceEndFlush();
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
        doFlushAsync(session);
    }

    private void doFlushSync(@NotNull PageSession session) {
        PageHolder holder = session.holder();
        holder.capture();
        Map<Integer, byte[]> diff = holder.diff();
        if (diff.isEmpty()) {
            holder.commit();
            session.markClean();
            session.onFlushSuccess();
            return;
        }
        try {
            saver.save(holder.townUuid(), holder.page(), diff)
                    .get(FLUSH_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            holder.commit();
            session.markClean();
            session.onFlushSuccess();
        } catch (TimeoutException te) {
            session.onFlushFailure();
            plugin.getLogger().warning("[Sessions] doFlushSync timeout для "
                    + holder.townUuid() + "," + holder.page()
                    + " — данные остаются dirty, попытаемся при следующем flush");
        } catch (Exception e) {
            session.onFlushFailure();
            plugin.getLogger().severe("[Sessions] doFlushSync: " + e.getMessage());
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();

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

        sessions.entrySet().removeIf(e -> {
            PageSession s = e.getValue();
            if (s.hasViewers()) return false;

            // ★ H5 — dirty, но flush много раз подряд падал → принудительный drop,
            // иначе сессия «залипнет» в реестре навсегда.
            if (s.isDirty() && s.flushFailures() >= MAX_FLUSH_FAILURES) {
                plugin.getLogger().warning("[Sessions] force-drop dirty session "
                        + s.holder().townUuid() + "," + s.holder().page()
                        + " после " + s.flushFailures() + " неудачных flush'ей — "
                        + "часть изменений потеряна");
                s.forceEndFlush();
                return true;
            }

            if (s.isDirty())    return false;
            if (now - s.lastAccessMs() <= sessionTtlMs) return false;
            s.forceEndFlush();
            return true;
        });

        long graceCutoff = INVALIDATED_GRACE_MS * 4;
        recentlyInvalidated.entrySet().removeIf(e ->
                now - e.getValue() > graceCutoff);
    }

    private record Key(UUID townUuid, int page) {}
}