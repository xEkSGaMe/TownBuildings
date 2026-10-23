package ru.example.cloudcore.institutes.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Данные институтов города. Иммутабельно — при изменении создаётся новый record.
 *
 * appliedChunkBonus — сколько чанков плагин уже начислил городу.
 * chunkBonusInitialized — прошла ли миграция (первый reconcile).
 */
public record TownInstituteData(
        @NotNull UUID townUuid,
        @Nullable Religion religion,
        @Nullable GovernmentForm form,
        @Nullable Ideology ideology,
        int appliedChunkBonus,
        boolean chunkBonusInitialized
) {

    /** "Пустая" запись без данных — используется как default перед загрузкой из БД. */
    public static @NotNull TownInstituteData empty(@NotNull UUID townUuid) {
        return new TownInstituteData(townUuid, null, null, null, 0, false);
    }

    public @Nullable Object get(@NotNull MenuType type) {
        return switch (type) {
            case RELIGION -> religion;
            case FORM -> form;
            case IDEOLOGY -> ideology;
        };
    }

    public @NotNull TownInstituteData with(@NotNull MenuType type, @Nullable Object value) {
        return switch (type) {
            case RELIGION -> new TownInstituteData(townUuid,
                    (Religion) value, form, ideology, appliedChunkBonus, chunkBonusInitialized);
            case FORM -> new TownInstituteData(townUuid,
                    religion, (GovernmentForm) value, ideology, appliedChunkBonus, chunkBonusInitialized);
            case IDEOLOGY -> new TownInstituteData(townUuid,
                    religion, form, (Ideology) value, appliedChunkBonus, chunkBonusInitialized);
        };
    }

    public @NotNull TownInstituteData withChunkBonus(int applied, boolean initialized) {
        return new TownInstituteData(townUuid, religion, form, ideology, applied, initialized);
    }

    public boolean isEmpty() {
        return religion == null && form == null && ideology == null;
    }
}