package net.exylia.lib.util.reward.internal;

import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The last items turned into snapshots and back.
 *
 * <p>Giving a player an item goes through its stored form: written to bytes,
 * Base64, then read straight back. A compressor or a mine hands out the same
 * few items all day, and each of those trips was a compressed serialisation
 * and its inflate on the main thread.
 */
final class Snapshots {

    private static final int SIZE = 128;

    private static final Map<ItemStack, String> WRITTEN = lru();
    private static final Map<String, ItemStack> READ = lru();

    private Snapshots() {
    }

    static String write(ItemStack item, Function<ItemStack, String> writer) {
        synchronized (WRITTEN) {
            String known = WRITTEN.get(item);
            if (known != null) return known;
        }
        String written = writer.apply(item);
        synchronized (WRITTEN) {
            WRITTEN.put(item.clone(), written);
        }
        return written;
    }

    /** A copy of what the snapshot reads as, so the caller may change it. */
    static ItemStack read(String snapshot, Function<String, ItemStack> reader) {
        ItemStack known;
        synchronized (READ) {
            known = READ.get(snapshot);
        }
        if (known == null) {
            known = reader.apply(snapshot);
            if (known == null) return null;
            synchronized (READ) {
                READ.put(snapshot, known);
            }
        }
        return known.clone();
    }

    private static <K, V> Map<K, V> lru() {
        return new LinkedHashMap<>(SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > SIZE;
            }
        };
    }
}
