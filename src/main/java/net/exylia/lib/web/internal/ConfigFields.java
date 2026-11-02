package net.exylia.lib.web.internal;

import net.exylia.lib.config.Schema;
import net.exylia.lib.config.Time;
import net.exylia.lib.effect.Ticks;
import net.exylia.lib.text.Lines;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Values;
import net.exylia.lib.util.TimeFormats;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the in-game settings screens know about one key of a record config: how it is edited,
 * how its value reads, and what a click writes back. Pure: no menu, no thread, no file, so the
 * mapping from a {@link Schema} to a control is tested on its own.
 *
 * <p>Every value it hands back for writing is already in the shape the file holds it in (an
 * enum as {@code lower-hyphen}, a {@link Duration} as {@code 1m30s}, a {@link Time} number in
 * its declared unit), so {@code ConfigFile.set} writes exactly what an owner would have typed.
 */
final class ConfigFields {

    /** How a key is edited. */
    enum Kind {
        /** A nested section: opens its own page. */
        SECTION,
        /** A block of named entries in a YAML file: opens the list of entries. */
        ENTRIES,
        /** {@code true} / {@code false}: a click flips it. */
        TOGGLE,
        /** An enum: left forward, right back. */
        CHOICE,
        /** A whole number: ±1, ±10 with shift, Q to type it. */
        INTEGER,
        /** A decimal: ±0.1, ±1 with shift, Q to type it. */
        DECIMAL,
        /** A {@link Duration} or a {@link Time} number: asked as a length of time. */
        TIME,
        /** One line of text, asked prefilled. */
        TEXT,
        /** A {@link Material}: the icon picker. */
        MATERIAL,
        /** A list of text lines, asked as one box of lines. */
        LINES,
        /** Anything else (maps, lists of records): shown, edited in the web editor. */
        LOCKED
    }

    /**
     * One key on a page.
     *
     * @param key    the dotted YAML key from the file's root
     * @param schema the record component it is
     * @param owner  the record class that declares it
     * @param kind   how it is edited
     */
    record Field(String key, Schema.Field schema, Class<?> owner, Kind kind) {

        /** The last part of the key, as written: {@code min-distance}. */
        String name() {
            return schema.key();
        }

        /** The unit a {@link Time} number is written in; {@code null} for anything else. */
        @Nullable Time.Unit unit() {
            Time time = annotation(owner, schema.name());
            return time == null ? null : time.value();
        }
    }

    private ConfigFields() {
    }

    // ------------------------------------------------------------------ shape

    /**
     * The keys of one section, in declaration order.
     *
     * @param root    the file's schema
     * @param section the section's dotted key, empty for the root
     * @return its keys, or empty when the section does not exist
     */
    static List<Field> fields(Schema root, String section) {
        Schema schema = section(root, section);
        if (schema == null) return List.of();
        List<Field> fields = new ArrayList<>(schema.fields().size());
        for (Schema.Field field : schema.fields()) {
            String key = section.isEmpty() ? field.key() : section + "." + field.key();
            fields.add(new Field(key, field, schema.type(), kind(schema.type(), field)));
        }
        return fields;
    }

    /** The schema of a section, or {@code null} when the path names none. */
    static @Nullable Schema section(Schema root, String section) {
        Schema schema = root;
        if (section.isEmpty()) return schema;
        for (String part : section.split("\\.")) {
            Schema next = null;
            for (Schema.Field field : schema.fields()) {
                if (field.key().equals(part) && field.isSection()) next = field.nested();
            }
            if (next == null) return null;
            schema = next;
        }
        return schema;
    }

    /** One key of the file, or {@code null} when there is none by that name. */
    static @Nullable Field field(Schema root, String key) {
        int dot = key.lastIndexOf('.');
        String section = dot < 0 ? "" : key.substring(0, dot);
        for (Field field : fields(root, section)) {
            if (field.key().equals(key)) return field;
        }
        return null;
    }

    /** How a component is edited. */
    static Kind kind(Class<?> owner, Schema.Field field) {
        if (field.isSection()) return Kind.SECTION;
        Class<?> type = field.type();
        if (type == boolean.class || type == Boolean.class) return Kind.TOGGLE;
        if (type == Duration.class) return Kind.TIME;
        if (type == Material.class) return Kind.MATERIAL;
        if (type.isEnum()) return Kind.CHOICE;
        boolean whole = type == int.class || type == Integer.class || type == long.class || type == Long.class;
        if (whole || type == double.class || type == Double.class || type == float.class || type == Float.class) {
            if (annotation(owner, field.name()) != null) return Kind.TIME;
            return whole ? Kind.INTEGER : Kind.DECIMAL;
        }
        if (type == String.class) return Kind.TEXT;
        if (List.class.isAssignableFrom(type) && field.generic() instanceof ParameterizedType list
                && list.getActualTypeArguments()[0] == String.class) {
            return Kind.LINES;
        }
        return Kind.LOCKED;
    }

    private static @Nullable Time annotation(Class<?> owner, String component) {
        if (!owner.isRecord()) return null;
        for (RecordComponent part : owner.getRecordComponents()) {
            if (part.getName().equals(component)) return part.getAnnotation(Time.class);
        }
        return null;
    }

