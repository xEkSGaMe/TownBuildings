package ru.example.cloudcore.database.dao;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * P18 — чтение «заголовка» сериализованного ItemStack без полной
 * десериализации.
 *
 * L-1 — self-check на трёх материалах: STONE (блок), DIAMOND (предмет),
 *       WRITTEN_BOOK (предмет с meta). DIAMOND одного недостаточно: если
 *       Paper изменит формат так, что DIAMOND случайно совпадёт с
 *       ожидаемым varint+float, а другой тип — нет, peekTrusted остался
 *       бы true и countAll врал бы. WRITTEN_BOOK исключает ложный позитив,
 *       когда метаданные «уехали» в начало потока.
 */
final class ItemHeader {

    private ItemHeader() {}

    private static volatile boolean peekTrusted = true;

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

    static {
        // ★ L-1 — расширенный self-check на 3 материала.
        try {
            boolean ok = true;

            org.bukkit.inventory.ItemStack probe1 =
                    new org.bukkit.inventory.ItemStack(Material.STONE, 13);
            byte[] b1 = probe1.serializeAsBytes();
            if (peekType(b1) != Material.STONE || peekAmount(b1) != 13) ok = false;

            org.bukkit.inventory.ItemStack probe2 =
                    new org.bukkit.inventory.ItemStack(Material.DIAMOND, 7);
            byte[] b2 = probe2.serializeAsBytes();
            if (peekType(b2) != Material.DIAMOND || peekAmount(b2) != 7) ok = false;

            org.bukkit.inventory.ItemStack probe3 =
                    new org.bukkit.inventory.ItemStack(Material.WRITTEN_BOOK, 1);
            byte[] b3 = probe3.serializeAsBytes();
            if (peekType(b3) != Material.WRITTEN_BOOK || peekAmount(b3) != 1) ok = false;

            if (!ok) {
                peekTrusted = false;
                org.bukkit.Bukkit.getLogger().info(
                        "[ItemHeader] fast-path peek отключён: на этой версии Paper "
                                + "формат ItemStack.serializeAsBytes() не совпадает с "
                                + "varint(material)+float(amount). countAll работает через "
                                + "полную десериализацию — это корректно, просто чуть "
                                + "медленнее. Функциональных потерь нет.");
            }
        } catch (Throwable t) {
            peekTrusted = false;
            org.bukkit.Bukkit.getLogger().info(
                    "[ItemHeader] fast-path peek отключён: " + t.getMessage()
                            + ". countAll работает через полную десериализацию.");
        }
    }

    static @Nullable Material peekType(@Nullable byte[] data) {
        if (!peekTrusted) return null;
        if (data == null || data.length < 5) return null;
        int[] pos = {0};
        int id = readVarint(data, pos);
        if (id < 0 || id >= Index.BY_ID.length) return null;
        return Index.BY_ID[id];
    }

    static int peekAmount(@Nullable byte[] data) {
        if (!peekTrusted) return 0;
        if (data == null || data.length < 9) return 0;
        int[] pos = {0};
        int id = readVarint(data, pos);
        if (id < 0) return 0;
        if (pos[0] + 4 > data.length) return 0;
        int bits = ((data[pos[0]] & 0xFF) << 24)
                | ((data[pos[0] + 1] & 0xFF) << 16)
                | ((data[pos[0] + 2] & 0xFF) << 8)
                | ((data[pos[0] + 3] & 0xFF));
        float amount = Float.intBitsToFloat(bits);
        if (!(amount > 0f)) return 0;
        return (int) amount;
    }

    private static int readVarint(@NotNull byte[] data, @NotNull int[] pos) {
        int result = 0;
        int shift = 0;
        while (pos[0] < data.length) {
            byte b = data[pos[0]++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 28) return -1;
        }
        return -1;
    }
}