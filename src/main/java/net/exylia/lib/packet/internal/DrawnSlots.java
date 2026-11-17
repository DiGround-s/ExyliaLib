package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.protocol.item.ItemStack;
import net.exylia.lib.packet.ItemPlace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * What each slot was last drawn as, per viewer, so an inventory sent again with the same items (a kit, a
 * menu refresh, a resync) reuses its decorated copies instead of converting every item and asking every
 * provider again.
 *
 * <p>An entry is reused only for an equal item in the same slot, place and icon limits, and only for a few
 * seconds, so lines that change with time still catch up the next time the slot is sent.
 */
final class DrawnSlots {

    // ponytail: fixed reuse window; lines that change faster than this lag by up to it.
    private static final long REUSE_MS = 3_000;

    private record Drawn(ItemStack input, ItemPlace place, @Nullable IconDowngrade.Limits limits,
                         @Nullable ItemStack output, long at) {
    }

    private static final Map<UUID, Map<Long, Drawn>> DRAWN = new ConcurrentHashMap<>();

    private DrawnSlots() {
    }

    /** The slot's decorated copy, reused when the same item was drawn there moments ago, or {@code null} for none. */
    static @Nullable ItemStack drawn(@NotNull UUID viewer, int window, int slot, @Nullable ItemStack item,
                                     @NotNull ItemPlace place, @Nullable IconDowngrade.Limits limits,
                                     @NotNull Supplier<ItemStack> draw) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        long key = ((long) window << 32) | (slot & 0xFFFFFFFFL);
        Map<Long, Drawn> mine = DRAWN.computeIfAbsent(viewer, id -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        Drawn last = mine.get(key);
        if (last != null && now - last.at() < REUSE_MS && last.place().equals(place)
                && Objects.equals(last.limits(), limits) && last.input().equals(item)) {
            return last.output();
        }
        ItemStack output = draw.get();
        mine.put(key, new Drawn(item, place, limits, output, now));
        return output;
    }

    /** Forgets one viewer's slots, or everyone's for {@code null} (the providers changed). */
    static void forget(@Nullable UUID viewer) {
        if (viewer == null) {
            DRAWN.clear();
        } else {
            DRAWN.remove(viewer);
        }
    }
}
