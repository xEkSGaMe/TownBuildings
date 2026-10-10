package ru.example.townbuildings.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Утилиты для работы с CompletableFuture и главным потоком Bukkit.
 */
public final class AsyncUtils {

    private AsyncUtils() {}

    /**
     * Выполнить callback в главном потоке после завершения future.
     * Если future завершился исключением — вызывается fail; иначе — ok.
     *
     * Полезно, когда результат async-операции требует обращения к Bukkit-API
     * (Player, Inventory, Event, ...), которое не thread-safe.
     */
    public static <T> void onMain(@NotNull Plugin plugin,
                                  @NotNull CompletableFuture<T> future,
                                  @NotNull Consumer<T> ok,
                                  @NotNull Consumer<Throwable> fail) {
        future.whenComplete((result, ex) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null) fail.accept(ex);
                    else            ok.accept(result);
                }));
    }

    /**
     * Упрощённый вариант — без явного fail (логирует через plugin).
     */
    public static <T> void onMain(@NotNull Plugin plugin,
                                  @NotNull CompletableFuture<T> future,
                                  @NotNull Consumer<T> ok) {
        onMain(plugin, future, ok, ex ->
                plugin.getLogger().severe("[Async] Future failed: " + ex.getMessage()));
    }
}