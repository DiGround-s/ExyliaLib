package net.exylia.lib.api.events.custom;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Everything ExyliaEvents needs to know about a minigame somebody else wrote.
 *
 * <p>One of these, handed to
 * {@link net.exylia.lib.api.events.EventsService#registerMinigame}, is the whole
 * of adding a minigame: the type appears in the admin menus, an admin can
 * configure as many arenas of it as they like, each arena gets the same setup
 * flow, the same protection, the same statistics and the same rewards as a
 * built-in one, and {@link MinigameHandler} is called to play it.
 *
 * <pre>{@code
 * MinigameDefinition definition = MinigameDefinition.of("skyfall", SkyfallHandler::new)
 *         .displayName("Skyfall")
 *         .description("The floor falls away beneath you.", "Be the last one on it.")
 *         .category("Survival")
 *         .icon("SAND", "<#FFAA00>")
 *         .players(2, 16)
 *         .duration(300)
 *         .settings(
 *                 MinigameSetting.duration("grace-period", 5).label("{info}&lGRACE PERIOD"),
 *                 MinigameSetting.flag("double-jump", true).label("{success}&lDOUBLE JUMP"))
 *         .markers(MinigameMarker.area("safe-zone", "Safe zone", "BARRIER").count(0, 4))
 *         .sidebar(MinigameSidebar.of("{primary}&lSKYFALL", List.of(
 *                 "",
 *                 " {muted}❙ {letters}Alive: {success}%alive%",
 *                 " {muted}❙ {letters}Time: {highlight}%time%",
 *                 "")))
 *         .build();
 * }</pre>
 *
 * <h2>One handler per run</h2>
 * The {@code handler} is a factory, not an instance: ExyliaEvents builds a fresh
 * one for every run, so several arenas of the same minigame play at once without
 * sharing a single field.
 *
 * <h2>Teams</h2>
 * Team minigames are not registrable through this API yet. A definition
 * describes a free-for-all; a game that wants sides has to keep them itself.
 *
 * @since 1.7.0
 */
public final class MinigameDefinition {

    private static final int MAX_ID_LENGTH = 32;

    private final String id;
    private final Supplier<MinigameHandler> handler;
    private final String displayName;
    private final List<String> description;
    private final String category;
    private final String icon;
    private final String color;
    private final int countdown;
    private final int duration;
    private final int minPlayers;
    private final int maxPlayers;
    private final List<MinigameSetting> settings;
    private final List<MinigameMarker> markers;
    private final MinigameSidebar sidebar;
    private final boolean spawnPerPlayer;
    private final boolean managesNametags;

    private MinigameDefinition(Builder builder) {
        this.id = builder.id;
        this.handler = builder.handler;
        this.displayName = builder.displayName == null ? builder.id : builder.displayName;
        this.description = List.copyOf(builder.description);
        this.category = builder.category == null ? "" : builder.category;
        this.icon = builder.icon;
        this.color = builder.color;
        this.countdown = builder.countdown;
        this.duration = builder.duration;
        this.minPlayers = builder.minPlayers;
        this.maxPlayers = builder.maxPlayers;
        this.settings = List.copyOf(builder.settings);
        this.markers = List.copyOf(builder.markers);
        this.sidebar = builder.sidebar;
        this.spawnPerPlayer = builder.spawnPerPlayer;
        this.managesNametags = builder.managesNametags;
    }

    /**
     * Starts a definition.
     *
     * @param id      the minigame's id: lowercase letters, digits and
     *                underscores, unique across the server. Prefix it with your
     *                plugin's name — {@code myplugin_skyfall} — so two add-ons
     *                cannot collide.
     * @param handler builds the rules for one run; called once per run
     * @return the builder
     */
    public static @NotNull Builder of(@NotNull String id, @NotNull Supplier<MinigameHandler> handler) {
        return new Builder(id, handler);
    }

    /** @return the minigame's id */
    public @NotNull String id() {
        return id;
    }

    /** @return a fresh set of rules for one run */
    public @NotNull MinigameHandler newHandler() {
        MinigameHandler built = handler.get();
        if (built == null) {
            throw new IllegalStateException("The handler factory for '" + id + "' returned null.");
        }
        return built;
    }

    /** @return what the menus call it */
    public @NotNull String displayName() {
        return displayName;
    }

    /** @return the lines describing it in the type menu */
    public @NotNull @Unmodifiable List<String> description() {
        return description;
    }

    /** @return what it is grouped under in the type menu */
    public @NotNull String category() {
        return category;
    }

    /** @return the material the menus draw it with */
    public @NotNull String icon() {
        return icon;
    }

    /** @return the colour its name takes, as a MiniMessage-style tag */
    public @NotNull String color() {
        return color;
    }

    /** @return how long a run waits in its lobby, in seconds */
    public int countdown() {
        return countdown;
    }

    /** @return the time limit in seconds, or {@code 0} for no limit */
    public int duration() {
        return duration;
    }

    /** @return how many players a run needs before it starts */
    public int minPlayers() {
        return minPlayers;
    }

    /** @return how many players a run will hold */
    public int maxPlayers() {
        return maxPlayers;
    }

    /** @return the settings an admin can change */
    public @NotNull @Unmodifiable List<MinigameSetting> settings() {
        return settings;
    }

    /** @return the places an admin has to mark on an arena */
    public @NotNull @Unmodifiable List<MinigameMarker> markers() {
        return markers;
    }

    /** @return the board shown while the game is played, or {@code null} for none */
    public @Nullable MinigameSidebar sidebar() {
        return sidebar;
    }

    /** @return whether every player needs a spawn point of their own */
    public boolean spawnPerPlayer() {
        return spawnPerPlayer;
    }

    /** @return whether the game renames its players while it runs */
    public boolean managesNametags() {
        return managesNametags;
    }

    /** Collects a definition and checks it before it can be handed over. */
    public static final class Builder {

        private final String id;
        private final Supplier<MinigameHandler> handler;
        private final List<MinigameSetting> settings = new ArrayList<>();
        private final List<MinigameMarker> markers = new ArrayList<>();
        private final List<String> description = new ArrayList<>();
        private String displayName;
        private String category = "";
        private String icon = "PAPER";
        private String color = "<#8a51c4>";
        private int countdown = 30;
        private int duration;
        private int minPlayers = 2;
        private int maxPlayers = 16;
        private MinigameSidebar sidebar;
        private boolean spawnPerPlayer;
        private boolean managesNametags;

        private Builder(String id, Supplier<MinigameHandler> handler) {
            this.id = validId(id);
            if (handler == null) {
                throw new IllegalArgumentException("Minigame '" + this.id + "' needs a handler factory.");
            }
            this.handler = handler;
        }

        /** What the menus call it. Defaults to the id. */
        public @NotNull Builder displayName(@NotNull String displayName) {
            this.displayName = displayName;
            return this;
        }

        /** The lines describing it where a player or an admin picks a minigame. */
        public @NotNull Builder description(@NotNull String... lines) {
            this.description.clear();
            this.description.addAll(List.of(lines));
            return this;
        }

        /**
         * What it is grouped under in the type menu, such as {@code Survival}.
         *
         * <p>Matching a category the built-in minigames already use puts it in
         * that group rather than in one of its own.
         */
        public @NotNull Builder category(@NotNull String category) {
            this.category = category;
            return this;
        }

        /**
         * How the menus draw it.
         *
         * @param material the icon material
         * @param color    the colour its name takes, such as {@code <#FFAA00>}
         */
        public @NotNull Builder icon(@NotNull String material, @NotNull String color) {
            this.icon = material.toUpperCase(Locale.ROOT);
            this.color = color;
            return this;
        }

        /** How long a run waits in its lobby before it starts, in seconds. */
        public @NotNull Builder countdown(int seconds) {
            this.countdown = Math.max(0, seconds);
            return this;
        }

        /**
         * The time limit in seconds.
         *
         * <p>{@code 0}, the default, means the game runs until something ends
         * it. A game with no limit never gets {@link MinigameHandler#onTimeUp}.
         */
        public @NotNull Builder duration(int seconds) {
            this.duration = Math.max(0, seconds);
            return this;
        }

        /** How many players a run needs, and how many it will hold. */
        public @NotNull Builder players(int minimum, int maximum) {
            this.minPlayers = Math.max(1, minimum);
            this.maxPlayers = Math.max(this.minPlayers, maximum);
            return this;
        }

        /** The values an admin can change per arena. */
        public @NotNull Builder settings(@NotNull MinigameSetting... settings) {
            this.settings.addAll(List.of(settings));
            return this;
        }

        /** The places an admin has to mark on an arena before it may be enabled. */
        public @NotNull Builder markers(@NotNull MinigameMarker... markers) {
            this.markers.addAll(List.of(markers));
            return this;
        }

        /** The board shown while the game is being played. */
        public @NotNull Builder sidebar(@NotNull MinigameSidebar sidebar) {
            this.sidebar = sidebar;
            return this;
        }

        /**
         * Every player needs a spawn point of their own.
         *
         * <p>An arena of this minigame is not complete until it has as many
         * spawn points as it has player slots — for a game where two players
         * sharing a spawn would be a bug rather than a crowd.
         */
        public @NotNull Builder spawnPerPlayer() {
            this.spawnPerPlayer = true;
            return this;
        }

        /** The game renames its players while it runs. */
        public @NotNull Builder managesNametags() {
            this.managesNametags = true;
            return this;
        }

        /**
         * Checks the definition and freezes it.
         *
         * @return the definition
         * @throws IllegalArgumentException when two settings or two markers
         *         share a key, which would silently overwrite one of them
         */
        public @NotNull MinigameDefinition build() {
            requireDistinct(settings.stream().map(MinigameSetting::key).toList(), "setting");
            requireDistinct(markers.stream().map(MinigameMarker::role).toList(), "marker");
            return new MinigameDefinition(this);
        }

        private void requireDistinct(List<String> keys, String what) {
            Set<String> seen = new LinkedHashSet<>();
            for (String key : keys) {
                if (!seen.add(key)) {
                    throw new IllegalArgumentException(
                            "Minigame '" + id + "' declares the " + what + " '" + key + "' twice.");
                }
            }
        }

        /**
         * The id, checked.
         *
         * <p>Strict on purpose: the id becomes a menu file name, a settings
         * screen id, a database value and a placeholder, and every one of those
         * breaks differently on a space or a capital.
         */
        private static String validId(String id) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("A minigame needs an id.");
            }
            String normalised = id.toLowerCase(Locale.ROOT);
            if (normalised.length() > MAX_ID_LENGTH) {
                throw new IllegalArgumentException(
                        "The minigame id '" + normalised + "' is longer than " + MAX_ID_LENGTH + " characters.");
            }
            if (!normalised.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("The minigame id '" + id
                        + "' may only hold lowercase letters, digits and underscores.");
            }
            if (normalised.endsWith("_teams")) {
                throw new IllegalArgumentException("The minigame id '" + id
                        + "' may not end in _teams: that suffix means a team variant of a built-in minigame.");
            }
            return normalised;
        }
    }
}
