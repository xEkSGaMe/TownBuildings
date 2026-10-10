package ru.example.townbuildings.database.dao;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * P18 — чтение только «заголовка» сериализованного ItemStack из
 * {@code item_data} (Bukkit ObjectStreamSerializer) без полной десериализации.
 *
 * Формат потока: varint(materialId), float(amount, 4 байта BE), int(nbtVersion), ...
 * Material по id резолвится через статический reverse-index (O(1), строится
 * лениво один раз). Meta/NBT не поднимаем вообще — для countAll нужны только
 * тип и количество.
 *
 * Безопасность: любой unexpected EOF / неизвестный id → null, вызывающий код
 * пропускает слот (как раньше пропускал air). Поведение countAll идентично
 * прежней полной десериализации для валидных данных.
 */
final class ItemHeader {

    private ItemHeader() {}

    /** materialId -> Material. Ленивая инициализация, потокобезопасно через holder. */
    private static final class Index {
        static final Material[] BY_ID = build();

        private static Material[] build() {
            Material[] all = Material.values();
            int max = 0;
            for (Material m : all) max = Math.max(max, m.ordinal());
            Material[] arr = new Material[max + 1];
            for (Material m : all) arr[m.ordinal()] = m;
            return arr;
        }
    }

    /** @return Material по id из заголовка, или null если данные битые/пустые. */
    static @Nullable Material peekType(@Nullable byte[] data) {
        if (data == null || data.length < 5) return null; // varint + минимум amount
        int[] pos = {0};
        int id = readVarint(data, pos);
        if (id < 0 || id >= Index.BY_ID.length) return null;
        return Index.BY_ID[id];
    }

    /**
     * @return amount из заголовка (float -> int trunc, как getAmount у ItemStack);
     *         0 если данные битые.
     */
    static int peekAmount(@Nullable byte[] data) {
        if (data == null || data.length < 9) return 0;
        int[] pos = {0};
        int id = readVarint(data, pos);
        if (id < 0) return 0;
        // varint мог занять до 5 байт: убедимся, что 4 байта float влезают.
        if (pos[0] + 4 > data.length) return 0;
        int bits = ((data[pos[0]] & 0xFF) << 24)
                | ((data[pos[0] + 1] & 0xFF) << 16)
                | ((data[pos[0] + 2] & 0xFF) << 8)
                | ((data[pos[0] + 3] & 0xFF));
        float amount = Float.intBitsToFloat(bits);
        if (!(amount > 0f)) return 0;
        return (int) amount;
    }

    /** LEB128 unsigned varint; -1 при выходе за границы массива. */
    private static int readVarint(@NotNull byte[] data, @NotNull int[] pos) {
        int result = 0;
        int shift = 0;
        while (pos[0] < data.length) {
            byte b = data[pos[0]++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 28) return -1; // мусорный varint
        }
        return -1;
    }
}
