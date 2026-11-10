package net.exylia.lib.web.internal;

import net.exylia.lib.config.Menu;
import net.exylia.lib.config.Schema;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.config.Time;
import net.exylia.lib.effect.Ticks;
import net.exylia.lib.text.Lines;
import net.exylia.lib.text.Lore;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Values;
import net.exylia.lib.util.TimeFormats;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
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
        /** A block of named entries, or a list of blocks: opens the list of entries. */
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
        /** A {@link Menu.Sound} string: left forward, right back through the house sounds, Q to type one. */
        SOUND,
        /** Anything else (a map of lists, a list of numbers): shown, edited in the web editor. */
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
            Time time = annotation(owner, schema.name(), Time.class);
            return time == null ? null : time.value();
        }

        /** One of the component's annotations, such as a {@link Menu.Range}; {@code null} when absent. */
        <A extends Annotation> @Nullable A hint(Class<A> type) {
            return annotation(owner, schema.name(), type);
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
            if (annotation(schema.type(), field.name(), Menu.Hidden.class) != null) continue;
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
            if (annotation(owner, field.name(), Time.class) != null) return Kind.TIME;
            return whole ? Kind.INTEGER : Kind.DECIMAL;
        }
        if (type == String.class) {
            if (annotation(owner, field.name(), Menu.Sound.class) != null) return Kind.SOUND;
            if (annotation(owner, field.name(), Menu.Material.class) != null) return Kind.MATERIAL;
            if (choices(owner, field.name()) != null) return Kind.CHOICE;
            return Kind.TEXT;
        }
        if (List.class.isAssignableFrom(type) && field.generic() instanceof ParameterizedType list) {
            java.lang.reflect.Type element = list.getActualTypeArguments()[0];
            if (element == String.class) return Kind.LINES;
            if (element instanceof Class<?> record && record.isRecord()) return Kind.ENTRIES;
        }
        if (java.util.Map.class.isAssignableFrom(type) && field.generic() instanceof ParameterizedType map
                && map.getActualTypeArguments()[1] instanceof Class<?> value && editable(value)) {
            return Kind.ENTRIES;
        }
        return Kind.LOCKED;
    }

    /** Whether a map's values can each be edited in game: a record, or one value of a kind a click edits. */
    private static boolean editable(Class<?> value) {
        return value.isRecord() || value.isEnum() || value == String.class || value == Boolean.class
                || value == Duration.class || Number.class.isAssignableFrom(value);
    }

    /** How many entries a map or list holds. */
    static int size(@Nullable Object value) {
        if (value instanceof java.util.Map<?, ?> map) return map.size();
        if (value instanceof java.util.Collection<?> list) return list.size();
        return value instanceof Number number ? number.intValue() : 0;
    }

    static <A extends Annotation> @Nullable A annotation(Class<?> owner, String component, Class<A> type) {
        if (!owner.isRecord()) return null;
        for (RecordComponent part : owner.getRecordComponents()) {
            if (part.getName().equals(component)) return part.getAnnotation(type);
        }
        return null;
    }

    /** The languages Exylia plugins ship, after {@code default}, which follows ExyliaLib's. */
    static final List<String> LANGUAGES = List.of("default", "en", "es", "fr", "pt", "ru", "tr");
    private static final List<String> LANGUAGE_NAMES =
            List.of("Default", "English", "Español", "Français", "Português", "Русский", "Türkçe");

    /**
     * The values a text key cycles through and what each is called, or {@code null} when it is
     * free text.
     */
    static @Nullable List<String[]> choices(Class<?> owner, String component) {
        if (annotation(owner, component, Menu.Language.class) != null) {
            List<String[]> out = new ArrayList<>();
            for (int i = 0; i < LANGUAGES.size(); i++) out.add(new String[]{LANGUAGES.get(i), LANGUAGE_NAMES.get(i)});
            return out;
        }
        Menu.Choices choices = annotation(owner, component, Menu.Choices.class);
        if (choices == null || choices.value().length == 0) return null;
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < choices.value().length; i++) {
            String label = i < choices.labels().length ? choices.labels()[i] : words(choices.value()[i]);
            out.add(new String[]{choices.value()[i], label});
        }
        return out;
    }

    /** A key's resolved {@link Menu.Range}: what it says, filled in from its preset. */
    record Bounds(double min, double max, double step, double none) {
        boolean hasNone() {
            return !Double.isNaN(none);
        }
    }

    /** The key's bounds, or {@code null} when it declares no {@link Menu.Range}. */
    static @Nullable Bounds bounds(Field field) {
        Menu.Range range = field.hint(Menu.Range.class);
        if (range == null) return null;
        Menu.Preset preset = range.preset();
        double min = range.min() != -Double.MAX_VALUE ? range.min() : preset.min();
        double max = range.max() != Double.MAX_VALUE ? range.max() : preset.max();
        double step = range.step() > 0 ? range.step() : preset.step();
        return new Bounds(min, max, step, range.none());
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
            case CHOICE -> field.schema().type() == String.class
                    ? nextChoice(choices(field.owner(), field.schema().name()), current, direction)
                    : next(field.schema().type(), current, direction);
            case SOUND -> nextSound(current == null ? "" : current.toString(), direction);
            case INTEGER -> {
                long now = current instanceof Number number ? number.longValue() : 0;
                long step = Math.max(1, Math.round(step(field)));
                long next = Math.round(clamp(field, now, now + (double) direction * step * (big ? 10 : 1)));
                yield field.schema().type() == int.class || field.schema().type() == Integer.class
                        ? (Object) (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, next)) : next;
            }
            case DECIMAL -> {
                BigDecimal now = current instanceof Number number ? new BigDecimal(number.toString()) : BigDecimal.ZERO;
                BigDecimal step = BigDecimal.valueOf(step(field)).multiply(BigDecimal.valueOf(big ? 10 : 1));
                double next = now.add(step.multiply(BigDecimal.valueOf(direction))).doubleValue();
                yield BigDecimal.valueOf(clamp(field, now.doubleValue(), next)).stripTrailingZeros().doubleValue();
            }
            default -> null;
        };
    }

    /** One click's step: the key's {@link Menu.Range#step()}, or 1 for a whole number and 0.1 for a decimal. */
    static double step(Field field) {
        Bounds range = bounds(field);
        if (range != null && range.step() > 0) return range.step();
        return field.kind() == Kind.DECIMAL ? 0.1 : 1;
    }

    /**
     * A stepped value kept inside the key's range. Stepping off the {@code none} value starts
     * from the range's edge; stepping below the range lands on {@code none} when there is one.
     */
    static double clamp(Field field, double from, double to) {
        Bounds range = bounds(field);
        if (range == null) return to;
        boolean hasNone = range.hasNone();
        if (hasNone && from == range.none()) return to > from ? range.min() : range.max();
        if (to < range.min()) return hasNone && from == range.min() ? range.none() : range.min();
        if (to > range.max()) return range.max();
        return to;
    }

    /** Whether a typed number may be written: inside the key's range, or its {@code none} value. */
    static boolean allowed(Field field, double value) {
        Bounds range = bounds(field);
        if (!Double.isFinite(value)) return false;
        if (range == null) return true;
        if (range.hasNone() && value == range.none()) return true;
        return value >= range.min() && value <= range.max();
    }

    /** The range a typed number must be in, as the refusal says it. */
    static String[] limits(Field field) {
        Bounds range = bounds(field);
        return range == null ? new String[]{"", ""} : new String[]{number(range.min()), number(range.max())};
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

    /** The text choice after (or before) {@code current}, wrapping; from the first when it is none of them. */
    static @Nullable String nextChoice(@Nullable List<String[]> choices, @Nullable Object current, int direction) {
        if (choices == null || choices.isEmpty()) return null;
        int at = -1;
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i)[0].equalsIgnoreCase(String.valueOf(current))) at = i;
        }
        if (at < 0) return choices.get(direction > 0 ? 0 : choices.size() - 1)[0];
        return choices.get(Math.floorMod(at + direction, choices.size()))[0];
    }

    /** The quiet interface sounds a {@link Menu.Sound} key walks through, as resource keys. */
    static final List<String> SOUNDS = List.of(
            "ui.button.click", "entity.experience_orb.pickup", "block.note_block.pling", "block.note_block.chime",
            "block.note_block.bell", "block.note_block.hat", "block.note_block.bit", "item.book.page_turn",
            "item.bundle.remove_one", "entity.villager.no");

    /**
     * The next house sound, keeping the volume and pitch written after the name and the way the
     * name is written: {@code ENTITY_X_Y} stays an upper-case constant, {@code minecraft:} stays.
     */
    static String nextSound(String current, int direction) {
        int bar = current.indexOf('|');
        String name = bar < 0 ? current : current.substring(0, bar);
        String rest = bar < 0 ? "" : current.substring(bar);
        int at = SOUNDS.indexOf(soundKey(name));
        String next = at < 0 ? SOUNDS.get(direction > 0 ? 0 : SOUNDS.size() - 1)
                : SOUNDS.get(Math.floorMod(at + direction, SOUNDS.size()));
        boolean constant = !name.isEmpty() && name.equals(name.toUpperCase(Locale.ROOT)) && name.indexOf('.') < 0;
        String written = constant ? next.replace('.', '_').toUpperCase(Locale.ROOT)
                : name.startsWith("minecraft:") ? "minecraft:" + next : next;
        return written + rest;
    }

    /** A written sound name as a resource key: {@code ENTITY_VILLAGER_NO} and {@code minecraft:entity.villager.no} alike. */
    static String soundKey(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
        if (key.indexOf('.') >= 0) return key;
        for (String known : SOUNDS) {
            if (known.replace('.', '_').equals(key)) return known;
        }
        return key.replace('_', '.');
    }

    /**
     * Whether writing {@code value} keeps the key's {@link Menu.AtLeast} / {@link Menu.AtMost}
     * order: {@code null} when it does, else the other key's label and value, and
     * {@code "least"} or {@code "most"}.
     */
    static @Nullable String[] order(Schema root, Object values, Field field, double value) {
        Menu.AtLeast least = field.hint(Menu.AtLeast.class);
        Menu.AtMost most = field.hint(Menu.AtMost.class);
        // The key's own "none" (such as -1 for no limit) has no order to keep.
        Bounds own = bounds(field);
        if (own != null && own.hasNone() && value == own.none()) return null;
        for (String[] rule : new String[][]{{least == null ? null : least.value(), "least"},
                {most == null ? null : most.value(), "most"}}) {
            if (rule[0] == null) continue;
            String key = rule[0].indexOf('.') >= 0 ? rule[0]
                    : field.key().lastIndexOf('.') < 0 ? rule[0] : field.key().substring(0, field.key().lastIndexOf('.') + 1) + rule[0];
            if (!(read(root, values, key) instanceof Number other)) continue;
            Field otherField = field(root, key);
            Bounds bounds = otherField == null ? null : bounds(otherField);
            if (bounds != null && bounds.hasNone() && other.doubleValue() == bounds.none()) continue;
            boolean broken = rule[1].equals("least") ? value < other.doubleValue() : value > other.doubleValue();
            if (broken) {
                return new String[]{otherField == null ? title(key) : label(otherField),
                        otherField == null ? String.valueOf(other) : display(otherField, other), rule[1]};
            }
        }
        return null;
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

    /** What a key is called: its {@link Menu.Label}, or its YAML key in words. In capitals. */
    static String label(Field field) {
        Menu.Label label = field.hint(Menu.Label.class);
        return label != null && !label.value().isBlank() ? label.value().toUpperCase(Locale.ROOT) : title(field.name());
    }

    /** A constant or a key in words, capitalised: {@code soft-reset} as {@code Soft reset}. */
    static String words(String written) {
        String spaced = written.replace('-', ' ').replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String number(double value) {
        if (value >= 1e15 || value <= -1e15) return value > 0 ? "∞" : "-∞";
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** {@link #display(Field, Object)}, with a {@code MONEY} amount read in the currency its {@link Menu.Format} names. */
    static String display(Field field, @Nullable Object value, Schema root, Object values) {
        if ((field.kind() == Kind.INTEGER || field.kind() == Kind.DECIMAL) && value instanceof Number) {
            String currency = currencyOf(field, root, values);
            if (currency != null) return CURRENCY.with(currency, () -> display(field, value));
        }
        return display(field, value);
    }

    /**
     * The currency id a {@code MONEY} key is in: what its {@link Menu.Format#currency} key holds,
     * {@code ""} for the default, {@code null} when the key is not money in another currency.
     */
    static @Nullable String currencyOf(Field field, Schema root, Object values) {
        Menu.Format format = field.hint(Menu.Format.class);
        if (format == null || format.value() != Menu.Style.MONEY || format.currency().isBlank()) return null;
        String key = format.currency();
        int dot = field.key().lastIndexOf('.');
        Object held = dot < 0 || key.contains(".") ? read(root, values, key) : read(root, values, field.key().substring(0, dot + 1) + key);
        if (held == null && !key.contains(".") && dot >= 0) held = read(root, values, key);
        return held == null ? "" : held.toString().trim();
    }

    /** The currency {@link #money} formats in while a display is worked out. */
    private static final Scoped CURRENCY = new Scoped();

    private static final class Scoped {
        private final ThreadLocal<String> current = new ThreadLocal<>();

        <T> T with(String value, java.util.function.Supplier<T> body) {
            String before = current.get();
            current.set(value);
            try {
                return body.get();
            } finally {
                current.set(before);
            }
        }

        @Nullable String get() {
            return current.get();
        }
    }

    /** Whether a text key names a currency ({@link Menu.Currency}). */
    static boolean currency(Field field) {
        return field.kind() == Kind.TEXT && field.hint(Menu.Currency.class) != null;
    }

    /** Whether a list of text holds item names ({@link Menu.Material} on a list). */
    static boolean materials(Field field) {
        return field.kind() == Kind.LINES && field.hint(Menu.Material.class) != null;
    }

    /** A currency's name, or "Default currency" for the empty id. */
    static String currencyName(@Nullable String id) {
        if (id == null || id.isBlank()) return Phrases.tr("Default currency");
        try {
            return Values.inert(Economy.info(id).name());
        } catch (RuntimeException unknown) {
            return Values.inert(id);
        }
    }

    /** The value, coloured by what it is; anything typed is shown as written, never parsed. */
    static String display(Field field, @Nullable Object value) {
        if (currency(field)) return "{highlight}" + currencyName(value == null ? "" : value.toString());
        if (materials(field)) {
            int count = value instanceof List<?> list ? list.size() : 0;
            return count == 0 ? none() : Phrases.tr("{info}{0} {letters}items", count);
        }
        return switch (field.kind()) {
            case SECTION -> Phrases.tr("{info}{0} {letters}settings", field.schema().nested() == null ? 0
                    : field.schema().nested().fields().size());
            case TOGGLE -> Boolean.TRUE.equals(value) ? Phrases.tr("{success}Enabled") : Phrases.tr("{error}Disabled");
            case CHOICE -> value instanceof Enum<?> constant ? "{highlight}" + words(constant.name())
                    : value == null || value.toString().isEmpty() ? none() : "{highlight}" + choiceLabel(field, value.toString());
            case SOUND -> value == null || value.toString().isBlank() ? none()
                    : "{highlight}" + Values.inert(words(soundKey(value.toString().split("\\|")[0]).replace('.', ' ')));
            case INTEGER, DECIMAL -> number(field, value);
            case TIME -> {
                Bounds range = bounds(field);
                if (range != null && value instanceof Number number && number.doubleValue() == range.none()) yield none();
                Duration duration = duration(field, value);
                yield duration.isZero() ? none() : "{info}" + TimeFormats.render(duration, TimeFormats.Style.COMPACT);
            }
            case MATERIAL -> value instanceof Material material ? "{highlight}" + material.name()
                    : value == null || value.toString().isBlank() ? none() : "{highlight}" + Values.inert(cut(value.toString()));
            case TEXT -> value == null || value.toString().isEmpty() ? Phrases.tr("{muted}Empty")
                    : "{letters}" + Values.inert(cut(value.toString()));
            case LINES -> Phrases.tr("{info}{0} {letters}lines", value instanceof List<?> list ? list.size() : 0);
            case LOCKED -> value == null ? none() : "{muted}" + Values.inert(cut(String.valueOf(value)));
            case ENTRIES -> entries(size(value));
        };
    }

    /** A number, read as its {@link Menu.Format} says. */
    private static String number(Field field, @Nullable Object value) {
        if (!(value instanceof Number number)) return none();
        Bounds range = bounds(field);
        if (range != null && number.doubleValue() == range.none()) return none();
        BigDecimal amount = new BigDecimal(number.toString()).stripTrailingZeros();
        Menu.Unit unit = field.hint(Menu.Unit.class);
        Long millis = unit == null ? null : unitMillis(unit.value());
        if (millis != null) {
            Duration duration = Duration.ofMillis(Math.max(0, Math.round(number.doubleValue() * millis)));
            return duration.isZero() ? none() : "{info}" + TimeFormats.render(duration, TimeFormats.Style.COMPACT);
        }
        Menu.Format format = field.hint(Menu.Format.class);
        Menu.Style style = format == null ? Menu.Style.NUMBER : format.value();
        String shown = switch (style) {
            case MONEY -> "{success}" + Values.inert(money(amount));
            case PERCENT -> "{info}" + amount.toPlainString() + "%";
            case FRACTION -> "{info}" + amount.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
            case MULTIPLIER -> "{info}×" + amount.toPlainString();
            case NUMBER -> "{info}" + amount.toPlainString();
        };
        return unit == null || unit.value().isBlank() ? shown : shown + " {letters}" + Values.inert(unit.value());
    }

    /** How long one of a {@link Menu.Unit} of time is, or {@code null} for a unit that is not time. */
    static @Nullable Long unitMillis(String unit) {
        return switch (unit.trim().toLowerCase(Locale.ROOT)) {
            case "ms", "millis", "milliseconds" -> 1L;
            case "t", "tick", "ticks" -> 50L;
            case "s", "sec", "secs", "second", "seconds" -> 1_000L;
            case "m", "min", "mins", "minute", "minutes" -> 60_000L;
            case "h", "hour", "hours" -> 3_600_000L;
            case "d", "day", "days" -> 86_400_000L;
            case "w", "week", "weeks" -> 604_800_000L;
            default -> null;
        };
    }

    /** What a text choice is called: its label, or the value in words when it is none of the choices. */
    private static String choiceLabel(Field field, String value) {
        List<String[]> choices = choices(field.owner(), field.schema().name());
        if (choices != null) {
            for (String[] choice : choices) {
                if (choice[0].equalsIgnoreCase(value)) return Values.inert(choice[1]);
            }
        }
        return Values.inert(cut(words(value)));
    }

    private static String money(BigDecimal amount) {
        try {
            return Economy.format(CURRENCY.get(), amount);
        } catch (RuntimeException noEconomy) {
            return amount.toPlainString();
        }
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
            case CHOICE, MATERIAL -> value == null ? none() : "{highlight}" + Values.inert(cut(String.valueOf(value)));
            case TIME -> value == null ? none() : "{info}" + Values.inert(cut(String.valueOf(value)));
            default -> value == null ? none() : "{muted}" + Values.inert(cut(String.valueOf(value)));
        };
    }

    /** The icon of a button: a switch's dye, a material itself, its {@link Menu.Icon}, or one chosen from its name. */
    static String icon(Field field, @Nullable Object value) {
        if (field.kind() == Kind.TOGGLE) return Boolean.TRUE.equals(value) ? "LIME_DYE" : "GRAY_DYE";
        if (currency(field) && field.hint(Menu.Icon.class) == null) return "GOLD_INGOT";
        if (field.kind() == Kind.MATERIAL && value instanceof Material material && material.isItem() && !material.isAir()) {
            return material.name();
        }
        if (field.kind() == Kind.MATERIAL && value instanceof String written) {
            Material material = Material.matchMaterial(written.trim());
            if (material != null && material.isItem() && !material.isAir()) return material.name();
        }
        Menu.Icon icon = field.hint(Menu.Icon.class);
        if (icon != null && !icon.value().isBlank()) return icon.value();
        String named = named(field.name());
        if (named != null && field.kind() != Kind.LOCKED) return named;
        return switch (field.kind()) {
            case SECTION -> "BOOKSHELF";
            case ENTRIES -> "CHEST";
            case TOGGLE -> Boolean.TRUE.equals(value) ? "LIME_DYE" : "GRAY_DYE";
            case CHOICE -> "COMPARATOR";
            case INTEGER, DECIMAL -> "PAPER";
            case TIME -> "CLOCK";
            case TEXT -> "NAME_TAG";
            case MATERIAL -> "BARRIER";
            case LINES -> "WRITABLE_BOOK";
            case SOUND -> "NOTE_BLOCK";
            case LOCKED -> "STRUCTURE_VOID";
        };
    }

    /** Words in a key that say what it is about, and the item that shows it. First match wins. */
    private static final List<String[]> NAMED = List.of(
            new String[]{"price,cost,fee,tax,money,balance,pay,reward,worth,bet", "GOLD_NUGGET"},
            new String[]{"percent,chance,ratio,rate,multiplier,odds", "EXPERIENCE_BOTTLE"},
            new String[]{"cooldown,delay,duration,time,seconds,minutes,hours,ticks,interval,timeout,period", "CLOCK"},
            new String[]{"radius,distance,range,height,size", "COMPASS"},
            new String[]{"max,limit,cap,amount,count,per,min", "HOPPER"},
            new String[]{"sound,volume,pitch", "NOTE_BLOCK"},
            new String[]{"particle,particles,effect,effects,glow", "BLAZE_POWDER"},
            new String[]{"world,worlds,region,regions", "GRASS_BLOCK"},
            new String[]{"broadcast,announce,announcement,notify,notification,alert", "BELL"},
            new String[]{"message,messages,chat,text,title,prefix,format", "OAK_SIGN"},
            new String[]{"debug,log,logs,metrics", "REDSTONE_TORCH"},
            new String[]{"language,lang,locale", "BOOK"},
            new String[]{"database,storage,redis,mysql,cache,sync", "ENDER_CHEST"},
            new String[]{"permission,permissions,rank,ranks", "TRIPWIRE_HOOK"},
            new String[]{"command,commands", "COMMAND_BLOCK"},
            new String[]{"webhook,discord,url,link", "PAPER"},
            new String[]{"teleport,spawn,location,warp", "ENDER_PEARL"},
            new String[]{"item,items,material,icon", "ITEM_FRAME"},
            new String[]{"update,updates,version", "ANVIL"});

    /** The item a key's name suggests, or {@code null} when no word in it does. */
    static @Nullable String named(String key) {
        for (String word : key.toLowerCase(Locale.ROOT).split("[-_.]")) {
            for (String[] entry : NAMED) {
                for (String candidate : entry[0].split(",")) {
                    if (candidate.equals(word)) return entry[1];
                }
            }
        }
        return null;
    }

    /** What the clicks on a button do, one {@code »} line each. */
    static String hint(Field field) {
        if (field.kind() == Kind.INTEGER || field.kind() == Kind.DECIMAL) {
            BigDecimal step = BigDecimal.valueOf(step(field));
            return Lines.join(numbers(step, step.multiply(BigDecimal.TEN)));
        }
        return hint(field.kind());
    }

    /** What the clicks on a button of this kind do. */
    static String hint(Kind kind) {
        List<String> lines = switch (kind) {
            case SECTION, ENTRIES -> List.of(act(Phrases.tr("Click {letters_black}· {letters}open")));
            case TOGGLE -> List.of(act(Phrases.tr("Click {letters_black}· {letters}switch")));
            case CHOICE -> List.of(act(Phrases.tr("Left-click {letters_black}· {letters}next")),
                    act(Phrases.tr("Right-click {letters_black}· {letters}previous")));
            case INTEGER, DECIMAL -> numbers(BigDecimal.ONE, BigDecimal.TEN);
            case TIME, TEXT, MATERIAL, LINES -> List.of(act(Phrases.tr("Click {letters_black}· {letters}change")));
            case SOUND -> List.of(act(Phrases.tr("Left-click {letters_black}· {letters}next")),
                    act(Phrases.tr("Right-click {letters_black}· {letters}previous")),
                    act(Phrases.tr("Press Q {letters_black}· {letters}type a sound")));
            case LOCKED -> List.of(Phrases.tr("{letters_black}Edit it with {letters}/exylialib editor{letters_black}."));
        };
        return Lines.join(lines);
    }

    /** An action line in the settings screens' tone. */
    static String act(String action) {
        return Lore.Tone.PRIMARY.colour() + "{icon:click} " + action;
    }

    /** The label opening an Information block, after the blank line that separates it. */
    static String information() {
        return Lore.Tone.PRIMARY.colour() + "✎ " + Phrases.tr("Information");
    }

    /** The label opening a Status block. */
    static String status() {
        return Lore.Tone.PRIMARY.colour() + "✦ " + Phrases.tr("Status");
    }

    private static List<String> numbers(BigDecimal step, BigDecimal big) {
        String small = step.stripTrailingZeros().toPlainString();
        return List.of(act(Phrases.tr("Left / Right-click {letters_black}· {letters}+{0} / -{0}", small)),
                act(Phrases.tr("Shift-click {letters_black}· {letters}±{0}", big.stripTrailingZeros().toPlainString())),
                act(Phrases.tr("Press Q {letters_black}· {letters}type a value")));
    }

    /**
     * A key's Information block: its {@link Menu.About}, or the first sentence of its comment,
     * wrapped. Nothing when it has neither.
     */
    static String about(Field field) {
        Menu.About about = field.hint(Menu.About.class);
        if (about != null) return block(List.of(about.value()));
        return summary(field.schema().comments());
    }

    /** Lines a plugin wrote for the screen, as an Information block; nothing when there are none. */
    static String block(List<String> lines) {
        List<String> shown = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank()) shown.add(" {letters}" + line);
        }
        if (shown.isEmpty()) return Lines.OMIT;
        List<String> out = new ArrayList<>();
        out.add("");
        out.add(information());
        out.addAll(shown);
        return Lines.join(out);
    }

    /** The first sentence of a comment, wrapped, as an Information block; nothing when undocumented. */
    static String summary(List<String> comments) {
        List<String> prose = new ArrayList<>();
        for (String comment : comments) {
            if (!comment.isBlank()) prose.add(comment.trim());
        }
        if (prose.isEmpty()) return Lines.OMIT;
        String text = String.join(" ", prose);
        java.util.regex.Matcher end = java.util.regex.Pattern.compile("[.!?](\\s|$)").matcher(text);
        if (end.find()) text = text.substring(0, end.start() + 1);
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(information());
        for (String line : Lines.split(Lines.wrap(text, 34))) {
            lines.add(" {letters}" + Values.inert(line));
        }
        return Lines.join(lines);
    }

    /** The comment lines as an Information block after a blank line; nothing when undocumented. */
    static String about(List<String> comments) {
        List<String> prose = new ArrayList<>();
        for (String comment : comments) {
            if (!comment.isBlank()) prose.add(comment.trim());
        }
        if (prose.isEmpty()) return Lines.OMIT;
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(information());
        for (String line : Lines.split(Lines.wrap(String.join(" ", prose), 34))) {
            lines.add(" {letters}" + Values.inert(line));
        }
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
