package ru.example.townbuildings.task;

/**
 * Планируется вынести сюда тик онлайна (сейчас OnlineTimeService.tick()
 * регистрируется внутри start()).
 *
 * TODO (future stage): вынести периодический tick в отдельный класс
 * для более понятного жизненного цикла.
 */
public final class OnlineTickTask {
    private OnlineTickTask() {}
}