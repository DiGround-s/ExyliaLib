package net.exylia.lib.database.wipe.internal;

import net.exylia.lib.database.DataKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What one wipe or player reset will touch: a scope, the kinds, the plugins.
 *
 * <p>Mutable, because the screens toggle it, and handed from screen to screen
 * in the menu context rather than kept in a per-player map. Every field the
 * screens change is thread-safe: a toggle lands on the viewer's thread while the
 * counts arrive on a background one.
 */
public final class WipeRequest {

    /** The four ways in, and what each one may and does delete. */
    public enum Preset {
        /** Everything a new season starts over. */
        SEASON(EnumSet.of(DataKind.PROGRESS, DataKind.WORLD, DataKind.CACHE),
                EnumSet.of(DataKind.HISTORY, DataKind.ACCOUNT), true),
        /** Every player's progress, the world left standing. */
        PLAYERS(EnumSet.of(DataKind.PROGRESS, DataKind.CACHE), EnumSet.of(DataKind.HISTORY), true),
        /** Every table of one plugin. */
        PLUGIN(EnumSet.allOf(DataKind.class), EnumSet.noneOf(DataKind.class), false),
        /** One player's rows, everywhere. */
        PLAYER(EnumSet.of(DataKind.PROGRESS, DataKind.WORLD, DataKind.CACHE),
                EnumSet.of(DataKind.ACCOUNT, DataKind.HISTORY), true),
        /** Kinds named by hand on the command line. */
        CUSTOM(EnumSet.noneOf(DataKind.class), EnumSet.allOf(DataKind.class), false);

        private final Set<DataKind> defaults;
        private final Set<DataKind> allowed;
        private final boolean vanilla;

        Preset(Set<DataKind> defaults, Set<DataKind> optional, boolean vanilla) {
            this.defaults = Set.copyOf(defaults);
            EnumSet<DataKind> all = EnumSet.noneOf(DataKind.class);
            all.addAll(defaults);
            all.addAll(optional);
            this.allowed = Set.copyOf(all);
            this.vanilla = vanilla;
        }

        /** The kinds on when the preset is picked. */
        public @NotNull Set<DataKind> defaults() {
            return defaults;
        }

        /** The kinds that may be turned on at all; {@code UNSPECIFIED} and {@code SETUP} never for presets. */
        public @NotNull Set<DataKind> allowed() {
            return allowed;
        }

        /** Whether the vanilla player files can be added. */
        public boolean vanilla() {
            return vanilla;
        }
    }

    private final Preset preset;
    private final @Nullable UUID player;
    private final @Nullable String playerName;
    private final @Nullable String only;
    private final @Nullable String table;
    private final Set<DataKind> kinds = ConcurrentHashMap.newKeySet();
    private final Set<String> excluded = ConcurrentHashMap.newKeySet();
    private volatile boolean vanilla;

    /** Filled once the catalog is read; {@code null} until then. */
    private volatile @Nullable List<WipeTable> tables;
    private final Map<String, Long> counts = new ConcurrentHashMap<>();

    /**
     * @param preset     the way in
     * @param kinds      the kinds on; {@code null} for the preset's defaults
     * @param player     whose rows, or {@code null} for every row
     * @param playerName what to call them
     * @param only       one plugin, or {@code null} for every plugin
     * @param table      one table by name, or {@code null} for every table of the kinds
     */
    public WipeRequest(@NotNull Preset preset, @Nullable Collection<DataKind> kinds, @Nullable UUID player,
                       @Nullable String playerName, @Nullable String only, @Nullable String table) {
        this.preset = preset;
        this.player = player;
        this.playerName = playerName;
        this.only = only;
        this.table = table;
        this.kinds.addAll(kinds == null ? preset.defaults() : kinds);
    }

    public @NotNull Preset preset() {
        return preset;
    }

    public @Nullable UUID player() {
        return player;
    }

    public @NotNull String playerName() {
        return playerName == null ? String.valueOf(player) : playerName;
    }

