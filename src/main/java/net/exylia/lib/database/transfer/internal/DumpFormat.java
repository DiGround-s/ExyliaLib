package net.exylia.lib.database.transfer.internal;

import net.exylia.lib.database.internal.ColumnModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shape of an {@code .exyliadump.gz} file, in one place.
 *
 * <h2>NDJSON inside gzip, not one JSON object</h2>
 * One JSON value per line, gzip-compressed as a stream. Three reasons, and the
 * first two are the ones that matter:
 *
 * <ul>
 *   <li><b>A truncated file is still readable up to the truncation.</b>
 *       ExyliaCommons wrote one nested {@code {tables:{t:[...]}}} object, which
 *       a parser can only accept or reject whole: a dump cut short by a full
 *       disk was worth nothing at all, and a dump with one bad row in the
 *       middle was worth nothing either.</li>
 *   <li><b>The reader needs no nested parser state and can name the line that
 *       failed.</b> "Line 41 812 of table {@code practice_player_stats}" is
 *       something an operator can act on; "unexpected token at offset
 *       9 214 663" is not.</li>
 *   <li><b>gzip is roughly an order of magnitude on this data</b> — a dump is
 *       mostly Base64 inventories and repeated column shapes — and the CPU it
 *       costs is well under the I/O it saves.</li>
 * </ul>
 *
 * <h2>The layout</h2>
 * <pre>
 * {"format":1,"exportedAt":...,"engine":"h2","plugin":"Shields","tables":[...]}
 * {"table":"shield_designs"}
 * [41,"6f1c...","{}",7]
 * [42,"6f1c...","{}",0]
 * {"table":"shield_uses"}
 * ...
 * </pre>
 *
 * <p>The header carries, per table, its row count at the moment the export
 * started and its column layout — each column's name and its
 * {@link ColumnModel#storedType()}, in order. That layout is what makes the
 * rows readable: a row is a positional JSON array rather than an object keyed
 * by column name, which is a great deal smaller on a table of four hundred
 * thousand rows and carries no information the header does not already have.
 *
 * <h2>Values are written typed, never inferred</h2>
 * The reader asks each value for the type the header says it is. It does not
 * ask the parser to guess, and that is not a stylistic preference: Gson binding
 * a JSON number without a type token produces a {@code Double} — which is what
 * ExyliaCommons did, with {@code GSON.fromJson(reader, Map.class)} — so every
 * {@code long} past 2^53 and every {@code BigDecimal} came back changed, and
 * nothing reported it.
 *
 * <p>{@code BigDecimal} is therefore written as a JSON <em>string</em>. It is
 * the one type where the text is the value: a decimal that goes through a
 * binary {@code double} does not come back, and money is the only reason a
 * column is a {@code BigDecimal} at all.
 *
 * <p>{@code long} is written as a JSON number, deliberately. The risk with a
 * number is a reader that widens it through a {@code double}; this reader calls
 * {@code JsonReader.nextLong()}, which parses the token's own text and is exact
 * for every {@code long}. Writing it as a string would cost a quoted field on
 * every timestamp column in the ecosystem to guard against a mistake the reader
 * structurally cannot make.
 *
 * @since 1.36.0
 */
public final class DumpFormat {

    /** The format version in the header. Bumped when a reader would need to care. */
    public static final int VERSION = 1;

    /** The suffix every dump carries, gzip included. */
    public static final String EXTENSION = ".exyliadump.gz";

    /** Rows accumulated before a write, on both sides. The memory bound. */
    public static final int BATCH_SIZE = 1000;

    // Header keys.
    static final String FORMAT = "format";
    static final String EXPORTED_AT = "exportedAt";
    static final String ENGINE = "engine";
    static final String PLUGIN = "plugin";
    static final String TABLES = "tables";
    static final String TABLE = "table";
    static final String ROWS = "rows";
    static final String COLUMNS = "columns";
    static final String NAME = "name";
    static final String TYPE = "type";
    static final String GENERATED_ID = "generatedId";

    /**
     * The token written for each stored type.
     *
     * <p>Short names rather than {@code Class#getName}: a dump is read by a
     * later version of this library, and a JDK class name in a file is a
     * promise about a type the library does not own.
     */
    private static final Map<Class<?>, String> TOKENS = Map.ofEntries(
            Map.entry(String.class, "string"),
            Map.entry(int.class, "int"), Map.entry(Integer.class, "int"),
            Map.entry(long.class, "long"), Map.entry(Long.class, "long"),
            Map.entry(double.class, "double"), Map.entry(Double.class, "double"),
            Map.entry(float.class, "float"), Map.entry(Float.class, "float"),
            Map.entry(short.class, "short"), Map.entry(Short.class, "short"),
            Map.entry(byte.class, "byte"), Map.entry(Byte.class, "byte"),
            Map.entry(boolean.class, "boolean"), Map.entry(Boolean.class, "boolean"),
            Map.entry(BigDecimal.class, "decimal"));

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());

    private DumpFormat() {
        throw new AssertionError("No instances.");
    }

    /**
     * The token for a column's stored type.
     *
     * <p>Every stored type is one of the sixteen above — that is what
     * {@link ColumnModel#storedType()} guarantees, since anything a codec
     * touches is {@code String}. An unknown one would be a new direct type
     * added to the model without this being updated, so it fails loudly rather
     * than writing a dump nothing can read back.
     *
     * @param column the column
     * @return its token
     * @throws IllegalStateException if the type is not one the format knows
     */
    static @NotNull String tokenOf(@NotNull ColumnModel column) {
        String token = TOKENS.get(column.storedType());
        if (token == null) {
            throw new IllegalStateException("Column " + column.name() + " stores a "
                    + column.storedType().getName() + ", which the dump format has no token for."
                    + " A new direct type was added to the model without teaching DumpFormat"
                    + " about it, and a dump written now could not be read back.");
        }
        return token;
    }

    /**
     * The file name a dump of this plugin gets.
     *
     * <p>Plugin, engine and a timestamp, so a folder of dumps is readable
     * without opening any of them, and so exporting twice in one day does not
     * overwrite the morning's copy.
     *
     * @param plugin the plugin whose tables these are
     * @param engine the engine it was read from
     * @param when   when the export started
     * @return the file name, extension included
     */
    public static @NotNull String fileName(@NotNull String plugin, @NotNull String engine,
                                           @NotNull Instant when) {
        return sanitise(plugin) + "-" + sanitise(engine) + "-" + STAMP.format(when) + EXTENSION;
    }

    /**
     * A name safe to put in a path.
     *
     * <p>A plugin name is whatever its author wrote in {@code plugin.yml}, and
     * a slash in one would silently write the dump into a directory that may
     * not exist — or, worse, one that does.
     */
    private static @NotNull String sanitise(@NotNull String name) {
        StringBuilder safe = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            safe.append(Character.isLetterOrDigit(character) || character == '_' || character == '-'
                    ? character : '_');
        }
        return safe.isEmpty() ? "plugin" : safe.toString();
    }

    /** One column as the header describes it. */
    public record ColumnLayout(@NotNull String name, @NotNull String type) {
    }

    /**
     * One table as the header describes it.
     *
     * @param table       the table name
     * @param rows        how many rows it held when the export started
     * @param columns     its layout, in the order rows are written
     * @param generatedId whether its key was handed out by the engine
     */
    public record TableLayout(@NotNull String table,
                              long rows,
                              @NotNull List<ColumnLayout> columns,
                              boolean generatedId) {

        public TableLayout {
            columns = List.copyOf(columns);
        }

        /**
         * Where a column sits in this layout, or {@code -1} when it is absent.
         *
         * <p>By name, which is the whole reason the header carries the layout:
         * a record that gained, lost or reordered a component since the dump
         * was written must still import, and binding positionally would put the
         * elo in the clan column and report success.
         *
         * @param name the column name
         * @return the index into a row array, or {@code -1}
         */
        public int indexOf(@NotNull String name) {
            for (int index = 0; index < columns.size(); index++) {
                if (columns.get(index).name().equals(name)) {
                    return index;
                }
            }
            return -1;
        }
    }

    /**
     * The header of a dump.
     *
     * @param format     the format version the file was written with
     * @param exportedAt epoch milliseconds
     * @param engine     the engine the rows were read from, for diagnostics
     * @param plugin     the plugin that owned them
     * @param tables     one entry per table, in the order they follow
     */
    public record Header(int format,
                         long exportedAt,
                         @NotNull String engine,
                         @NotNull String plugin,
                         @NotNull List<TableLayout> tables) {

        public Header {
            tables = List.copyOf(tables);
        }

        /** One table's layout by name, or {@code null} when the dump has no such table. */
        public @Nullable TableLayout table(@NotNull String name) {
            for (TableLayout layout : tables) {
                if (layout.table().equals(name)) {
                    return layout;
                }
            }
            return null;
        }
    }
}
