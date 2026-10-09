package net.exylia.lib.settings;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * One preference a player can change from {@code /settings}.
 *
 * <p>Built once, registered with {@link Settings#register}, and read back with
 * {@link Settings#enabled}, {@link Settings#value} or {@link Settings#number}.
 *
 * <pre>{@code
 * Settings.register(this, Setting.toggle("trade-requests", true)
 *         .category("requests")
 *         .icon("EMERALD")
 *         .name("Trade requests")
 *         .description("Whether players can ask you", "to {highlight}trade{letters}.")
 *         .build());
 *
 * Settings.register(this, Setting.choice("chat-mode", "all", "all", "friends", "none")
 *         .label("all", "Everybody").label("friends", "Friends only").label("none", "Nobody")
 *         .build());
 *
 * Settings.register(this, Setting.number("render-distance", 8, 2, 16, 1)
 *         .perServer()
 *         .build());
 * }</pre>
 *
 * <h2>Values</h2>
 * Every value is kept as text: {@code true}/{@code false} for a toggle, an
 * option id for a choice, a number for a number. Only a value that differs
 * from the default is stored, so a default changed in a later release reaches
 * every player who never touched the setting, with no migration.
 *
 * <h2>Text</h2>
 * {@link Builder#name} is plain text, drawn in the menu's own colours and in
 * capitals. {@link Builder#description} lines are body text and may carry
 * palette tokens such as {@code {highlight}}; each one becomes one lore line.
 *
 * @since 1.261.0
 */
public final class Setting {

    /** What a setting holds, and so how the menu changes it. */
    public enum Kind {
        /** On or off; a click flips it. */
        TOGGLE,
        /** One of a fixed list of option ids; left click goes forward, right click back. */
        CHOICE,
        /** A number between a minimum and a maximum; left click adds a step, right click takes one. */
        NUMBER
    }

    /**
     * Where a setting is kept when it is not the library's table — a plugin
     * that already had a column for it.
     *
     * <p>Called on the thread that asks: memory only, never a database.
     */
    public interface Store {

        /**
         * The player's current value, as text.
         *
         * @param player whose value
         * @return the value, or {@code null} for the default
         */
        @Nullable String get(@NotNull Player player);

        /**
         * Stores a new value, already checked against the setting.
         *
         * @param player whose value
         * @param value  the new value, as text
         */
        void set(@NotNull Player player, @NotNull String value);

        /**
         * Whether the player's value has been read, so a click can be trusted.
         *
         * @param player whose value
         * @return {@code true} when it is known
         */
        default boolean loaded(@NotNull Player player) {
            return true;
        }
    }

    /** The category broadcast channels are kept under, which the menu shows apart. */
    public static final String ANNOUNCEMENTS = "announcements";

    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]{1,64}");

    private final String key;
    private final Kind kind;
    private final String defaultValue;
    private final List<String> options;
    private final Map<String, String> labels;
    private final double min;
    private final double max;
    private final double step;
    private final String category;
    private final String icon;
    private final String name;
    private final List<String> description;
    private final @Nullable String permission;
    private final boolean perServer;
    private final @Nullable BooleanSupplier available;
    private final @Nullable String unavailableReason;
    private final @Nullable Store store;

    private Setting(Builder builder) {
        this.key = builder.key;
        this.kind = builder.kind;
        this.defaultValue = builder.defaultValue;
        this.options = List.copyOf(builder.options);
        this.labels = Map.copyOf(builder.labels);
        this.min = builder.min;
        this.max = builder.max;
        this.step = builder.step;
        this.category = builder.category;
        this.icon = builder.icon;
        this.name = builder.name == null ? key : builder.name;
        this.description = List.copyOf(builder.description);
        this.permission = builder.permission;
        this.perServer = builder.perServer;
        this.available = builder.available;
        this.unavailableReason = builder.unavailableReason;
        this.store = builder.store;
    }

    /**
     * A setting that is on or off.
     *
     * @param key          its id inside the plugin, lower case: {@code death-messages}
     * @param defaultValue what a player who never touched it has
     * @return a builder
     */
    public static @NotNull Builder toggle(@NotNull String key, boolean defaultValue) {
        return new Builder(key, Kind.TOGGLE, Boolean.toString(defaultValue));
    }

    /**
     * A setting that is one of a fixed list of options.
     *
     * @param key          its id inside the plugin
     * @param defaultValue the option a player who never touched it has
     * @param options      every option id, in the order a click walks them
     * @return a builder
     */
    public static @NotNull Builder choice(@NotNull String key, @NotNull String defaultValue,
                                          @NotNull String... options) {
        Builder builder = new Builder(key, Kind.CHOICE, defaultValue);
        builder.options.addAll(List.of(options));
        return builder;
    }

    /**
     * A setting that is a number.
     *
     * @param key          its id inside the plugin
     * @param defaultValue what a player who never touched it has
     * @param min          the lowest value
     * @param max          the highest value
     * @param step         how much one click changes it
     * @return a builder
     */
    public static @NotNull Builder number(@NotNull String key, double defaultValue, double min, double max,
                                          double step) {
        Builder builder = new Builder(key, Kind.NUMBER, format(defaultValue));
        builder.min = min;
        builder.max = max;
        builder.step = step;
        return builder;
    }

    /** Its id inside the plugin. */
    public @NotNull String key() {
        return key;
    }

    /** What it holds. */
    public @NotNull Kind kind() {
        return kind;
    }

    /** The default, as text. */
    public @NotNull String defaultValue() {
        return defaultValue;
    }

    /** A choice's option ids, in order; empty for the other kinds. */
    public @NotNull List<String> options() {
        return options;
    }

    /**
     * What an option is called on screen.
     *
     * @param option the option id
     * @return its label, or the id itself when none was given
     */
    public @NotNull String label(@NotNull String option) {
        return labels.getOrDefault(option, option);
    }

    /** A number's lowest value. */
    public double min() {
        return min;
    }

    /** A number's highest value. */
    public double max() {
        return max;
    }

    /** How much one click changes a number. */
    public double step() {
        return step;
    }

    /** The category it is listed under. */
    public @NotNull String category() {
        return category;
    }

    /** What its button is drawn as: anything a menu's {@code material:} accepts. */
    public @NotNull String icon() {
        return icon;
    }

    /** Its name, plain text. */
    public @NotNull String name() {
        return name;
    }

    /** Its description, one lore line each. */
    public @NotNull List<String> description() {
        return description;
    }

    /** The permission it needs, or {@code null} when everybody has it. */
    public @Nullable String permission() {
        return permission;
    }

    /** Whether each server keeps its own value rather than the network sharing one. */
    public boolean perServer() {
        return perServer;
    }

    /** The plugin's own store, or {@code null} when the library keeps it. */
    public @Nullable Store store() {
        return store;
    }

    /**
     * Whether what the setting acts on is running. One that is not is shown
     * disabled rather than offered.
     *
     * @return {@code true} unless an availability check says otherwise
     */
    public boolean available() {
        try {
            return available == null || available.getAsBoolean();
        } catch (RuntimeException broken) {
            return false;
        }
    }

    /** Why it is not available, or {@code null} for the menu's own wording. */
    public @Nullable String unavailableReason() {
        return unavailableReason;
    }

    /** Whether a player may change it. */
    public boolean allowed(@NotNull Player player) {
        return permission == null || player.hasPermission(permission);
    }

    /**
     * A value checked against this setting.
     *
     * @param value what to store
     * @return the value as it is stored, or {@code null} when it is not one this setting can hold
     */
    public @Nullable String normalise(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return switch (kind) {
            case TOGGLE -> trimmed.equalsIgnoreCase("true") ? "true"
                    : trimmed.equalsIgnoreCase("false") ? "false" : null;
            case CHOICE -> options.contains(trimmed) ? trimmed : null;
            case NUMBER -> {
                try {
                    double number = Double.parseDouble(trimmed);
                    yield Double.isFinite(number) ? format(Math.max(min, Math.min(max, number))) : null;
                } catch (NumberFormatException notANumber) {
                    yield null;
                }
            }
        };
    }

    /**
     * The value one click away.
     *
     * @param current   the value now
     * @param direction {@code 1} forward, {@code -1} back
     * @return the next value
     */
    public @NotNull String next(@NotNull String current, int direction) {
        return switch (kind) {
            case TOGGLE -> Boolean.toString(!Boolean.parseBoolean(current));
            case CHOICE -> {
                int at = Math.max(0, options.indexOf(current));
                yield options.get(Math.floorMod(at + direction, options.size()));
            }
            case NUMBER -> {
                String moved = normalise(format(parse(current) + direction * step));
                yield moved == null ? defaultValue : moved;
            }
        };
    }

    static double parse(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException | NullPointerException notANumber) {
            return 0;
        }
    }

    /** A number as text: {@code 8} rather than {@code 8.0}. */
    static String format(double number) {
        return number == Math.rint(number) && Math.abs(number) < 1e15
                ? Long.toString((long) number) : Double.toString(number);
    }

    @Override
    public String toString() {
        return "Setting[" + key + ", " + kind + ", default " + defaultValue + "]";
    }

    /** Builds a {@link Setting}. */
    public static final class Builder {

        private final String key;
        private final Kind kind;
        private final String defaultValue;
        private final List<String> options = new ArrayList<>();
        private final Map<String, String> labels = new LinkedHashMap<>();
        private double min;
        private double max;
        private double step = 1;
        private String category = "general";
        private String icon = "PAPER";
        private @Nullable String name;
        private final List<String> description = new ArrayList<>();
        private @Nullable String permission;
        private boolean perServer;
        private @Nullable BooleanSupplier available;
        private @Nullable String unavailableReason;
        private @Nullable Store store;

        private Builder(String key, Kind kind, String defaultValue) {
            Objects.requireNonNull(key, "key");
            if (!KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("A setting key is lower case letters, digits, '_', '.' or '-': "
                        + key);
            }
            this.key = key;
            this.kind = kind;
            this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        }

        /**
         * The category it is listed under; settings sharing one share a page.
         * Describe it with {@link Settings#category}. {@code general} by default.
         */
        public @NotNull Builder category(@NotNull String category) {
            this.category = category.toLowerCase(Locale.ROOT);
            return this;
        }

        /** What its button is drawn as: a material name, or anything a menu's {@code material:} accepts. */
        public @NotNull Builder icon(@NotNull String icon) {
            this.icon = icon;
            return this;
        }

        /** Its name, plain text. The key by default. */
        public @NotNull Builder name(@NotNull String name) {
            this.name = name;
            return this;
        }

        /** Its description, one lore line each, palette tokens allowed. */
        public @NotNull Builder description(@NotNull String... lines) {
            this.description.clear();
            this.description.addAll(List.of(lines));
            return this;
        }

        /** Its description, one lore line each, palette tokens allowed. */
        public @NotNull Builder description(@NotNull List<String> lines) {
            this.description.clear();
            this.description.addAll(lines);
            return this;
        }

        /** What a choice's option is called on screen. The id by default. */
        public @NotNull Builder label(@NotNull String option, @NotNull String label) {
            this.labels.put(option, label);
            return this;
        }

        /**
         * The permission it needs. A player without it sees the setting locked
         * and reads the default.
         */
        public @NotNull Builder permission(@Nullable String permission) {
            this.permission = permission;
            return this;
        }

        /** Keeps a value per server, under the server's network id, instead of one for the network. */
        public @NotNull Builder perServer() {
            this.perServer = true;
            return this;
        }

        /**
         * Asked every time the menu draws the setting: one whose feature is off
         * is shown disabled with the reason, rather than offered.
         *
         * @param check  memory only, any thread
         * @param reason why it is off, or {@code null} for the menu's own wording
         */
        public @NotNull Builder available(@NotNull BooleanSupplier check, @Nullable String reason) {
            this.available = check;
            this.unavailableReason = reason;
            return this;
        }

        /** Keeps the value in the plugin's own store instead of the library's table. */
        public @NotNull Builder store(@NotNull Store store) {
            this.store = store;
            return this;
        }

        /**
         * The setting.
         *
         * @return the setting
         * @throws IllegalArgumentException when the default is not one the setting can hold
         */
        public @NotNull Setting build() {
            if (kind == Kind.CHOICE && options.isEmpty()) {
                throw new IllegalArgumentException("A choice needs at least one option: " + key);
            }
            if (kind == Kind.NUMBER && (min > max || step <= 0)) {
                throw new IllegalArgumentException("A number needs min <= max and a positive step: " + key);
            }
            Setting setting = new Setting(this);
            if (!defaultValue.equals(setting.normalise(defaultValue))) {
                throw new IllegalArgumentException("The default of " + key + " is not a value it can hold: "
                        + defaultValue);
            }
            return setting;
        }
    }
}
