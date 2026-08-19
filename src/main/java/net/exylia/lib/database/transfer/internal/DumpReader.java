package net.exylia.lib.database.transfer.internal;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Reads one dump, one line at a time, holding nothing but the current line.
 *
 * <p>The header is parsed on open, so a file that is not a dump — or is a dump
 * written by a version this one does not understand — is refused before a
 * single row is written to a database. After that the file is walked line by
 * line: a table marker changes which table the rows belong to, and every other
 * line is one row.
 *
 * <h2>Every failure names its line</h2>
 * That is what one JSON value per line buys. A parser reading one nested object
 * can only report a byte offset into a stream that no longer exists once it is
 * decompressed, and ExyliaCommons' importer could not report even that: a
 * truncated file failed as a whole and named nothing at all.
 *
 * <h2>One reader, one thread</h2>
 * Not thread-safe. It is driven by one import, which reads a batch, waits for
 * that batch to be written, and only then reads the next.
 *
 * @since 1.36.0
 */
final class DumpReader implements AutoCloseable {

    private final BufferedReader lines;
    private final DumpFormat.Header header;

    /** Which line was read last, so a failure can name it. Starts at the header. */
    private long line;

    /** The table the following rows belong to, or {@code null} before the first marker. */
    private String table;

    private DumpReader(BufferedReader lines, DumpFormat.Header header, long line) {
        this.lines = lines;
        this.header = header;
        this.line = line;
    }

