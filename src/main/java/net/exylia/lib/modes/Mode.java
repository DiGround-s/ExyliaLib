package net.exylia.lib.modes;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * One way to play that a plugin offers in the shared modes screen: a game, an
 * event, a queue, a lobby.
 *
 * <p>Built once, registered with {@link Modes#register}, and opened by the
 * player from {@link Modes#open(Player)}.
 *
 * <pre>{@code
 * Modes.register(this, Mode.of("chess", player -> lobbies.open(player, "chess"))
 *         .category("tabletop")
 *         .icon("BLACK_CONCRETE")
 *         .name("Chess")
 *         .description("Checkmate the other king", "before yours falls.")
 *         .open(() -> games.isPlayable("chess"))
 *         .players(() -> matches.playing("chess"))
 *         .build());
 * }</pre>
 *
 * <h2>Live values</h2>
 * {@link Builder#open} and {@link Builder#players} are asked every second while
 * the screen is open, on the viewer's thread. They read memory &mdash; a map, a
 * counter the plugin keeps &mdash; never a database or a file.
 *
 * <h2>Text</h2>
 * {@link Builder#name} is plain text, drawn in the screen's own colours and in
 * capitals. {@link Builder#description} lines are body text and may carry
 * palette tokens such as {@code {highlight}}; each one becomes one lore line.
 *
 * @since 1.356.0
 */
public final class Mode {

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}");

    private final String id;
    private final Consumer<Player> action;
    private final String category;
    private final String icon;
    private final String name;
    private final List<String> description;
    private final int order;
    private final @Nullable String permission;
    private final BooleanSupplier open;
    private final @Nullable IntSupplier players;
    private final @Nullable Predicate<Player> playing;

    private Mode(Builder builder) {
        this.id = builder.id;
        this.action = builder.action;
        this.category = builder.category;
        this.icon = builder.icon;
        this.name = builder.name == null ? builder.id : builder.name;
        this.description = List.copyOf(builder.description);
        this.order = builder.order;
        this.permission = builder.permission;
        this.open = builder.open;
        this.players = builder.players;
        this.playing = builder.playing;
    }

    /**
     * Starts a mode.
     *
     * @param id     lower case letters, digits, {@code _}, {@code .} or {@code -}; unique within the plugin
     * @param action what a click does, on the player's thread, after their screen is closed: open the
     *               plugin's own lobby, join a queue, start a round
     * @return the builder
     */
    public static @NotNull Builder of(@NotNull String id, @NotNull Consumer<Player> action) {
        return new Builder(id, action);
    }

    /** Its id, unique within the plugin that registered it. */
    public @NotNull String id() {
        return id;
    }

    /** What a click does. */
    public @NotNull Consumer<Player> action() {
        return action;
    }

    /** The category it is listed under. */
    public @NotNull String category() {
        return category;
    }

    /** What its card is drawn as. */
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

    /** Where it sits in its category: lower first, then by name. */
    public int order() {
        return order;
    }

    /** The permission it needs, or {@code null}. */
    public @Nullable String permission() {
        return permission;
    }

    /** Whether it takes players right now. Memory only, any thread. */
    public boolean isOpen() {
        return open.getAsBoolean();
    }

    /** How many are playing it right now, or {@code -1} when the plugin does not count. */
    public int players() {
        return players == null ? -1 : Math.max(0, players.getAsInt());
    }

    /**
     * Whether the player is in this mode right now; {@code false} when the
     * plugin did not say ({@link Builder#playing}). Memory only, any thread.
     *
     * @since 1.386.0
     */
    public boolean isPlaying(@NotNull Player player) {
        return playing != null && playing.test(player);
    }

    /** Whether the player may play it. */
    public boolean allowed(@NotNull Player player) {
        return permission == null || player.hasPermission(permission);
    }

    @Override
    public String toString() {
        return "Mode[" + id + " in " + category + "]";
    }

    /** Builds a {@link Mode}. */
    public static final class Builder {

        private final String id;
        private final Consumer<Player> action;
        private String category = "general";
        private String icon = "PAPER";
        private @Nullable String name;
        private List<String> description = List.of();
        private int order;
        private @Nullable String permission;
        private BooleanSupplier open = () -> true;
        private @Nullable IntSupplier players;
        private @Nullable Predicate<Player> playing;

        private Builder(String id, Consumer<Player> action) {
            Objects.requireNonNull(id, "id");
            if (!ID.matcher(id).matches()) {
                throw new IllegalArgumentException("A mode id is 1 to 64 lower case letters, digits, '_', '.' or '-': "
                        + id);
            }
            this.id = id;
            this.action = Objects.requireNonNull(action, "action");
        }

        /**
         * The category it is listed under; describe it with {@link Modes#category}.
         * Several plugins may list modes under one category. {@code general} by default.
         */
        public @NotNull Builder category(@NotNull String category) {
            this.category = category.toLowerCase(Locale.ROOT);
            return this;
        }

        /** What its card is drawn as: a material name, or anything a menu's {@code material:} accepts. */
        public @NotNull Builder icon(@NotNull String icon) {
            this.icon = Objects.requireNonNull(icon, "icon");
            return this;
        }

        /** Its name, plain text. The id by default. */
        public @NotNull Builder name(@NotNull String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** Its description, one lore line each, palette tokens allowed. */
        public @NotNull Builder description(@NotNull String... lines) {
            this.description = List.of(lines);
            return this;
        }

        /** Where it sits in its category: lower first, then by name. {@code 0} by default. */
        public @NotNull Builder order(int order) {
            this.order = order;
            return this;
        }

        /** The permission it needs. A player without it sees the mode locked. */
        public @NotNull Builder permission(@Nullable String permission) {
            this.permission = permission;
            return this;
        }

        /**
         * Whether it takes players right now; a closed mode is shown, not hidden,
         * so players know it exists. Open by default.
         *
         * @param open memory only, any thread; asked every second while the screen is open
         */
        public @NotNull Builder open(@NotNull BooleanSupplier open) {
            this.open = Objects.requireNonNull(open, "open");
            return this;
        }

        /**
         * How many are playing it right now, shown on its card and added up on
         * its category's. Not shown when left out.
         *
         * @param players memory only, any thread; asked every second while the screen is open
         */
        public @NotNull Builder players(@NotNull IntSupplier players) {
            this.players = Objects.requireNonNull(players, "players");
            return this;
        }

        /**
         * Whether a player is in this mode right now, so {@link Modes#current}
         * can answer. Left out, the mode never claims anyone.
         *
         * @param playing memory only, any thread: a set of players the plugin keeps
         * @since 1.386.0
         */
        public @NotNull Builder playing(@NotNull Predicate<Player> playing) {
            this.playing = Objects.requireNonNull(playing, "playing");
            return this;
        }

        /** The mode. */
        public @NotNull Mode build() {
            return new Mode(this);
        }
    }
}
