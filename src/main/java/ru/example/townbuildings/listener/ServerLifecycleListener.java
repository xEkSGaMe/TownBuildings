package ru.example.townbuildings.listener;

/**
 * Планируется вынести сюда обработку ServerLoadEvent/ServerUnloadEvent —
 * например, принудительную регидратацию кэша городов, проверку БД после плагинов
 * с поздней инициализацией (Vault, LuckPerms).
 *
 * TODO (future stage): при перезагрузке плагинов /reload — валидация состояния БД и кэша.
 */
public final class ServerLifecycleListener {
    private ServerLifecycleListener() {}
}