    public @Nullable String only() {
        return only;
    }

    public @Nullable String table() {
        return table;
    }

    /** The kinds on, in declaration order. */
    public @NotNull Set<DataKind> kinds() {
        EnumSet<DataKind> sorted = EnumSet.noneOf(DataKind.class);
        sorted.addAll(kinds);
        return sorted;
    }

    /** Turns a kind on or off; a kind the preset does not allow stays off. */
    public boolean toggle(@NotNull DataKind kind) {
        if (!preset.allowed().contains(kind)) return false;
        if (!kinds.remove(kind)) kinds.add(kind);
        return true;
    }

    public boolean excluded(@NotNull String plugin) {
        return excluded.contains(plugin.toLowerCase(Locale.ROOT));
    }

    /** Leaves a plugin out, or puts it back. */
    public void toggle(@NotNull String plugin) {
        String key = plugin.toLowerCase(Locale.ROOT);
        if (!excluded.remove(key)) excluded.add(key);
    }

    public boolean vanilla() {
        return vanilla && preset.vanilla();
    }

    public void vanilla(boolean on) {
        this.vanilla = on;
    }

    /** Whether this is every row of every table it names, as opposed to one player's. */
    public boolean whole() {
        return player == null;
    }

    /**
     * Whether a table belongs on the screens at all, whatever the toggles say:
     * in scope for the plugin, and, for a player reset, with an owner column.
     */
    public boolean candidate(@NotNull WipeTable table) {
        if (only != null && !table.plugin().equalsIgnoreCase(only)) return false;
        if (this.table != null && !table.table().equalsIgnoreCase(this.table)) return false;
        return player == null || table.owner() != null;
    }

    /** Whether a table is emptied (or, for a player, has their rows removed). */
    public boolean includes(@NotNull WipeTable table) {
        if (!candidate(table) || excluded(table.plugin())) return false;
        // A table named by hand goes whatever it is; everything else by its kind.
        return this.table != null || kinds.contains(table.kind());
    }

    // ------------------------------------------------------------ the catalog

    /** The catalog this request was planned against, or {@code null} while it is read. */
    public @Nullable List<WipeTable> tables() {
        return tables;
    }

    /** Takes another request's catalog and counts, so a narrower request needs no second read. */
    void adopt(@NotNull WipeRequest other) {
        List<WipeTable> found = other.tables;
        if (found != null) this.tables = found;
        counts.putAll(other.counts);
    }

    void tables(@NotNull List<WipeTable> found) {
        this.tables = List.copyOf(found);
    }

    /** Rows a table holds in scope, or {@code -1} while it is counted (or could not be). */
    public long count(@NotNull WipeTable table) {
        return counts.getOrDefault(table.key(), -1L);
    }

    void count(@NotNull WipeTable table, long rows) {
        counts.put(table.key(), rows);
    }

    /** The tables this request would touch, sorted by plugin and then table. */
    public @NotNull List<WipeTable> targets() {
        List<WipeTable> all = tables;
        return all == null ? List.of() : all.stream().filter(this::includes).toList();
    }

    /** Every plugin on the screens, with its candidate tables. */
    public @NotNull Map<String, List<WipeTable>> byPlugin() {
        Map<String, List<WipeTable>> grouped = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<WipeTable> all = tables;
        if (all == null) return grouped;
        for (WipeTable table : all) {
            if (candidate(table)) grouped.computeIfAbsent(table.plugin(), key -> new java.util.ArrayList<>()).add(table);
        }
        return grouped;
    }

    /**
     * Rows that would go, kind by kind, for one plugin's tables.
     *
     * @return the kinds in declaration order; a kind still being counted is {@code -1}
     */
    public @NotNull Map<DataKind, Long> rowsByKind(@NotNull List<WipeTable> tables) {
        Map<DataKind, Long> rows = new LinkedHashMap<>();
        for (WipeTable table : tables) {
            if (!includes(table)) continue;
            long counted = count(table);
            rows.merge(table.kind(), counted, (a, b) -> a < 0 || b < 0 ? -1L : a + b);
        }
        Map<DataKind, Long> sorted = new LinkedHashMap<>();
        for (DataKind kind : DataKind.values()) {
            if (rows.containsKey(kind)) sorted.put(kind, rows.get(kind));
        }
        return sorted;
    }

