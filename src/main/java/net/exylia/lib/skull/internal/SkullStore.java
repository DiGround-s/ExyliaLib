package net.exylia.lib.skull.internal;

import com.github.benmanes.caffeine.cache.Cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps textures across restarts.
 *
 * <p>Without this, the first menu opened after every reboot is a burst of HTTP
 * to Mojang for heads the server has already fetched a hundred times. With it,
 * that menu is instant and the network is untouched.
 *
 * <h2>Why a text file and not JSON or a config</h2>
 * One line per entry, {@code key<tab>epochMillis<tab>texture}. The file is
 * machine-written and machine-read, holds tens of thousands of long base64
 * strings, and is never edited by hand. A JSON document of that size costs
 * real time to parse at startup and buys nothing; the record-based config
 * module is for settings a human reads, which this is not.
 *
 * <p>Reads and writes happen off the main thread, once each per lifetime.
 */
public final class SkullStore {

    /** How long a stored texture stays good. Skins change, but rarely. */
    private static final long TTL_MILLIS = java.time.Duration.ofDays(14).toMillis();

    /** A ceiling, so an old server does not carry a file that grows forever. */
    private static final int MAX_ENTRIES = 20_000;

    private static final char SEPARATOR = '\t';

    private final Path file;
    private final Logger logger;

    /** What is known to be on disk, plus what has been learned since. */
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private volatile boolean dirty;

    public SkullStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    private record Entry(long storedAt, String texture) {
    }

    /**
     * Reads the file into the given cache.
     *
     * <p>Expired and malformed lines are dropped rather than repaired: a bad
     * line costs one head one lookup, and trying to fix it costs every start
     * the attempt.
     *
     * @param textures the cache to populate
     */
    public void load(Cache<String, String> textures) {
        if (file == null || !Files.exists(file)) {
            return;
        }
        long now = System.currentTimeMillis();
        int loaded = 0;
        int dropped = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int first = line.indexOf(SEPARATOR);
                int second = first < 0 ? -1 : line.indexOf(SEPARATOR, first + 1);
                if (second < 0) {
                    dropped++;
                    continue;
                }
                String key = line.substring(0, first);
                String texture = line.substring(second + 1);
                long storedAt;
                try {
                    storedAt = Long.parseLong(line.substring(first + 1, second));
                } catch (NumberFormatException malformed) {
                    dropped++;
                    continue;
                }
                if (now - storedAt > TTL_MILLIS || !Textures.isValid(texture)) {
                    dropped++;
                    continue;
                }
                entries.put(key, new Entry(storedAt, texture));
                textures.put(key, texture);
                loaded++;
            }
            if (dropped > 0) {
                // The file is rewritten on shutdown anyway; mark it so the
                // dead lines actually go away.
                dirty = true;
            }
            if (loaded > 0) {
                logger.log(Level.INFO, "Skulls: {0} textures restored from disk.", loaded);
            }
        } catch (IOException unreadable) {
            logger.log(Level.WARNING, "Skulls: could not read the texture cache; "
                    + "heads will be fetched again as they are needed.", unreadable);
        }
    }

    /** Records a texture, to be written on shutdown. */
    public void remember(String key, String texture) {
        entries.put(key, new Entry(System.currentTimeMillis(), texture));
        dirty = true;
    }

    /** Drops one entry. */
    public void forget(String key) {
        if (entries.remove(key) != null) {
            dirty = true;
        }
    }

    /** Drops everything, including the file. */
    public void clear() {
        entries.clear();
        dirty = true;
    }

    /**
     * Writes the file, if anything changed.
     *
     * <p>Through a temporary file and an atomic move: a server killed halfway
     * through a write would otherwise leave a truncated cache that the next
     * start has to throw away.
     *
     * @param textures the live cache, whose contents win over stale entries
     */
    public void save(Cache<String, String> textures) {
        if (!dirty || file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>(Math.min(entries.size(), MAX_ENTRIES));
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                if (lines.size() >= MAX_ENTRIES) {
                    break;
                }
                Entry value = entry.getValue();
                if (now - value.storedAt() > TTL_MILLIS) {
                    continue;
                }
                lines.add(entry.getKey() + SEPARATOR + value.storedAt()
                        + SEPARATOR + value.texture());
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException unwritable) {
            logger.log(Level.WARNING, "Skulls: could not write the texture cache; "
                    + "the next start will fetch heads again.", unwritable);
        }
    }

    /** How many entries are held. */
    public int size() {
        return entries.size();
    }
}
