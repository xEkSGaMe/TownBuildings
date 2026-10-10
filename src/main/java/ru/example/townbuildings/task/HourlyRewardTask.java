package ru.example.townbuildings.task;

/**
 * Планируется вынести сюда задачу выдачи почасовых наград
 * (сейчас — часть OnlineTimeService + RewardService).
 *
 * TODO (future stage): рефакторинг в единый таск с чистой архитектурой:
 * RewardService.checkAndGive* + отдельный scheduler.
 */
public final class HourlyRewardTask {
    private HourlyRewardTask() {}
}