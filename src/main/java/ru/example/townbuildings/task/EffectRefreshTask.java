package ru.example.townbuildings.task;

/**
 * Планируется вынести сюда задачу обновления постоянных эффектов
 * (сейчас внутри EffectService.start()).
 *
 * TODO (future stage): вынести refreshAll() в отдельный Runnable
 * для более гибкой настройки интервала из config.yml.
 */
public final class EffectRefreshTask {
    private EffectRefreshTask() {}
}