    /**
     * Opens a dump and reads its header.
     *
     * @param file the dump
     * @return the open reader
     * @throws IOException            if the file could not be read or decompressed
     * @throws DumpException          if it is not a dump, or is one this version
     *                                cannot read
     */
    static @NotNull DumpReader open(@NotNull Path file) throws IOException {
        BufferedReader lines = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))),
                StandardCharsets.UTF_8));
        try {
            String first = lines.readLine();
            if (first == null) {
                throw new DumpException("The dump is empty: it has no header line."
                        + " An export that was interrupted before it wrote anything looks"
                        + " exactly like this.", 1L);
            }
            return new DumpReader(lines, header(first), 1L);
        } catch (IOException | RuntimeException failure) {
            try {
                lines.close();
            } catch (IOException ignored) {
                // The failure being handled is the one worth reporting.
            }
            throw failure;
        }
    }

    /** The header, read on open. */
    @NotNull DumpFormat.Header header() {
        return header;
    }

    /** The line last read, for a message that has to say where something went wrong. */
    long line() {
        return line;
    }

    /**
     * Reads up to {@code limit} rows of one table.
     *
     * <p>Stops at the limit, at the next table marker, or at the end of the
     * file — whichever comes first — and answers which table the rows belong
     * to. A marker is consumed and remembered, so the next call continues with
     * the new table.
     *
     * @param limit rows at most, which is the memory bound of the whole import
     * @return the batch, or {@code null} at the end of the file
     * @throws IOException   if the stream failed
     * @throws DumpException if a line could not be parsed, naming that line
     */
    @Nullable Batch next(int limit) throws IOException {
        List<List<Object>> rows = new ArrayList<>(Math.min(limit, DumpFormat.BATCH_SIZE));
        while (rows.size() < limit) {
            lines.mark(1 << 20);
            String text = lines.readLine();
            if (text == null) {
                break;
            }
            if (text.isBlank()) {
                line++;
                continue;
            }
            if (isMarker(text)) {
                if (!rows.isEmpty()) {
                    // The marker belongs to the next batch: put it back rather
                    // than consuming it, so the batch about to be returned is
                    // still entirely the previous table's.
                    lines.reset();
                    break;
                }
                line++;
                table = marker(text);
                continue;
            }
            line++;
            if (table == null) {
                throw new DumpException("A row appears before any table marker, so there is"
                        + " nothing to say which table it belongs to.", line);
            }
            rows.add(row(text));
        }
        if (rows.isEmpty()) {
            return null;
        }
        return new Batch(table, List.copyOf(rows));
    }

    /**
     * Whether a line is a table marker rather than a row.
     *
     * <p>By its first character. A row is a JSON array and a marker is a JSON
     * object, so the two can never be confused — which is why rows are arrays
     * and not objects keyed by column name.
     */
    private static boolean isMarker(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (!Character.isWhitespace(character)) {
                return character == '{';
            }
        }
        return false;
    }

    private String marker(String text) throws IOException {
        try (JsonReader json = reader(text)) {
            json.beginObject();
            String name = null;
            while (json.hasNext()) {
                if (DumpFormat.TABLE.equals(json.nextName())) {
                    name = json.nextString();
                } else {
                    json.skipValue();
                }
            }
            json.endObject();
            if (name == null) {
                throw new DumpException("A table marker names no table.", line);
            }
            return name;
        } catch (DumpException already) {
            throw already;
        } catch (IOException | RuntimeException malformed) {
            // IOException as well as RuntimeException, and that is not
            // defensive: Gson's own MalformedJsonException and the EOFException
            // it throws on a truncated value both extend IOException, so
            // catching only RuntimeException lets exactly the failure this
            // module exists to report escape without its line number. The
            // reader here is a StringReader over one line already in memory, so
            // an IOException from it can only be a parse failure.
            throw new DumpException("A table marker could not be read: "
                    + malformed.getMessage(), line, malformed);
        }
    }

    /**
     * Reads one row.
     *
     * <p>Values come back as {@code Long}, {@code Double}, {@code String},
     * {@code Boolean}, {@code BigDecimal} or {@code null} — whichever the JSON
     * token itself is — and are coerced to the column's stored type by
     * {@link #coerce}, against the header's layout rather than against a guess.
     */
    private List<Object> row(String text) throws IOException {
        List<Object> values = new ArrayList<>();
        try (JsonReader json = reader(text)) {
            json.beginArray();
            while (json.hasNext()) {
                values.add(value(json));
            }
            json.endArray();
        } catch (IOException | RuntimeException malformed) {
            // Both, for the reason above: a line cut in half by a full disk
            // fails as Gson's MalformedJsonException, which is an IOException,
            // and that is precisely the case whose line number matters.
            throw new DumpException("A row could not be read: " + malformed.getMessage(),
                    line, malformed);
        }
        return values;
    }

    private static @Nullable Object value(JsonReader json) throws IOException {
        JsonToken token = json.peek();
        return switch (token) {
            case NULL -> {
                json.nextNull();
                yield null;
            }
            case BOOLEAN -> json.nextBoolean();
            case STRING -> json.nextString();
            case NUMBER -> number(json);
            default -> throw new IllegalStateException("a " + token + " is not a column value");
        };
    }

    /**
     * Reads a JSON number without letting it through a {@code double}.
     *
     * <p>{@code nextLong} first, because it parses the token's own digits and
     * is exact for every {@code long}. Only a token it refuses — one with a
     * fraction or an exponent — is read as a {@code double}, and by then the
     * value is genuinely a floating-point one.
     */
    private static @NotNull Object number(JsonReader json) throws IOException {
        try {
            return json.nextLong();
        } catch (NumberFormatException fractional) {
            return json.nextDouble();
        }
    }

    /**
     * Turns a parsed JSON value into the type a column stores.
     *
     * <p>The one place the dump's declared type is applied. Called with the
     * type token the <em>header</em> announced for that column, not with the
     * current model's — a dump written when a column was an {@code int} and
     * read when it is a {@code long} is still readable, because the value is
     * widened here and bound as whatever the column is now.
     *
     * @param value the parsed value, possibly {@code null}
     * @param type  the token from the header
     * @return the value in stored form
     */
    static @Nullable Object coerce(@Nullable Object value, @NotNull String type) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case "string" -> value.toString();
            case "decimal" -> value instanceof BigDecimal already
                    ? already : new BigDecimal(value.toString());
            case "boolean" -> value instanceof Boolean flag
                    ? flag : Boolean.parseBoolean(value.toString());
            case "int" -> ((Number) numeric(value)).intValue();
            case "long" -> ((Number) numeric(value)).longValue();
            case "short" -> ((Number) numeric(value)).shortValue();
            case "byte" -> ((Number) numeric(value)).byteValue();
            case "double" -> ((Number) numeric(value)).doubleValue();
            case "float" -> ((Number) numeric(value)).floatValue();
            // A type token from a newer format version. The value is handed
            // over as it was parsed rather than dropped: the driver may well
            // accept it, and dropping it would silently blank a column.
            default -> value;
        };
    }

    private static Number numeric(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        return new BigDecimal(value.toString());
    }

    private static JsonReader reader(String text) {
        JsonReader json = new JsonReader(new StringReader(text));
        json.setStrictness(Strictness.LENIENT);
        return json;
    }

    private static DumpFormat.Header header(String text) throws IOException {
        int format = 0;
        long exportedAt = 0L;
        String engine = "unknown";
        String plugin = "unknown";
        List<DumpFormat.TableLayout> tables = new ArrayList<>();
        try (JsonReader json = reader(text)) {
            json.beginObject();
            while (json.hasNext()) {
                switch (json.nextName()) {
                    case DumpFormat.FORMAT -> format = json.nextInt();
                    case DumpFormat.EXPORTED_AT -> exportedAt = json.nextLong();
                    case DumpFormat.ENGINE -> engine = json.nextString();
                    case DumpFormat.PLUGIN -> plugin = json.nextString();
                    case DumpFormat.TABLES -> {
                        json.beginArray();
                        while (json.hasNext()) {
                            tables.add(tableLayout(json));
                        }
                        json.endArray();
                    }
                    default -> json.skipValue();
                }
            }
            json.endObject();
        } catch (IOException | RuntimeException malformed) {
            throw new DumpException("The first line is not a dump header: "
                    + malformed.getMessage() + ". The file is either not an ExyliaLib dump or"
                    + " was truncated before its header was complete.", 1L, malformed);
        }
        if (format <= 0) {
            throw new DumpException("The header declares no format version, so this is not"
                    + " an ExyliaLib dump.", 1L);
        }
        if (format > DumpFormat.VERSION) {
            throw new DumpException("The dump is format version " + format + " and this"
                    + " version of ExyliaLib reads up to " + DumpFormat.VERSION
                    + ". Import it with the version that wrote it, or export it again from"
                    + " the source server after updating there.", 1L);
        }
        return new DumpFormat.Header(format, exportedAt, engine, plugin, tables);
    }

    private static DumpFormat.TableLayout tableLayout(JsonReader json) throws IOException {
        String table = null;
        long rows = 0L;
        boolean generatedId = false;
        List<DumpFormat.ColumnLayout> columns = new ArrayList<>();
        json.beginObject();
        while (json.hasNext()) {
            switch (json.nextName()) {
                case DumpFormat.TABLE -> table = json.nextString();
                case DumpFormat.ROWS -> rows = json.nextLong();
                case DumpFormat.GENERATED_ID -> generatedId = json.nextBoolean();
                case DumpFormat.COLUMNS -> {
                    json.beginArray();
                    while (json.hasNext()) {
                        columns.add(columnLayout(json));
                    }
                    json.endArray();
                }
                default -> json.skipValue();
            }
        }
        json.endObject();
        if (table == null) {
            throw new IllegalStateException("a table entry names no table");
        }
        return new DumpFormat.TableLayout(table, rows, columns, generatedId);
    }

    private static DumpFormat.ColumnLayout columnLayout(JsonReader json) throws IOException {
        String name = null;
        String type = "string";
        json.beginObject();
        while (json.hasNext()) {
            switch (json.nextName()) {
                case DumpFormat.NAME -> name = json.nextString();
                case DumpFormat.TYPE -> type = json.nextString();
                default -> json.skipValue();
            }
        }
        json.endObject();
        if (name == null) {
            throw new IllegalStateException("a column entry names no column");
        }
        return new DumpFormat.ColumnLayout(name, type);
    }

    @Override
    public void close() throws IOException {
        lines.close();
    }

    /**
     * Rows of one table, as they were parsed.
     *
     * @param table the table they belong to
     * @param rows  each in the order the header's layout for that table lists
     */
    record Batch(@NotNull String table, @NotNull List<List<Object>> rows) {
    }
}
