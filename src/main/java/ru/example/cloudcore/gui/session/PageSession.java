package ru.example.cloudcore.gui.session;

import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.example.cloudcore.gui.holder.PageHolder;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Одна "сессия" на (townUuid, page).
 * Хранит общий Inventory, holder, список зрителей, флаг dirty и ready-future.
 *
 * H5 — счётчик подряд идущих неудачных flush'ей: sweep() принудительно
 *      evict'ит сессию после MAX_FLUSH_FAILURES, чтобы «вечно dirty»
 *      сессия не залипла в реестре навсегда.
 */
public final class PageSession {

    private final PageHolder holder;
    private final Inventory inventory;
    private final CompletableFuture<PageSession> ready = new CompletableFuture<>();

    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    private volatile boolean dirty = false;
    private volatile long lastAccessMs = System.currentTimeMillis();

    /**
     * P8 — сериализация flush'ей одной сессии: в полёте может быть только
     * один save-запрос.
     */
    private final AtomicReference<CompletableFuture<Void>> inFlightFlush =
            new AtomicReference<>(null);

    /** ★ H5 — счётчик подряд идущих неудачных flush'ей. */
    private final AtomicInteger flushFailures = new AtomicInteger(0);

    public PageSession(@NotNull PageHolder holder, @NotNull Inventory inventory) {
        this.holder = holder;
        this.inventory = inventory;
    }

    public @NotNull PageHolder holder()      { return holder; }
    public @NotNull Inventory inventory()    { return inventory; }
    public @NotNull Set<UUID> viewers()      { return viewers; }

    public boolean isDirty()                 { return dirty; }
    public void markDirty()                  { dirty = true; }
    public void markClean()                  { dirty = false; }

    public void touch()                      { lastAccessMs = System.currentTimeMillis(); }
    public long lastAccessMs()               { return lastAccessMs; }

    public void addViewer(@NotNull UUID uuid)    { viewers.add(uuid); touch(); }
    public void removeViewer(@NotNull UUID uuid) { viewers.remove(uuid); touch(); }
    public boolean hasViewers()                  { return !viewers.isEmpty(); }

    /** ★ H5 — сколько flush'ей подряд упало. */
    public int flushFailures()               { return flushFailures.get(); }
    /** ★ H5 — отметить неудачный flush. */
    public void onFlushFailure()             { flushFailures.incrementAndGet(); }
    /** ★ H5 — успех сбрасывает счётчик. */
    public void onFlushSuccess()             { flushFailures.set(0); }

    /** P8 — текущий незавершённый flush этой сессии (или null). */
    public @Nullable CompletableFuture<Void> inFlightFlush() {
        return inFlightFlush.get();
    }

    /** P8 — попытаться зарегистрировать свой flush; false, если другой уже в полёте. */
    public boolean beginFlushIfFree(@NotNull CompletableFuture<Void> future) {
        return inFlightFlush.compareAndSet(null, future);
    }

    /** P8 — снять регистрацию (в т.ч. при отложенном старте). */
    public void endFlush(@NotNull CompletableFuture<Void> future) {
        inFlightFlush.compareAndSet(future, null);
    }

    /**
     * P9 — сбросить регистрацию флша принудительно, независимо от того,
     * какой future сейчас в полёте.
     */
    public void forceEndFlush() {
        inFlightFlush.set(null);
    }

    public void completeReady()               { ready.complete(this); }
    public void failReady(Throwable t)        { ready.completeExceptionally(t); }
    public @NotNull CompletableFuture<PageSession> readyFuture() { return ready; }
}