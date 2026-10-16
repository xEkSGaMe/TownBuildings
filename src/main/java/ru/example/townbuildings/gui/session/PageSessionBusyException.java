package ru.example.townbuildings.gui.session;

/**
 * ★ C2 — специализированное исключение для grace-периода после
 * invalidateTown. Caller'ы (CityInventoryMenu, CityStockMenu) по нему
 * отличают «сессия временно недоступна» от реальных ошибок БД и
 * делают retry вместо «Внутренней ошибки».
 */
public final class PageSessionBusyException extends RuntimeException {
    public PageSessionBusyException(String message) {
        super(message);
    }
}