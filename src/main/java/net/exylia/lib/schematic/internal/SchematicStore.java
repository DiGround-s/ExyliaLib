package net.exylia.lib.schematic.internal;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where one plugin's schematics live, and which ones exist.
 *
 * <h2>Two folders</h2>
 * Written to {@code schematics/}, also read from {@code schematics/regions/}.
 * The second is where ExyliaCommons wrote them, and those files are the arenas
 * on production right now, so they are still read and can still be pasted
 * without re-saving. A name present in both resolves to the new folder, which
 * is what makes a re-save an upgrade rather than a second copy nobody reads.
 *
 * <h2>The index</h2>
 * {@code exists()} never touches the disk. Roughly twenty call sites in the
 * ecosystem are menu renders, and ExyliaCommons answered each with
 * {@code File.exists()} — one stat syscall per slot per redraw, on the thread
 * that also runs the game. The names are read once, off the main thread, and
 * kept in step by {@code save} and {@code delete}.
 */
@ApiStatus.Internal
public final class SchematicStore {

    /** The extension in both folders. */
    static final String EXTENSION = ".schem";

    /** Where this module writes. */
    static final String FOLDER = "schematics";

    /** Where ExyliaCommons wrote, and where the arenas on production are. */
    static final String LEGACY_FOLDER = "regions";

    private final File folder;
    private final File legacyFolder;

    /**
     * Every name either folder holds.
     *
     * <p>A set rather than a map to the file: which folder a name resolves to
     * can change under a re-save, and answering that at read time is one
     * comparison against a folder that is almost always the new one.
     */
    private final Set<String> names = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean indexed = new AtomicBoolean();

    SchematicStore(File dataFolder) {
        this.folder = new File(dataFolder, FOLDER);
        this.legacyFolder = new File(folder, LEGACY_FOLDER);
    }

    /** The folder this plugin's schematics are written to. */
    public @NotNull File folder() {
        return folder;
    }

    /**
     * Reads both folders once.
     *
     * <p>Called off the server threads: listing a directory is I/O, and a
     * server with a hundred arenas should not pay for it on a tick.
     */
    void seed() {
        addAll(folder);
        addAll(legacyFolder);
        indexed.set(true);
    }

    /** Whether the first listing has finished. */
    boolean isIndexed() {
        return indexed.get();
    }

    /**
     * Whether a name is known, from memory.
     *
     * <p>Answers {@code false} for everything between a plugin enabling and its
     * first listing finishing. That costs nothing real: an operation asked for
     * anyway reads the disk and answers {@code NOT_FOUND} honestly.
     */
    boolean contains(@NotNull String name) {
        return names.contains(name);
    }

    /** Every name known, as a snapshot. */
    @NotNull Set<String> names() {
        return Set.copyOf(names);
    }

    /** Records a name a save just wrote. */
    void remember(@NotNull String name) {
        names.add(name);
    }

    /** Forgets a name a delete just removed. */
    void forget(@NotNull String name) {
        names.remove(name);
    }

    /**
     * The file a name resolves to, preferring the new folder.
     *
     * <p>Reads the disk, so this is the resolution an operation does — never
     * the one {@code exists()} does.
     *
     * @param name a name already checked
     * @return the file, or {@code null} when neither folder has it
     */
    @Nullable File find(@NotNull String name) {
        File current = new File(folder, SchematicNames.fileName(name));
        if (current.isFile()) {
            return current;
        }
        File legacy = new File(legacyFolder, SchematicNames.fileName(name));
        return legacy.isFile() ? legacy : null;
    }

    /**
     * The file a save writes to, with its folder created.
     *
     * @param name a name already checked
     * @return the destination
     */
    @NotNull File destination(@NotNull String name) {
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();
        return new File(folder, SchematicNames.fileName(name));
    }

    /**
     * Deletes a name from both folders.
     *
     * <p>Both, because leaving the old copy would make a deleted arena come
     * back at the next restart — the legacy folder is still read.
     *
     * @param name a name already checked
     * @return {@code true} when at least one file was removed
     */
    boolean delete(@NotNull String name) {
        String fileName = SchematicNames.fileName(name);
        boolean removed = deleteIfPresent(new File(folder, fileName));
        // Not an else: a re-saved arena exists in both, and removing only the
        // new one would resurrect the version it replaced.
        removed |= deleteIfPresent(new File(legacyFolder, fileName));
        return removed;
    }

    private static boolean deleteIfPresent(File file) {
        return file.isFile() && file.delete();
    }

    private void addAll(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String fileName = file.getName();
            if (!fileName.endsWith(EXTENSION)) {
                continue;
            }
            String name = fileName.substring(0, fileName.length() - EXTENSION.length());
            // A file dropped in by hand can hold anything; the index must not
            // grow a name no operation would then accept.
            if (SchematicNames.isValid(name)) {
                names.add(name);
            }
        }
    }
}
