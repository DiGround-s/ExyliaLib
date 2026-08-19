package net.exylia.lib.database.transfer.internal;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonWriter;
import net.exylia.lib.database.internal.ColumnModel;
import net.exylia.lib.database.internal.EntityModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Writes one dump, one line at a time, holding nothing.
 *
 * <p>The header goes out first, then a marker line per table, then one line per
 * row. Nothing accumulates beyond the streams' own buffers: a batch arrives,
 * its rows are written, and the batch is dropped. A table of four hundred
 * thousand rows costs the same heap as one of four hundred, which is the entire
 * point of writing this rather than serialising a structure.
 *
 * <h2>One writer, one thread</h2>
 * Not thread-safe, and it does not need to be. The batches it is fed come from
 * {@code Storage.scan}, whose block runs on one database thread and is called
 * with each batch before the next is read, so writes are already serialised by
 * the scan itself. Tables are exported one after another, each waiting on its
 * own scan's future, so the same holds across tables. Two threads sharing one
 * of these would interleave two rows into one line.
 *
 * @since 1.36.0
 */
final class DumpWriter implements AutoCloseable {

    /**
     * The character sink, held as well as wrapped.
     *
     * <p>A newline between values is what makes the file NDJSON, and JSON has
     * no notion of one — {@link JsonWriter} cannot be asked for it. So the
     * separator is written straight to the stream, after flushing whatever the
     * JSON writer still holds.
     */
    private final Writer target;

    private final JsonWriter json;
    private final List<DumpFormat.TableLayout> layouts;

    private DumpWriter(Writer target, JsonWriter json, List<DumpFormat.TableLayout> layouts) {
        this.target = target;
        this.json = json;
        this.layouts = layouts;
    }