    // ------------------------------------------------------------------ values

    /**
     * The value a dotted key holds in a loaded record.
     *
     * @param root   the file's schema
     * @param values what {@code ConfigFile.get()} answers
     * @param key    the dotted key
     * @return the value, or {@code null} when absent or unreadable
     */
    static @Nullable Object read(Schema root, Object values, String key) {
        Schema schema = root;
        Object node = values;
        for (String part : key.split("\\.")) {
            if (node == null || schema == null) return null;
            Schema.Field found = null;
            for (Schema.Field field : schema.fields()) {
                if (field.key().equals(part)) found = field;
            }
            if (found == null) return null;
            node = component(node, found.name());
            schema = found.nested();
        }
        return node;
    }

    private static @Nullable Object component(Object record, String name) {
        if (!record.getClass().isRecord()) return null;
        for (RecordComponent part : record.getClass().getRecordComponents()) {
            if (!part.getName().equals(name)) continue;
            try {
                var accessor = part.getAccessor();
                accessor.setAccessible(true);
                return accessor.invoke(record);
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return null;
            }
        }
        return null;
    }

    /**
     * What one click on a toggle, a choice or a number writes.
     *
     * @param field     the key
     * @param current   its value now
     * @param direction {@code 1} forward or up, {@code -1} back or down
     * @param big       shift held: ten times the step
     * @return the value to write, or {@code null} when that click changes nothing
     */
    static @Nullable Object step(Field field, @Nullable Object current, int direction, boolean big) {
        return switch (field.kind()) {
            case TOGGLE -> !Boolean.TRUE.equals(current);
            case CHOICE -> next(field.schema().type(), current, direction);
            case INTEGER -> {
                long now = current instanceof Number number ? number.longValue() : 0;
                long next = now + (long) direction * (big ? 10 : 1);
                yield field.schema().type() == int.class || field.schema().type() == Integer.class
                        ? (Object) (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, next)) : next;
            }
            case DECIMAL -> {
                BigDecimal now = current instanceof Number number ? new BigDecimal(number.toString()) : BigDecimal.ZERO;
                yield now.add(new BigDecimal(big ? "1" : "0.1").multiply(BigDecimal.valueOf(direction))).doubleValue();
            }
            default -> null;
        };
    }

    /** The constant after (or before) {@code current}, wrapping, as the file writes it. */
    static String next(Class<?> type, @Nullable Object current, int direction) {
        Object[] constants = type.getEnumConstants();
        int at = 0;
        for (int i = 0; i < constants.length; i++) {
            if (constants[i] == current) at = i;
        }
        int next = Math.floorMod(at + direction, constants.length);
        return written((Enum<?>) constants[next]);
    }

    /** An enum constant the way the binder writes it: {@code lower-hyphen}. */
    static String written(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** A length of time as a field of this kind holds it in the file. */
    static Object time(Field field, Duration duration) {
        Time.Unit unit = field.unit();
        if (unit == null) return Ticks.write(duration.toMillis());
        long amount = Math.round(duration.toMillis() / (double) unit.millis());
        Class<?> type = field.schema().type();
        if (type == int.class || type == Integer.class) return (int) Math.min(Integer.MAX_VALUE, amount);
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return duration.toMillis() / (double) unit.millis();
        }
        return amount;
    }

    /** The length of time a value of this field is, for display and to prefill the box. */
    static Duration duration(Field field, @Nullable Object value) {
        if (value instanceof Duration duration) return duration;
        Time.Unit unit = field.unit();
        double amount = value instanceof Number number ? number.doubleValue() : 0;
        return Duration.ofMillis(Math.max(0, Math.round(amount * (unit == null ? 1000 : unit.millis()))));
    }

    /** Typed lines, split as the box returns them. */
    static List<String> lines(String typed) {
        return List.of(typed.replace("\r\n", "\n").split("\n", -1));
    }

    // ------------------------------------------------------------------ drawing

    /** What a key is called on its button: {@code min-distance} as {@code MIN DISTANCE}. */
    static String title(String key) {
        return key.replace('-', ' ').replace('_', ' ').toUpperCase(Locale.ROOT);
    }

    /** The value, coloured by what it is; anything typed is shown as written, never parsed. */
    static String display(Field field, @Nullable Object value) {
        return switch (field.kind()) {
            case SECTION -> Phrases.tr("{info}{0} {letters}settings", field.schema().nested() == null ? 0
                    : field.schema().nested().fields().size());
            case TOGGLE -> Boolean.TRUE.equals(value) ? Phrases.tr("{success}Enabled") : Phrases.tr("{error}Disabled");
            case CHOICE -> value instanceof Enum<?> constant ? "{highlight}" + written(constant) : none();
            case INTEGER, DECIMAL -> value instanceof Number number
                    ? "{info}" + new BigDecimal(number.toString()).stripTrailingZeros().toPlainString() : none();
            case TIME -> "{info}" + TimeFormats.render(duration(field, value), TimeFormats.Style.FULL) + " ⌚";
            case MATERIAL -> value instanceof Material material ? "{highlight}" + material.name() : none();
            case TEXT -> value == null || value.toString().isEmpty() ? Phrases.tr("{muted}Empty")
                    : "{letters}" + Values.inert(cut(value.toString()));
            case LINES -> Phrases.tr("{info}{0} {letters}lines", value instanceof List<?> list ? list.size() : 0);
            case LOCKED -> value == null ? none() : "{muted}" + Values.inert(cut(String.valueOf(value)));
            case ENTRIES -> entries(value instanceof Number number ? number.intValue() : 0);
        };
    }

    /** How many entries a block holds. */
    static String entries(int count) {
        return Phrases.tr("{info}{0} {letters}entries", count);
    }

    /**
     * A value of a YAML file, which has no record to say more: toggles, numbers, text and lines
     * read as a record's do.
     */
    static String plain(Kind kind, @Nullable Object value) {
        return switch (kind) {
            case TOGGLE -> Boolean.TRUE.equals(value) ? Phrases.tr("{success}Enabled") : Phrases.tr("{error}Disabled");
            case INTEGER, DECIMAL -> value instanceof Number number
                    ? "{info}" + new BigDecimal(number.toString()).stripTrailingZeros().toPlainString() : none();
            case TEXT -> value == null || value.toString().isEmpty() ? Phrases.tr("{muted}Empty")
                    : "{letters}" + Values.inert(cut(value.toString()));
            case LINES -> Phrases.tr("{info}{0} {letters}lines", value instanceof List<?> list ? list.size() : 0);
            case ENTRIES -> entries(value instanceof Number number ? number.intValue() : 0);
            case SECTION -> Phrases.tr("{info}{0} {letters}settings", value instanceof Number number ? number.intValue() : 0);
            default -> value == null ? none() : "{muted}" + Values.inert(cut(String.valueOf(value)));
        };
    }

    /** The icon of a button. */
    static String icon(Field field, @Nullable Object value) {
        return switch (field.kind()) {
            case SECTION -> "BOOKSHELF";
            case ENTRIES -> "CHEST";
            case TOGGLE -> Boolean.TRUE.equals(value) ? "LIME_DYE" : "GRAY_DYE";
            case CHOICE -> "COMPARATOR";
            case INTEGER, DECIMAL -> "PAPER";
            case TIME -> "CLOCK";
            case TEXT -> "NAME_TAG";
            case MATERIAL -> value instanceof Material material && material.isItem() && !material.isAir()
                    ? material.name() : "BARRIER";
            case LINES -> "WRITABLE_BOOK";
            case LOCKED -> "STRUCTURE_VOID";
        };
    }

    /** What the clicks on a button do, one {@code ➥} line each. */
    static String hint(Field field) {
        return hint(field.kind());
    }

    /** What the clicks on a button of this kind do. */
    static String hint(Kind kind) {
        List<String> lines = switch (kind) {
            case SECTION, ENTRIES -> List.of(Phrases.tr("{warning}➥ Click {letters_black}» {letters}open"));
            case TOGGLE -> List.of(Phrases.tr("{warning}➥ Click {letters_black}» {letters}switch"));
            case CHOICE -> List.of(Phrases.tr("{warning}➥ Left-click {letters_black}» {letters}next"),
                    Phrases.tr("{warning}➥ Right-click {letters_black}» {letters}previous"));
            case INTEGER -> numbers("1", "10");
            case DECIMAL -> numbers("0.1", "1");
            case TIME, TEXT, MATERIAL, LINES -> List.of(Phrases.tr("{warning}➥ Click {letters_black}» {letters}change"));
            case LOCKED -> List.of(Phrases.tr("{letters_black}Edit it with {letters}/exylialib editor{letters_black}."));
        };
        return Lines.join(lines);
    }

    private static List<String> numbers(String step, String big) {
        return List.of(Phrases.tr("{warning}➥ Left-click {letters_black}» {letters}+{0}", step),
                Phrases.tr("{warning}➥ Right-click {letters_black}» {letters}-{0}", step),
                Phrases.tr("{warning}➥ Shift-click {letters_black}» {letters}±{0}", big),
                Phrases.tr("{warning}➥ Press Q {letters_black}» {letters}type a value"));
    }

    /** The comment lines as an Information block ending in a blank line; nothing when undocumented. */
    static String about(List<String> comments) {
        List<String> prose = new ArrayList<>();
        for (String comment : comments) {
            if (!comment.isBlank()) prose.add(comment.trim());
        }
        if (prose.isEmpty()) return Lines.OMIT;
        List<String> lines = new ArrayList<>();
        lines.add(Phrases.tr("{secondary}Information:"));
        for (String line : Lines.split(Lines.wrap(String.join(" ", prose), 38))) {
            lines.add(" {letters_black}▎ {letters}" + Values.inert(line));
        }
        lines.add("");
        return Lines.join(lines);
    }

    private static String none() {
        return Phrases.tr("{muted}None");
    }

    private static String cut(String text) {
        String line = text.replace('\n', ' ');
        return line.length() <= 40 ? line : line.substring(0, 39) + "…";
    }
}
