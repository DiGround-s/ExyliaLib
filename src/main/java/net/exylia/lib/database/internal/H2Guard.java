package net.exylia.lib.database.internal;

import net.exylia.lib.database.DatabaseException;
import org.h2.mvstore.MVStoreException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Keeps an embedded H2 file readable: a full-read health check before the
 * database is handed out, an automatic repair when that check finds it
 * damaged, and rotating online backups once it is known to be good.
 *
 * <h2>Why a full read</h2>
 * A crash in the middle of a write (an {@code OutOfMemoryError}, a killed
 * process) can leave the newest MVStore chunk torn. H2 opens such a file
 * without complaint and even answers {@code SELECT COUNT(*)}; only the first
 * read of a page in the torn chunk fails, and that failure closes the whole
 * database for the rest of the run. So the check reads every row of every
 * table, and it does so on a read-only connection, before the pool opens and
 * before any plugin writes, so a damaged file is never written to.
 *
 * <h2>The repair, in order</h2>
 * <ol>
 *   <li>The damaged file is copied to {@code corrupted/<time>/} first. It is
 *       never deleted.</li>
 *   <li>Roll back the last unfinished write: on a copy, the newest chunk is
 *       zeroed, which makes H2 fall back to the previous complete version (up
 *       to {@value #CHUNKS_TO_TRY} chunks). The survivor is dumped and loaded
 *       into a fresh file, so nothing torn is carried over.</li>
 *   <li>Otherwise the newest backup that restores and passes the check.</li>
 *   <li>Otherwise nothing: the file stays where it was and the database stays
 *       unavailable. An empty database is never created in its place, because
 *       a plugin would seed new rows (start keys, defaults) over the lost
 *       ones.</li>
 * </ol>
 */
public final class H2Guard {

    private static final int BLOCK = 4096;
    private static final int CHUNKS_TO_TRY = 3;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** MVStore errors that mean the bytes on disk are wrong, not that the file is busy or missing. */
    private static final Set<Integer> CORRUPT_STORE = Set.of(3, 6, 8, 9, 50, 100, 106);
    private static final int FILE_CORRUPTED = 90030;
    private static final int GENERAL_ERROR = 50000;
    private static final int DATABASE_IS_CLOSED = 90098;

    /** Files this run already failed to repair, with their size, so a retry fails fast instead of repeating it. */
    private static final Map<Path, Long> GIVEN_UP = new ConcurrentHashMap<>();

    /** The cause of a refusal that was already reported in full this run. */
    private static final Throwable ALREADY_REPORTED = new Throwable("already reported", null, false, false) { };

    private H2Guard() {
        throw new AssertionError("No instances.");
    }

    /** Whether these settings name an embedded H2 file, the only thing this class looks after. */
    public static boolean applies(@NotNull SqlSettings settings) {
        return settings.file() != null && settings.engine().trim().equalsIgnoreCase("h2");
    }

    /**
     * Checks the file and repairs it when it is damaged. Called before the pool opens.
     *
     * @param settings the database
     * @param log      where to report, by level
     * @throws DatabaseException when the file is damaged and could not be repaired
     */
    public static void prepare(@NotNull SqlSettings settings, @NotNull BiConsumer<Level, String> log) {
        Path base = settings.file().toAbsolutePath().normalize();
        Path db = mvFile(base);
        if (!Files.exists(db)) {
            return;
        }
        Long failedSize = GIVEN_UP.get(db);
        if (failedSize != null && failedSize == size(db)) {
            DatabaseException again = unavailable(db, base);
            again.initCause(ALREADY_REPORTED);
            throw again;
        }
        Throwable failure = check(base, settings);
        if (failure == null) {
            return;
        }
        if (!isCorruption(failure)) {
            // Locked by another process, an old file format, a wrong password:
            // the pool reports those itself, with the engine's own words.
            return;
        }
        log.accept(Level.SEVERE, "The database file " + db + " is damaged (" + failure.getMessage()
                + "). This usually means the server stopped in the middle of a write (out of memory,"
                + " killed process). Repairing it now; this plugin's data stays offline until it is done.");
        try {
            repair(settings, base, db, log);
        } catch (DatabaseException givenUp) {
            throw givenUp;
        } catch (IOException | SQLException | RuntimeException broken) {
            GIVEN_UP.put(db, size(db));
            log.accept(Level.SEVERE, "The repair itself failed: " + broken);
            throw unavailable(db, base);
        }
    }

    private static void repair(SqlSettings settings, Path base, Path db, BiConsumer<Level, String> log)
            throws IOException, SQLException {
        Path dir = base.getParent();
        String name = base.getFileName().toString();
        Path kept = quarantine(dir, db);
        // COUNT(*) still answers on a torn file: it is the yardstick for what a repair lost.
        Map<String, Long> before = counts(base(kept), settings);
        Path work = Files.createDirectories(dir.resolve("repair"));
        Path workBase = work.resolve(name);
        Path chosen = null;
        Map<String, Long> chosenCounts = null;
        String how = null;
        try {
            // A: the previous complete version on disk, which normally loses only the write that was torn.
            List<long[]> chunks = chunks(kept);
            for (int zeroed = 1; zeroed <= Math.min(CHUNKS_TO_TRY, chunks.size() - 1); zeroed++) {
                Files.copy(kept, mvFile(workBase), StandardCopyOption.REPLACE_EXISTING);
                zero(mvFile(workBase), chunks.subList(0, zeroed));
                if (check(workBase, settings) != null) {
                    continue;
                }
                Path rebuiltBase = work.resolve(name + "-rebuilt");
                chosen = rebuild(workBase, rebuiltBase, settings);
                chosenCounts = counts(rebuiltBase, settings);
                how = "rolling back the last " + (zeroed == 1 ? "unfinished write" : zeroed + " unfinished writes");
                break;
            }
            // B: the newest readable backup, when the rollback lost rows or did not work at all.
            if (chosen == null || before == null || !before.equals(chosenCounts)) {
                for (Path backup : backups(dir, name)) {
                    Path restored = restore(backup, workBase);
                    if (restored == null || check(workBase, settings) != null) {
                        continue;
                    }
                    Map<String, Long> counts = counts(workBase, settings);
                    if (chosen == null || total(counts) > total(chosenCounts)) {
                        chosen = restored;
                        chosenCounts = counts;
                        how = "restoring the backup " + backup.getFileName() + " (taken "
                                + Files.getLastModifiedTime(backup) + "); every change made after it is LOST";
                    }
                    break;
                }
            }
            if (chosen != null) {
                install(chosen, base, dir);
                log.accept(Level.SEVERE, "Repaired " + db + " by " + how + ". " + loss(before, chosenCounts)
                        + " The damaged file is kept in " + kept.getParent() + ".");
                return;
            }
        } finally {
            deleteTree(work);
        }
        // C: nothing usable. The damaged file stays where it is, so nothing opens an empty one.
        GIVEN_UP.put(db, size(db));
        throw unavailable(db, base);
    }

    /** What a repair lost, table by table, against the counts the damaged file reported. */
    private static String loss(@Nullable Map<String, Long> before, @Nullable Map<String, Long> after) {
        if (before == null || after == null) {
            return "How many rows were lost could not be measured: the damaged file did not report its row counts.";
        }
        List<String> lost = new ArrayList<>();
        before.forEach((table, rows) -> {
            long now = after.getOrDefault(table, 0L);
            if (now < rows) {
                lost.add(table + " " + rows + " -> " + now);
            }
        });
        return lost.isEmpty()
                ? "Every table has as many rows as before the crash; only what was being saved at that moment is lost."
                : "Some rows are LOST (rows before the crash -> now): " + String.join(", ", lost) + ".";
    }

    /** Rows per table, or {@code null} when the file does not answer. Counts come from the tree roots, so a torn file answers too. */
    private static @Nullable Map<String, Long> counts(Path base, SqlSettings settings) {
        try (Connection connection = connect("jdbc:h2:file:" + base + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE", settings);
             Statement statement = connection.createStatement()) {
            try {
                Map<String, Long> counts = new java.util.TreeMap<>();
                for (String table : tables(connection)) {
                    try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        rows.next();
                        counts.put(table, rows.getLong(1));
                    }
                }
                return counts;
            } finally {
                // Never left registered in this JVM: see check().
                statement.execute("SHUTDOWN IMMEDIATELY");
            }
        } catch (SQLException | RuntimeException unreadable) {
            return null;
        }
    }

    private static long total(@Nullable Map<String, Long> counts) {
        return counts == null ? -1L : counts.values().stream().mapToLong(Long::longValue).sum();
    }

    private static Path base(Path mvFile) {
        String file = mvFile.getFileName().toString();
        return mvFile.resolveSibling(file.substring(0, file.length() - ".mv.db".length()));
    }

    /** Whether a failure is a repeat of a refusal already reported in full, which needs no new line. */
    public static boolean repeated(@Nullable Throwable failure) {
        return failure != null && failure.getCause() == ALREADY_REPORTED;
    }

    // ---------------------------------------------------------------- check

    /** Reads every row of every table on a read-only connection; the failure, or {@code null} when all of it read. */
    static @Nullable Throwable check(@NotNull Path base, @NotNull SqlSettings settings) {
        try (Connection connection = connect("jdbc:h2:file:" + base + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE", settings)) {
            try {
                readEverything(connection);
                return null;
            } catch (SQLException | RuntimeException failure) {
                // H2 keeps a database that failed a read registered in this JVM,
                // still holding the old file: the next connection to the same
                // path, the pool's included, would get it back instead of what
                // the repair put there. Only a shutdown forgets it.
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SHUTDOWN IMMEDIATELY");
                } catch (SQLException alreadyClosed) {
                    // Closed by H2 itself, which is what this was for.
                }
                return failure;
            }
        } catch (SQLException | RuntimeException failure) {
            return failure;
        }
    }

    /** Streams every row of every table and discards it. */
    static void readEverything(@NotNull Connection connection) throws SQLException {
        for (String table : tables(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.setFetchSize(1000);
                try (ResultSet rows = statement.executeQuery("SELECT * FROM " + table)) {
                    int columns = rows.getMetaData().getColumnCount();
                    while (rows.next()) {
                        for (int column = 1; column <= columns; column++) {
                            rows.getObject(column);
                        }
                    }
                }
            }
        }
    }

    /** Every table, schema-qualified and quoted. */
    private static List<String> tables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT TABLE_SCHEMA, TABLE_NAME"
                     + " FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'BASE TABLE'"
                     + " AND TABLE_SCHEMA <> 'INFORMATION_SCHEMA'")) {
            while (rows.next()) {
                tables.add(quote(rows.getString(1)) + "." + quote(rows.getString(2)));
            }
        }
        return tables;
    }

    /**
     * Whether a failure means the file's bytes are wrong.
     *
     * <p>Narrow on purpose: a busy, locked, missing or newer-format file, and
     * every ordinary SQL error, is not corruption, and repairing one of those
     * would move a perfectly good file aside.
     */
    public static boolean isCorruption(@Nullable Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MVStoreException store && CORRUPT_STORE.contains(store.getErrorCode())) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getErrorCode() == FILE_CORRUPTED) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getErrorCode() == GENERAL_ERROR
                    && String.valueOf(sql.getMessage()).contains("File corrupted")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /** Whether a failure at run time means the file is damaged or H2 already closed it over damage. */
    public static boolean isDamage(@Nullable Throwable failure) {
        if (isCorruption(failure)) {
            return true;
        }
        for (Throwable cause = failure; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == DATABASE_IS_CLOSED) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------------------- backups

    /**
     * Takes one online backup and rotates the old ones out, after checking
     * that the live database still reads in full. A database that fails the
     * check is not backed up: its copy would push a good backup out.
     *
     * @param backend the open database
     * @param settings its settings
     * @return the backup written
     * @throws SQLException when the check or the backup failed
     */
    public static @NotNull Path backup(@NotNull SqlBackend backend, @NotNull SqlSettings settings)
            throws SQLException, IOException {
        Path base = settings.file().toAbsolutePath().normalize();
        Path dir = Files.createDirectories(base.getParent().resolve("backups"));
        String name = base.getFileName().toString();
        Path target = dir.resolve(name + "-" + STAMP.format(Instant.now()) + ".zip");
        try (Connection connection = backend.connection();
             Statement statement = connection.createStatement()) {
            readEverything(connection);
            statement.execute("BACKUP TO " + literal(target));
        }
        List<Path> all = backups(base.getParent(), name);
        for (Path old : all.subList(Math.min(all.size(), Math.max(1, settings.backups().keep())), all.size())) {
            Files.deleteIfExists(old);
        }
        return target;
    }

    /** This database's backups, newest first. */
    private static List<Path> backups(Path dir, String name) throws IOException {
        Path folder = dir.resolve("backups");
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            // The stamp sorts as text, which keeps the order right whatever the file times say.
            return files.filter(file -> {
                        String file1 = file.getFileName().toString();
                        return file1.startsWith(name + "-") && file1.endsWith(".zip");
                    })
                    .sorted(Comparator.comparing((Path file) -> file.getFileName().toString()).reversed())
                    .toList();
        }
    }

    /** Extracts a backup's {@code .mv.db} to the work file; {@code null} when the zip holds none or is unreadable. */
    private static @Nullable Path restore(Path backup, Path workBase) {
        Path out = mvFile(workBase);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(backup))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().endsWith(".mv.db")) {
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                    return out;
                }
            }
        } catch (IOException unreadable) {
            return null;
        }
        return null;
    }

    // ---------------------------------------------------------------- repair

    /** Copies the damaged file aside, once per distinct file, and returns the copy. */
    private static Path quarantine(Path dir, Path db) throws IOException {
        Path folder = dir.resolve("corrupted");
        long size = size(db);
        FileTime modified = Files.getLastModifiedTime(db);
        if (Files.isDirectory(folder)) {
            // The same damaged file after another restart: one copy of it is enough.
            try (Stream<Path> earlier = Files.list(folder)) {
                for (Path candidate : earlier.map(sub -> sub.resolve(db.getFileName())).toList()) {
                    if (Files.exists(candidate) && size(candidate) == size
                            && Files.getLastModifiedTime(candidate).equals(modified)) {
                        return candidate;
                    }
                }
            }
        }
        String stamp = STAMP.format(Instant.now());
        Path into = folder.resolve(stamp);
        for (int suffix = 2; Files.exists(into); suffix++) {
            into = folder.resolve(stamp + "-" + suffix);
        }
        Files.createDirectories(into);
        Path kept = into.resolve(db.getFileName());
        Files.copy(db, kept, StandardCopyOption.COPY_ATTRIBUTES);
        Path trace = db.resolveSibling(db.getFileName().toString().replace(".mv.db", ".trace.db"));
        if (Files.exists(trace)) {
            Files.copy(trace, into.resolve(trace.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        return kept;
    }

    /**
     * The chunks in a file, newest first, as {@code {id, firstBlock, blocks}}.
     *
     * <p>Every chunk starts on a 4096-byte block with a text header such as
     * {@code chunk:1cdcf9f,len:39e,...}: the id and the length in blocks, in hex.
     * Read from the headers rather than through MVStore, whose own tools fail
     * on exactly the files that need this.
     */
    static List<long[]> chunks(Path file) throws IOException {
        List<long[]> found = new ArrayList<>();
        byte[] prefix = "chunk:".getBytes(StandardCharsets.US_ASCII);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long blocks = channel.size() / BLOCK;
            ByteBuffer head = ByteBuffer.allocate(256);
            // Blocks 0 and 1 are the file header.
            for (long block = 2; block < blocks; block++) {
                head.clear();
                channel.read(head, block * BLOCK);
                byte[] bytes = head.array();
                if (!startsWith(bytes, prefix)) {
                    continue;
                }
                String header = new String(bytes, 0, head.position(), StandardCharsets.US_ASCII);
                long id = field(header, "chunk:");
                long length = field(header, ",len:");
                if (id >= 0 && length > 0) {
                    found.add(new long[]{id, block, Math.min(length, blocks - block)});
                }
            }
        }
        found.sort(Comparator.comparingLong((long[] chunk) -> chunk[0]).reversed());
        return found;
    }

    private static void zero(Path file, List<long[]> chunks) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            for (long[] chunk : chunks) {
                ByteBuffer zeros = ByteBuffer.allocate((int) Math.min(chunk[2] * BLOCK, Integer.MAX_VALUE));
                channel.write(zeros, chunk[1] * BLOCK);
            }
        }
    }

    /** Dumps a readable database and loads it into a fresh file, so nothing of the old layout survives. */
    private static Path rebuild(Path from, Path to, SqlSettings settings) throws SQLException {
        Path dump = from.resolveSibling(from.getFileName() + ".sql");
        try (Connection source = connect("jdbc:h2:file:" + from + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE", settings);
             Statement statement = source.createStatement()) {
            statement.execute("SCRIPT TO " + literal(dump));
        }
        try (Connection target = connect("jdbc:h2:file:" + to, settings);
             Statement statement = target.createStatement()) {
            statement.execute("RUNSCRIPT FROM " + literal(dump));
            statement.execute("SHUTDOWN COMPACT");
        }
        Throwable failure = check(to, settings);
        if (failure != null) {
            throw new SQLException("The rebuilt database does not read back: " + failure.getMessage(), failure);
        }
        return mvFile(to);
    }

    /** Puts a good file where the damaged one was, and moves the old trace file aside. */
    private static void install(Path good, Path base, Path dir) throws IOException {
        Path trace = dir.resolve(base.getFileName() + ".trace.db");
        Files.deleteIfExists(trace);
        Files.move(good, mvFile(base), StandardCopyOption.REPLACE_EXISTING);
    }

    // ----------------------------------------------------------------- small

    private static DatabaseException unavailable(Path db, Path base) {
        return new DatabaseException("The database " + db + " is damaged and could not be repaired"
                + " automatically, so it was left untouched and this plugin's data is offline."
                + " No empty database was created in its place. A copy of the damaged file is in "
                + base.getParent().resolve("corrupted") + ", and backups (if any) are in "
                + base.getParent().resolve("backups") + ". To recover, stop the server and replace "
                + db.getFileName() + " with the .mv.db file from a backup zip.");
    }

    private static Connection connect(String url, SqlSettings settings) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", settings.user() == null ? "sa" : settings.user());
        properties.setProperty("password", settings.password() == null ? "" : settings.password());
        // Directly, not through DriverManager, for the same classloader reason SqlBackend gives.
        return new org.h2.Driver().connect(url, properties);
    }

    private static Path mvFile(Path base) {
        return base.resolveSibling(base.getFileName() + ".mv.db");
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException gone) {
            return -1L;
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static long field(String header, String key) {
        int start = header.indexOf(key);
        if (start < 0) {
            return -1L;
        }
        start += key.length();
        int end = start;
        while (end < header.length() && Character.digit(header.charAt(end), 16) >= 0) {
            end++;
        }
        try {
            return Long.parseLong(header.substring(start, end), 16);
        } catch (NumberFormatException garbage) {
            return -1L;
        }
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String literal(Path path) {
        return "'" + path.toString().replace("'", "''") + "'";
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // A leftover work folder is harmless and replaced on the next repair.
        }
    }
}
