package net.exylia.lib.database.transfer.internal;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntConsumer;

/**
 * The two things a test needs from a dump that production has no reason to
 * expose.
 *
 * <p>Kept out of {@link DumpFormat} and {@link DumpReader} on purpose: neither
 * of those should grow a method whose only caller is a test, and a reader that
 * publicly announced its batch sizes would invite somebody to depend on them.
 *
 * <p>Not API, and it says so. The whole {@code internal} package is free to
 * change without notice, and this class more than the rest of it.
 *
 * @since 1.36.0
 */
@ApiStatus.Internal
public final class DumpFormatAccess {

    private DumpFormatAccess() {
        throw new AssertionError("No instances.");
    }

    /** The dump extension, for a test building a fixture file. */
    public static @NotNull String extension() {
        return DumpFormat.EXTENSION;
    }

    /**
     * Walks a dump and reports the size of every batch the reader hands over.
     *
     * <p>The memory bound of an import, made observable. The importer's own
     * loop asks the reader for at most {@link DumpFormat#BATCH_SIZE} rows,
     * writes them and drops them, so what bounds the import is exactly what
     * bounds the reader — and this is the reader, driven the same way.
     *
     * <p>A test that instead trusted the importer's comment would pass against
     * a reader that parsed the whole file and chunked it afterwards, which is
     * the one implementation this has to rule out.
     *
     * @param file  the dump
     * @param sizes told the size of each batch, in order
     * @throws IOException if the dump could not be read
     */
    public static void observeBatches(@NotNull Path file, @NotNull IntConsumer sizes)
            throws IOException {
        try (DumpReader reader = DumpReader.open(file)) {
            TransferRuntime.observeBatches(reader, sizes);
        }
    }
}
