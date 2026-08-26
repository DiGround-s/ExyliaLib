package net.exylia.lib.item;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.item.internal.ItemReader;
import net.exylia.lib.item.internal.ItemRenderer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The item reader belonging to one plugin.
 *
 * <p>Obtained from {@link Items#of(Plugin)}. Values written onto an item with
 * {@code nbt} go under this plugin's namespace, so two plugins storing
 * {@code id} on an item never collide.
 *
 * <pre>{@code
 * PluginItems items = Items.of(this);
 * Item icon = items.parse(section);
 * ItemStack stack = items.render(icon, player);
 * }</pre>
 *
 * <h2>Threads</h2>
 * {@link #parse} is pure and safe anywhere. {@link #render} builds an
 * {@code ItemStack}, so it belongs on the thread that owns the viewer — the
 * same rule as everything else that touches an inventory.
 *
 * @since 1.22.0
 */
public final class PluginItems {

    private final Plugin plugin;
    private final Debug debug;
    private final ItemValues values;

    PluginItems(Plugin plugin) {
        this.plugin = plugin;
        this.debug = Debug.of(plugin);
        this.values = new ItemValues(plugin);
    }

    /** The plugin these items belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * Values stored on live items under this plugin's namespace.
     *
     * <p>The runtime half of the {@code nbt} block: a use counter, a stat
     * track, which tool an {@code ItemStack} is. Same namespace, so a value
     * declared in a file and a value written from code are the same value.
     *
     * @return this plugin's item value store; the same instance every time
     */
    public @NotNull ItemValues values() {
        return values;
    }

    /**
     * Reads an item, reporting bad parts to the console.
     *
     * <p>One unreadable enchantment costs that enchantment, not the item. The
     * problem is named on the console so whoever wrote the file finds out from
     * a log line rather than from a player.
     *
     * @param section the section describing the item
     * @return the definition
     */
    public @NotNull Item parse(@NotNull ConfigurationSection section) {
        return ItemReader.read(section, (where, problem) ->
                debug.warn("In " + describe(section) + ", " + where + ": " + problem));
    }

    /**
     * Reads an item, reporting bad parts wherever the caller wants them.
     *
     * @param section  the section describing the item
     * @param problems where to report bad parts
     * @return the definition
     */
    public @NotNull Item parse(@NotNull ConfigurationSection section,
                               @NotNull Problems problems) {
        return ItemReader.read(section, problems);
    }

    /**
     * Builds an item for a player.
     *
     * <p>Placeholders resolve for them, their palette applies, and a head that
     * has not been fetched comes back plain rather than blocking. Must be called
     * on the thread that owns the viewer.
     *
     * @param definition what to build
     * @param viewer     who it is for, or {@code null} for nobody in particular
     * @return the item
     */
    public @NotNull ItemStack render(@NotNull Item definition, @Nullable Player viewer) {
        return ItemRenderer.render(definition, viewer, plugin, (where, problem) ->
                debug.warn("Rendering an item, " + where + ": " + problem));
    }

    /**
     * Builds an item for a player, reporting problems wherever the caller wants.
     *
     * @param definition what to build
     * @param viewer     who it is for, or {@code null}
     * @param problems   where to report parts that could not be applied
     * @return the item
     */
    public @NotNull ItemStack render(@NotNull Item definition, @Nullable Player viewer,
                                     @NotNull Problems problems) {
        return ItemRenderer.render(definition, viewer, plugin, Map.of(), problems::found);
    }

    /**
     * Builds an item with extra values for its placeholders.
     *
     * <p>For the same definition drawn many times with different values — a row
     * of a list, a slot that is about one particular thing:
     *
     * <pre>{@code
     * items.render(template, player, Map.of("kit_name", kit.name()));
     * }</pre>
     *
     * <p>Names are written without percent signs. A value given here wins over
     * a registered placeholder of the same name, which is what makes a
     * leaderboard show each row's {@code %player_name%} rather than the
     * viewer's.
     *
     * @param definition what to build
     * @param viewer     who it is for, or {@code null}
     * @param values     placeholder names to what they resolve to
     * @return the item
     */
    public @NotNull ItemStack render(@NotNull Item definition, @Nullable Player viewer,
                                     @NotNull Map<String, String> values) {
        return render(definition, viewer, values, java.util.Set.of());
    }

    /**
     * Builds an item where some values carry their own formatting.
     *
     * <p>A value named in {@code formatted} is parsed rather than inserted as
     * text, so a rank written {@code {highlight}&lMVP} in a config arrives as
     * colour instead of as those characters:
     *
     * <pre>{@code
     * items.render(template, player,
     *         Map.of("rank", config.rankDisplay()), Set.of("rank"));
     * }</pre>
     *
     * <p>Only for values the server owner wrote. A value a player typed goes
     * through {@link #render(Item, Player, Map)}: parsing it would let somebody
     * named {@code {error}} recolour whatever line they appear on.
     *
     * @param definition what to build
     * @param viewer     who it is for, or {@code null}
     * @param values     placeholder names to what they resolve to
     * @param formatted  which of those names are parsed rather than inserted literally
     * @return the item
     * @since 1.28.0
     */
    public @NotNull ItemStack render(@NotNull Item definition, @Nullable Player viewer,
                                     @NotNull Map<String, String> values,
                                     @NotNull java.util.Set<String> formatted) {
        return ItemRenderer.render(definition, viewer, plugin, values, formatted,
                (where, problem) -> debug.warn("Rendering an item, " + where + ": " + problem));
    }

    /**
     * Reads and builds in one call.
     *
     * <p>For the one-off case — an item read from a config and handed straight
     * to a player. Anything drawn more than once should keep the {@link Item}
     * and render that, so the file is read once rather than on every draw.
     *
     * @param section the section describing the item
     * @param viewer  who it is for, or {@code null}
     * @return the item
     */
    public @NotNull ItemStack build(@NotNull ConfigurationSection section,
                                    @Nullable Player viewer) {
        return render(parse(section), viewer);
    }

    /** Names a section the way somebody editing the file would recognise it. */
    private static String describe(ConfigurationSection section) {
        String path = section.getCurrentPath();
        return path == null || path.isEmpty() ? "an item" : "\"" + path + "\"";
    }
}
