package net.exylia.lib.modes;

import net.exylia.lib.modes.internal.ModesMenu;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ways to play every plugin on the server offers, and the one screen that
 * lists them: games, events, queues, a mode selector of any kind.
 *
 * <pre>{@code
 * // onEnable
 * Modes.category(this, "tabletop", "OAK_SIGN", "Tabletop", 10, "Board games against", "another player.");
 * Modes.register(this, Mode.of("chess", player -> lobbies.open(player, "chess"))
 *         .category("tabletop").icon("BLACK_CONCRETE").name("Chess")
 *         .players(() -> matches.playing("chess"))
 *         .build());
 *
 * // from a command, an NPC, a lobby item
 * Modes.open(player);               // every category
 * Modes.open(player, "tabletop");   // straight to one
 * }</pre>
 *
 * <h2>Shared categories</h2>
 * A category is a global id, not a plugin's: two plugins listing modes under
 * {@code games} share one card. Whoever describes it last decides its name,
 * icon and order. A category nobody describes is still shown, named after its
 * id and drawn as its first mode, after the described ones.
 *
 * <h2>Threads</h2>
 * Registering, listing and opening are safe from any thread. Lists are read
 * from memory; a mode's live values are asked on the viewer's thread.
 *
 * <h2>Lifecycle</h2>
 * Everything a plugin registered, modes and the categories it described, is
 * forgotten when it is disabled. Register from {@code onEnable}; registering an
 * id again replaces it.
 *
 * <h2>Reload</h2>
 * Nothing here is derived from the palette; the screens are recompiled with
 * the library's other menus on {@code /exylialib reload}.
 *
 * @since 1.356.0
 */
public final class Modes {

    /**
     * A category's card.
     *
     * @param id          what modes name in {@link Mode.Builder#category}
     * @param icon        what its card is drawn as
     * @param name        its name, plain text
     * @param order       where it sits: lower first, then by name
     * @param description its description, one lore line each
     */
    public record Category(@NotNull String id, @NotNull String icon, @NotNull String name, int order,
                           @NotNull List<String> description) {
    }

    private record Owned<T>(Plugin owner, T value) {
    }

    /** By owner plugin name and mode id. */
    private static final Map<String, Owned<Mode>> MODES = new ConcurrentHashMap<>();
    private static final Map<String, Owned<Category>> CATEGORIES = new ConcurrentHashMap<>();

    private Modes() {
        throw new AssertionError("No instances.");
    }

    // ------------------------------------------------------------------ registry

    /**
     * Adds a mode to the screen, or replaces the plugin's mode with the same id.
     *
     * @param plugin the plugin it belongs to
     * @param mode   the mode
     */
    public static void register(@NotNull Plugin plugin, @NotNull Mode mode) {
        Objects.requireNonNull(mode, "mode");
        MODES.put(key(plugin, mode.id()), new Owned<>(plugin, mode));
    }

    /**
     * Removes a mode from the screen.
     *
     * @param plugin the plugin it belongs to
     * @param id     its id
     */
    public static void unregister(@NotNull Plugin plugin, @NotNull String id) {
        MODES.computeIfPresent(key(plugin, id), (key, owned) -> owned.owner() == plugin ? null : owned);
    }

    /**
     * Describes a category: the card the screen shows for it.
     *
     * @param plugin      the plugin describing it; the description goes when it is disabled
     * @param id          what modes name in {@link Mode.Builder#category}
     * @param icon        what its card is drawn as
     * @param name        its name, plain text
     * @param order       where it sits: lower first, then by name
     * @param description its description, one lore line each, palette tokens allowed
     */
    public static void category(@NotNull Plugin plugin, @NotNull String id, @NotNull String icon,
                                @NotNull String name, int order, @NotNull String... description) {
        String normalised = id.toLowerCase(Locale.ROOT);
        CATEGORIES.put(normalised, new Owned<>(plugin, new Category(normalised, icon, name, order,
                List.of(description))));
    }

    /**
     * Every registered mode, by category then by order and name.
     *
     * @return a copy
     */
    public static @NotNull List<Mode> modes() {
        Map<String, Category> categories = described();
        List<Mode> all = new ArrayList<>();
        MODES.values().forEach(owned -> all.add(owned.value()));
        all.sort(Comparator.<Mode>comparingInt(mode -> order(categories, mode.category()))
                .thenComparing(Mode::category)
                .thenComparingInt(Mode::order)
                .thenComparing(Mode::name, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    /**
     * The modes of one category, by order and name.
     *
     * @param category the category id
     * @return a copy, empty for a category with no modes
     */
    public static @NotNull List<Mode> modes(@NotNull String category) {
        String id = category.toLowerCase(Locale.ROOT);
        return modes().stream().filter(mode -> mode.category().equals(id)).toList();
    }

    /**
     * Every category with at least one mode, by order and name; one nobody
     * described comes last, named after its id.
     *
     * @return a copy
     */
    public static @NotNull List<Category> categories() {
        Map<String, Category> categories = described();
        Map<String, Category> used = new LinkedHashMap<>();
        for (Mode mode : modes()) {
            used.computeIfAbsent(mode.category(), id -> categories.getOrDefault(id,
                    new Category(id, mode.icon(), id, Integer.MAX_VALUE, List.of())));
        }
        List<Category> all = new ArrayList<>(used.values());
        all.sort(Comparator.comparingInt(Category::order).thenComparing(Category::name, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    private static Map<String, Category> described() {
        Map<String, Category> described = new LinkedHashMap<>();
        CATEGORIES.forEach((id, owned) -> described.put(id, owned.value()));
        return described;
    }

    private static int order(Map<String, Category> categories, String id) {
        Category category = categories.get(id);
        return category == null ? Integer.MAX_VALUE : category.order();
    }

    private static String key(Plugin plugin, String id) {
        return plugin.getName().toLowerCase(Locale.ROOT) + ":" + id;
    }

    // ------------------------------------------------------------------ screens

    /**
     * Opens the modes screen: a card per category, or the one category's list
     * straight away when there is only one. Any thread.
     *
     * @param player who to show it to
     */
    public static void open(@NotNull Player player) {
        ModesMenu.open(player);
    }

    /**
     * Opens one category's list directly, with no way back to the others. Any thread.
     *
     * @param player   who to show it to
     * @param category the category id
     */
    public static void open(@NotNull Player player, @NotNull String category) {
        ModesMenu.open(player, category.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ lifecycle

    /** Forgets what a plugin registered when it is disabled &mdash; this load only. Called by the library. */
    public static void release(@NotNull Plugin plugin) {
        MODES.values().removeIf(owned -> owned.owner() == plugin);
        CATEGORIES.values().removeIf(owned -> owned.owner() == plugin);
    }

    /** Forgets every plugin, on shutdown. Called by the library. */
    public static void releaseAll() {
        MODES.clear();
        CATEGORIES.clear();
    }
}
