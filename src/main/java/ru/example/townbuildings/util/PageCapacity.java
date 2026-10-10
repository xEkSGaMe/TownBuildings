package ru.example.townbuildings.util;

import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.database.model.BuildingData;

import java.util.List;

/**
 * Гигиена — единая формула числа страниц склада/хранилища.
 *
 * Раньше одинаковый {@code 1 + clamp(level, 0, 3)} был скопирован в
 * CityInventoryMenu.resolveTotalPages и CityStockMenu.resolveTotalPages
 * (плюс подразумевался в DAO-валидациях). При изменении схемы страниц
 * достаточно править одно место.
 */
public final class PageCapacity {

    /** Максимум дополнительных страниц поверх базовой. */
    public static final int MAX_EXTRA_PAGES = 3;

    private PageCapacity() {}

    /**
     * @param level уровень здания (0 = не построено → 1 страница)
     * @return число доступных страниц (минимум 1)
     */
    public static int pagesForLevel(int level) {
        return 1 + Math.max(0, Math.min(MAX_EXTRA_PAGES, level));
    }

    /** Уровень здания-склада из списка построек города (0 если отсутствует). */
    public static int warehouseLevel(@NotNull List<BuildingData> buildings,
                                     @NotNull String buildingId) {
        return buildings.stream()
                .filter(b -> b.buildingId().equals(buildingId))
                .mapToInt(BuildingData::level)
                .findFirst().orElse(0);
    }
}
