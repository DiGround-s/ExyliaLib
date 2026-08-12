package net.exylia.lib.util.internal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads and writes the cooldowns that are worth keeping.
 *
 * <h2>The format</h2>
 * One line per cooldown: the expiry instant in milliseconds, a space, then the
 * key.
 *
 * <pre>
 * 1763925600000 myplugin:daily-reward
 * 1763839200000 myplugin:raid-lockout
 * </pre>
 *
 * <p>Plain text rather than JSON because the data genuinely is a map of string
 * to long. A parser for that is six lines and no dependency; anything richer
 * would be structure for its own sake. The expiry comes first so the key may
 * contain spaces without needing to be quoted.
 *
 * <h2>Writing</h2>
 * Always to a temporary file that is then moved into place. A server killed
 * mid-write leaves either the old file or the new one, never half of either.
 *
 * <p>Nothing here schedules anything or knows about threads: the caller decides
 * when and on which thread these run. That is what makes it testable without a
 * server.
 *
 * @since 1.11.0
 */
public final class CooldownStore {

    private final Path directory;
    private final Logger logger;

    public CooldownStore(Path directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    /**
     * Reads what was saved for an owner.
     *
     * <p>Entries that expired while the server was down are dropped here rather
     * than loaded and swept later: they were over before anybody asked.
     *
     * @param storageId the file name, without extension
     * @param now       the current instant, so the caller's clock decides
     * @return the surviving cooldowns, empty when there is no file
     */
    public Map<String, Long> load(String storageId, long now) {
        Path file = fileFor(storageId);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, Long> loaded = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int space = line.indexOf(' ');
                if (space <= 0 || space == line.length() - 1) {
                    // A line we cannot read is skipped rather than fatal: a
                    // corrupt file must not stop a player from joining.
                    continue;
                }
                long expiry;
                try {
                    expiry = Long.parseLong(line, 0, space, 10);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (expiry > now) {
                    loaded.put(line.substring(space + 1), expiry);
                }
            }
        } catch (IOException e) {
            logger.warning("Could not read the cooldowns for " + storageId
                    + ": " + e.getMessage());
            return Map.of();
        }
        return loaded;
    }

    /**
     * Writes an owner's cooldowns, replacing whatever was there.
     *
     * <p>An empty snapshot deletes the file instead of leaving an empty one
     * behind, so a player who used up every long cooldown stops costing an
     * inode.
     *
     * @param storageId the file name, without extension
     * @param snapshot  what to write; the caller owns this map and this method
     *                  only reads it
     */
    public void save(String storageId, Map<String, Long> snapshot) {
        Path file = fileFor(storageId);
        if (snapshot.isEmpty()) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                logger.warning("Could not delete the cooldown file for " + storageId
                        + ": " + e.getMessage());
            }
            return;
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(directory);
            try (BufferedWriter writer = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, Long> entry : snapshot.entrySet()) {
                    writer.write(Long.toString(entry.getValue()));
                    writer.write(' ');
                    writer.write(entry.getKey());
                    writer.newLine();
                }
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warning("Could not save the cooldowns for " + storageId
                    + ": " + e.getMessage());
        }
    }

    /** Removes an owner's file entirely. */
    public void delete(String storageId) {
        try {
            Files.deleteIfExists(fileFor(storageId));
        } catch (IOException e) {
            logger.warning("Could not delete the cooldown file for " + storageId
                    + ": " + e.getMessage());
        }
    }

    private Path fileFor(String storageId) {
        return directory.resolve(storageId + ".cd");
    }
}
