package ru.example.townbuildings.gui.session;

import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.gui.holder.PageHolder;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Одна "сессия" на (townUuid, page).
 * Хранит общий Inventory, holder, список зрителей, флаг dirty и ready-future.
 */
public final class PageSession {

    private final PageHolder holder;
    private final Inventory inventory;
    private final CompletableFuture<PageSession> ready = new CompletableFuture<>();

    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    private volatile boolean dirty = false;
    private volatile long lastAccessMs = System.currentTimeMillis();

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

    public void completeReady()               { ready.complete(this); }
    public void failReady(Throwable t)        { ready.completeExceptionally(t); }
    public @NotNull CompletableFuture<PageSession> readyFuture() { return ready; }
}