    // ------------------------------------------------------------ the command

    /** What a word means as the plugin of a wipe: every plugin. */
    public static boolean everyPlugin(@Nullable String word) {
        return word == null || word.equals("*") || word.equalsIgnoreCase("all");
    }

    /**
     * Reads {@code /exylialib wipe <plugin|all> <table|@kind|@preset|all>}.
     *
     * @param plugin the first word
     * @param target the second word
     * @return the request, or {@code null} for the per-plugin table syntax the
     *         command has always had ({@code <plugin> <table|all>})
     * @throws IllegalArgumentException for an {@code @} word that names nothing
     */
    public static @Nullable WipeRequest parse(@NotNull String plugin, @NotNull String target) {
        boolean all = everyPlugin(plugin);
        String only = all ? null : plugin;
        if (target.startsWith("@")) {
            String word = target.substring(1).toLowerCase(Locale.ROOT);
            return switch (word) {
                case "season" -> new WipeRequest(Preset.SEASON, null, null, null, only, null);
                case "players" -> new WipeRequest(Preset.PLAYERS, null, null, null, only, null);
                default -> new WipeRequest(Preset.CUSTOM, Set.of(kind(target)), null, null, only, null);
            };
        }
        if (!all) return null;
        return everyPlugin(target)
                ? new WipeRequest(Preset.CUSTOM, EnumSet.allOf(DataKind.class), null, null, null, null)
                : new WipeRequest(Preset.CUSTOM, EnumSet.allOf(DataKind.class), null, null, null, target);
    }

    /**
     * Reads the kinds of {@code /exylialib resetplayer}.
     *
     * @param player the player's id
     * @param name   what to call them
     * @param plugin one plugin, or {@code null}/{@code all} for every one
     * @param kinds  {@code @kind} words; none for the preset's defaults
     * @return the request
     * @throws IllegalArgumentException for a kind a player reset never touches
     */
    public static @NotNull WipeRequest player(@NotNull UUID player, @NotNull String name, @Nullable String plugin,
                                              @NotNull List<String> kinds) {
        Set<DataKind> chosen = kinds.isEmpty() ? null : EnumSet.noneOf(DataKind.class);
        for (String word : kinds) {
            DataKind kind = kind(word);
            if (!Preset.PLAYER.allowed().contains(kind)) {
                throw new IllegalArgumentException(net.exylia.lib.text.Phrases.tr(
                        "A player reset never touches {0} tables.", word));
            }
            chosen.add(kind);
        }
        return new WipeRequest(Preset.PLAYER, chosen, player, name, everyPlugin(plugin) ? null : plugin, null);
    }

    /** {@code @progress} as a kind. */
    static DataKind kind(String word) {
        String name = (word.startsWith("@") ? word.substring(1) : word).toUpperCase(Locale.ROOT);
        for (DataKind kind : DataKind.values()) {
            if (kind.name().equals(name)) return kind;
        }
        throw new IllegalArgumentException(net.exylia.lib.text.Phrases.tr(
                "{0} is not a kind or a preset. Use @season, @players, @progress, @world, @account, @history, @cache, @setup or @unspecified.",
                word));
    }

    /** Every {@code @} word the command suggests. */
    public static @NotNull List<String> words() {
        List<String> words = new java.util.ArrayList<>(List.of("@season", "@players"));
        for (DataKind kind : DataKind.values()) words.add("@" + kind.name().toLowerCase(Locale.ROOT));
        return words;
    }

    /** What a pending command confirmation is tied to. */
    public @NotNull String describe() {
        return preset + "|" + player + "|" + only + "|" + table + "|" + kinds() + "|" + vanilla();
    }
}
