package net.exylia.lib.setup;

import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Choices a plugin adds to the first-run setup ({@code /exylialib setup}).
 *
 * <p>Each registered choice becomes one more step after the library's own: a page with one card per
 * option, the current one glowing. Clicking an option calls {@code apply} with its id, on the thread
 * that owns the player. A server with no plugin registering a choice sees no extra step. Choices are
 * dropped when their plugin disables.
 *
 * <pre>{@code
 * SetupChoices.of(this).choice("layout", "Map layout", List.of(
 *         new SetupChoices.Option("zones", "Zones", List.of("Several zones bought with coins."), Material.GRASS_BLOCK),
 *         new SetupChoices.Option("plot", "Plot", List.of("One plot per player."), Material.OAK_FENCE)),
 *     () -> config.layoutMode(), id -> config.switchLayout(id));
 * }</pre>
 *
 * <p>Titles, names and descriptions come from the plugin (translate them there); at most
 * {@value #MAX_CHOICES} choices and {@value #MAX_OPTIONS} options per choice are shown.
 *
 * @since 1.675.0
 */
public final class SetupChoices {

    /** Most choices the setup hub shows. */
    public static final int MAX_CHOICES = 3;
    /** Most options one choice page shows. */
    public static final int MAX_OPTIONS = 7;

    private static final List<Choice> CHOICES = new ArrayList<>();

    private final String plugin;

    private SetupChoices(String plugin) {
        this.plugin = plugin;
    }

    /**
     * One answer to a choice.
     *
     * @param id          what {@code apply} receives
     * @param name        the card's name (MiniMessage and palette tokens allowed)
     * @param description a few short lines under the name
     * @param icon        the card's item
     */
    public record Option(@NotNull String id, @NotNull String name, @NotNull List<String> description,
                         @NotNull Material icon) {
    }

    /** A registered choice. */
    public record Choice(@NotNull String plugin, @NotNull String id, @NotNull String title,
                         @NotNull List<Option> options, @NotNull Supplier<String> current,
                         @NotNull Consumer<String> apply) {
    }

    /**
     * The registrar of a plugin.
     *
     * @param plugin the plugin adding choices
     * @return the registrar
     */
    public static @NotNull SetupChoices of(@NotNull Plugin plugin) {
        return new SetupChoices(plugin.getName());
    }

    /**
     * Adds a step to the setup, replacing this plugin's earlier choice with the same id.
     *
     * @param id      unique within the plugin
     * @param title   the step's name
     * @param options the answers, at most {@value #MAX_OPTIONS}
     * @param current the id of the answer in force now, read each time the page draws
     * @param apply   puts an answer in force, called with its id on the player's thread
     * @return this
     */
    public @NotNull SetupChoices choice(@NotNull String id, @NotNull String title, @NotNull List<Option> options,
                                        @NotNull Supplier<String> current, @NotNull Consumer<String> apply) {
        register(new Choice(plugin, id, title, List.copyOf(options), current, apply));
        return this;
    }

    /** Adds a choice; used by {@link #choice} and tests. */
    static void register(Choice choice) {
        synchronized (CHOICES) {
            CHOICES.removeIf(c -> c.plugin().equals(choice.plugin()) && c.id().equals(choice.id()));
            CHOICES.add(choice);
        }
    }

    /**
     * Every registered choice, in registration order, at most {@value #MAX_CHOICES}.
     *
     * @return a snapshot
     */
    public static @NotNull List<Choice> all() {
        synchronized (CHOICES) {
            return List.copyOf(CHOICES.subList(0, Math.min(CHOICES.size(), MAX_CHOICES)));
        }
    }

    /**
     * Drops a plugin's choices. Called by ExyliaLib when the plugin disables.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        synchronized (CHOICES) {
            CHOICES.removeIf(c -> c.plugin().equals(pluginName));
        }
    }
}