    /**
     * Opens a dump and writes its header.
     *
     * <p>The file is created here, so a failure to open it — a read-only
     * folder, a full disk, a path that is a directory — happens before a single
     * row is read out of the database.
     *
     * @param file    where to write; missing parent directories are created
     * @param plugin  the plugin whose tables these are
     * @param engine  the engine they came from
     * @param when    the export's own timestamp
     * @param layouts one per table, in the order they will follow
     * @return the open writer, header already written
     * @throws IOException if the file could not be created or the header written
     */
    static @NotNull DumpWriter open(@NotNull Path file,
                                    @NotNull String plugin,
                                    @NotNull String engine,
                                    @NotNull Instant when,
                                    @NotNull List<DumpFormat.TableLayout> layouts)
            throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Each stream is held by the next, so closing the outermost closes the
        // chain — which is what makes one try-with-resources at the call site
        // enough even when a write fails halfway down a table.
        Writer text = new OutputStreamWriter(
                new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file))),
                StandardCharsets.UTF_8);
        JsonWriter json = new JsonWriter(text);
        // Lenient because a file of many top-level values is exactly what
        // NDJSON is, and strict mode refuses the second one.
        json.setStrictness(Strictness.LENIENT);
        // Explicit: a null column has to be a JSON null rather than an omitted
        // entry. A row is positional, so an omitted value would shift every
        // column after it by one.
        json.setSerializeNulls(true);
        DumpWriter writer = new DumpWriter(text, json, List.copyOf(layouts));
        try {
            writer.header(plugin, engine, when);
        } catch (IOException | RuntimeException failure) {
            writer.closeQuietly();
            throw failure;
        }
        return writer;
    }

    private void header(String plugin, String engine, Instant when) throws IOException {
        json.beginObject();
        json.name(DumpFormat.FORMAT).value(DumpFormat.VERSION);
        json.name(DumpFormat.EXPORTED_AT).value(when.toEpochMilli());
        json.name(DumpFormat.ENGINE).value(engine);
        json.name(DumpFormat.PLUGIN).value(plugin);
        json.name(DumpFormat.TABLES).beginArray();
        for (DumpFormat.TableLayout layout : layouts) {
            json.beginObject();
            json.name(DumpFormat.TABLE).value(layout.table());
            json.name(DumpFormat.ROWS).value(layout.rows());
            json.name(DumpFormat.GENERATED_ID).value(layout.generatedId());
            json.name(DumpFormat.COLUMNS).beginArray();
            for (DumpFormat.ColumnLayout column : layout.columns()) {
                json.beginObject();
                json.name(DumpFormat.NAME).value(column.name());
                json.name(DumpFormat.TYPE).value(column.type());
                json.endObject();
            }
            json.endArray();
            json.endObject();
        }
        json.endArray();
        json.endObject();
        newLine();
    }

    /** Writes the marker saying the rows that follow belong to this table. */
    void beginTable(@NotNull String table) throws IOException {
        json.beginObject();
        json.name(DumpFormat.TABLE).value(table);
        json.endObject();
        newLine();
    }

    /**
     * Writes one batch of rows in storage form.
     *
     * <p>Each row is a positional JSON array in {@link EntityModel#columns()}
     * order, and each value is written as the type its column stores. The
     * arrays are only read: the batch belongs to the scan that produced it and
     * is dropped straight afterwards.
     *
     * @param model the model whose columns lay these rows out
     * @param batch the rows
     * @throws IOException if the stream refused a write
     */
    void writeRows(@NotNull EntityModel<?> model, @NotNull List<Object[]> batch) throws IOException {
        List<ColumnModel> columns = model.columns();
        for (Object[] row : batch) {
            json.beginArray();
            for (int index = 0; index < columns.size(); index++) {
                write(columns.get(index), index < row.length ? row[index] : null);
            }
            json.endArray();
            newLine();
        }
    }

    /**
     * Writes one value as the type its column stores.
     *
     * <p>Typed on the way out so the reader never has to infer. A JSON number
     * bound without a type token becomes a {@code Double} — which is what
     * ExyliaCommons did, with {@code GSON.fromJson(reader, Map.class)} — and
     * that one default silently changed every {@code long} past 2^53 and every
     * decimal it ever imported.
     *
     * <p>A {@code BigDecimal} goes out as a JSON string, always. The text
     * <em>is</em> the value: a decimal routed through a binary double does not
     * come back, and money is the only reason a column is one.
     */
    private void write(ColumnModel column, @Nullable Object value) throws IOException {
        if (value == null) {
            json.nullValue();
            return;
        }
        Class<?> stored = column.storedType();
        if (stored == String.class) {
            // toString rather than a cast: the scan reads text with getString,
            // but a Mongo document hands back whatever type it was written with.
            json.value(value.toString());
            return;
        }
        if (stored == BigDecimal.class) {
            json.value(decimal(value).toPlainString());
            return;
        }
        if (stored == boolean.class || stored == Boolean.class) {
            boolean flag = value instanceof Boolean already
                    ? already : Boolean.parseBoolean(value.toString());
            json.value(flag);
            return;
        }
        if (value instanceof Number number) {
            writeNumber(stored, number);
            return;
        }
        // A driver answering a numeric column with text, which happens on Mongo
        // and on any column somebody widened to VARCHAR by hand. Through
        // BigDecimal because it reads both "42" and "42.0", and a MySQL DOUBLE
        // round-trips through text as the second.
        writeNumber(stored, new BigDecimal(value.toString()));
    }

    private void writeNumber(Class<?> stored, Number number) throws IOException {
        if (stored == double.class || stored == Double.class
                || stored == float.class || stored == Float.class) {
            // As a Number, so Gson emits the shortest exact text for the width
            // it actually is rather than a double's rendering of a float.
            json.value(number);
            return;
        }
        // Every remaining direct type is a whole number no wider than a long,
        // and JsonReader.nextLong() parses the token's own digits — exact for
        // every long, unlike a value that goes through a double on the way in.
        json.value(number.longValue());
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal already ? already : new BigDecimal(value.toString());
    }

    /** Ends the line, which is the one thing JSON itself has no notion of. */
    private void newLine() throws IOException {
        json.flush();
        target.write('\n');
    }

    /** Closes without masking a failure that is already on its way up. */
    void closeQuietly() {
        try {
            close();
        } catch (IOException ignored) {
            // The failure being handled is the one worth reporting; a second
            // one from the close would replace it with a less useful message.
        }
    }

    @Override
    public void close() throws IOException {
        json.close();
    }
}
