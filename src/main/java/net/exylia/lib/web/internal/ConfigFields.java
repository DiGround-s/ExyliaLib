package net.exylia.lib.web.internal;

import net.exylia.lib.config.Menu;
import net.exylia.lib.config.Schema;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.config.Time;
import net.exylia.lib.effect.Ticks;
import net.exylia.lib.text.Lines;
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
        if (type == String.class) return Kind.TEXT;
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
        Menu.Range range = field.hint(Menu.Range.class);
        if (range != null && range.step() > 0) return range.step();
        return field.kind() == Kind.DECIMAL ? 0.1 : 1;
    }

    /**
     * A stepped value kept inside the key's range. Stepping off the {@code none} value starts
     * from the range's edge; stepping below the range lands on {@code none} when there is one.
     */
    static double clamp(Field field, double from, double to) {
        Menu.Range range = field.hint(Menu.Range.class);
        if (range == null) return to;
        boolean hasNone = !Double.isNaN(range.none());
        if (hasNone && from == range.none()) return to > from ? range.min() : range.max();
        if (to < range.min()) return hasNone && from == range.min() ? range.none() : range.min();
        if (to > range.max()) return range.max();
        return to;
    }

    /** Whether a typed number may be written: inside the key's range, or its {@code none} value. */
    static boolean allowed(Field field, double value) {
        Menu.Range range = field.hint(Menu.Range.class);
        if (!Double.isFinite(value)) return false;
        if (range == null) return true;
        if (!Double.isNaN(range.none()) && value == range.none()) return true;
        return value >= range.min() && value <= range.max();
    }

    /** The range a typed number must be in, as the refusal says it. */
    static String[] bounds(Field field) {
        Menu.Range range = field.hint(Menu.Range.class);
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

    /** The value, coloured by what it is; anything typed is shown as written, never parsed. */
    static String display(Field field, @Nullable Object value) {
        return switch (field.kind()) {
            case SECTION -> Phrases.tr("{info}{0} {letters}settings", field.schema().nested() == null ? 0
                    : field.schema().nested().fields().size());
            case TOGGLE -> Boolean.TRUE.equals(value) ? Phrases.tr("{success}Enabled") : Phrases.tr("{error}Disabled");
            case CHOICE -> value instanceof Enum<?> constant ? "{highlight}" + words(constant.name()) : none();
            case INTEGER, DECIMAL -> number(field, value);
            case TIME -> {
                Menu.Range range = field.hint(Menu.Range.class);
                if (range != null && value instanceof Number number && number.doubleValue() == range.none()) yield none();
                Duration duration = duration(field, value);
                yield duration.isZero() ? none() : "{info}" + TimeFormats.render(duration, TimeFormats.Style.COMPACT) + " ⌚";
            }
            case MATERIAL -> value instanceof Material material ? "{highlight}" + material.name() : none();
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
        Menu.Range range = field.hint(Menu.Range.class);
        if (range != null && number.doubleValue() == range.none()) return none();
        BigDecimal amount = new BigDecimal(number.toString()).stripTrailingZeros();
        Menu.Format format = field.hint(Menu.Format.class);
        Menu.Style style = format == null ? Menu.Style.NUMBER : format.value();
        return switch (style) {
            case MONEY -> "{success}" + Values.inert(money(amount));
            case PERCENT -> "{info}" + amount.toPlainString() + "%";
            case NUMBER -> "{info}" + amount.toPlainString();
        };
    }

    private static String money(BigDecimal amount) {
        try {
            return Economy.format(null, amount);
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
            case TIME -> value == null ? none() : "{info}" + Values.inert(cut(String.valueOf(value))) + " ⌚";
            default -> value == null ? none() : "{muted}" + Values.inert(cut(String.valueOf(value)));
        };
    }

    /** The icon of a button: a switch's dye, a material itself, its {@link Menu.Icon}, or one chosen from its name. */
    static String icon(Field field, @Nullable Object value) {
        if (field.kind() == Kind.TOGGLE) return Boolean.TRUE.equals(value) ? "LIME_DYE" : "GRAY_DYE";
        if (field.kind() == Kind.MATERIAL && value instanceof Material material && material.isItem() && !material.isAir()) {
            return material.name();
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

    /** What the clicks on a button do, one {@code ➥} line each. */
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
            case SECTION, ENTRIES -> List.of(Phrases.tr("{warning}➥ Click {letters_black}» {letters}open"));
            case TOGGLE -> List.of(Phrases.tr("{warning}➥ Click {letters_black}» {letters}switch"));
            case CHOICE -> List.of(Phrases.tr("{warning}➥ Left-click {letters_black}» {letters}next"),
                    Phrases.tr("{warning}➥ Right-click {letters_black}» {letters}previous"));
            case INTEGER, DECIMAL -> numbers(BigDecimal.ONE, BigDecimal.TEN);
            case TIME, TEXT, MATERIAL, LINES -> List.of(Phrases.tr("{warning}➥ Click {letters_black}» {letters}change"));
            case LOCKED -> List.of(Phrases.tr("{letters_black}Edit it with {letters}/exylialib editor{letters_black}."));
        };
        return Lines.join(lines);
    }

    private static List<String> numbers(BigDecimal step, BigDecimal big) {
        String small = step.stripTrailingZeros().toPlainString();
        return List.of(Phrases.tr("{warning}➥ Left / Right-click {letters_black}» {letters}+{0} / -{0}", small),
                Phrases.tr("{warning}➥ Shift-click {letters_black}» {letters}±{0}", big.stripTrailingZeros().toPlainString()),
                Phrases.tr("{warning}➥ Press Q {letters_black}» {letters}type a value"));
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
            if (!line.isBlank()) shown.add(" {letters_black}▎ {letters}" + line);
        }
        if (shown.isEmpty()) return Lines.OMIT;
        List<String> out = new ArrayList<>();
        out.add(Phrases.tr("{secondary}Information:"));
        out.addAll(shown);
        out.add("");
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
        lines.add(Phrases.tr("{secondary}Information:"));
        for (String line : Lines.split(Lines.wrap(text, 34))) {
            lines.add(" {letters_black}▎ {letters}" + Values.inert(line));
        }
        lines.add("");
        return Lines.join(lines);
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
