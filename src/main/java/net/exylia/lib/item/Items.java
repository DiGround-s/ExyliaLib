package net.exylia.lib.item;

import net.exylia.lib.item.internal.BannerCodec;
import net.exylia.lib.item.internal.ItemReader;
import net.exylia.lib.item.internal.ItemRenderer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import net.exylia.lib.item.internal.InertItems;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Items described in configuration, read once and drawn per player.
 *
 * <p>The shared answer to a question five plugins ask separately: a menu icon,
 * a special item, a kit entry, a lobby hotbar slot and a shield are all the same
 * block of YAML, and were all being parsed by a different copy of the same code.
 *
 * <pre>{@code
 * PluginItems items = Items.of(this);
 *
 * // when the file loads, once
 * Item icon = items.parse(section);
 *
 * // when somebody looks at it
 * ItemStack stack = items.render(icon, player);
 * }</pre>
 *
 * <h2>Why it is per plugin</h2>
 * Values written onto an item with {@code nbt} are stored under the owning
 * plugin's namespace, so two plugins can both write {@code id} without
 * colliding. ExyliaCommons held one static plugin reference for this, which in a
 * shared library would file every value under ExyliaLib's own name.
 *
 * <h2>What it costs</h2>
 * Reading a section is the expensive part and happens once. Rendering an item
 * with no placeholders is a cache lookup; rendering one with placeholders is a
 * resolve and a copy. Nothing here touches the network — a head is asked of
 * {@link net.exylia.lib.skull.Skulls}, which never blocks.
 *
 * @since 1.22.0
 */
public final class Items {

    private Items() {
    }

    /**
     * The item reader belonging to a plugin.
     *
     * @param plugin the plugin whose items these are
     * @return its reader
     */
    public static @NotNull PluginItems of(@NotNull Plugin plugin) {
        return new PluginItems(plugin);
    }

    /**
     * Forgets a plugin's item state.
     *
     * <p>Called by ExyliaLib when the plugin is disabled. Consumers do not need
     * to call this.
     *
     * @param pluginName the name of the plugin being disabled
     * @since 1.84.4
     */
    public static void release(@NotNull String pluginName) {
        InertItems.release(pluginName);
    }

    /**
     * Reads an item with no owner.
     *
     * <p>For definitions that will never carry stored values, which is most of
     * them. An item parsed this way that does declare {@code nbt} keeps the
     * values in the definition and drops them when rendered, because there is no
     * namespace to file them under.
     *
     * @param section the section describing the item
     * @return the definition
     */
    public static @NotNull Item parse(@NotNull ConfigurationSection section) {
        return ItemReader.read(section, Problems.IGNORED);
    }

    /**
     * Reads an item with no owner, reporting parts that could not be read.
     *
     * @param section  the section describing the item
     * @param problems where to report bad parts
     * @return the definition
     */
    public static @NotNull Item parse(@NotNull ConfigurationSection section,
                                      @NotNull Problems problems) {
        return ItemReader.read(section, problems);
    }

    /**
     * Builds the object an icon string names.
     *
     * <p>The whole grammar {@link Source} reads, in one call and with nothing
     * written on it: a material name, a {@code bytes:} snapshot of an item
     * somebody held, or a head. What a database column holds when a plugin
     * stored "what this should be drawn as", which is a kit, a warp, a home, an
     * arena and a reward in every plugin in the ecosystem.
     *
     * <pre>{@code
     * ItemStack icon = Items.icon(home.icon());
     * }</pre>
     *
     * <p>This is the short way for a caller holding a bare string and nothing
     * else. An icon that belongs to a menu does not need it: write
     * {@code material: "%home_icon%"} in the file and the row resolves it,
     * with the viewer, the placeholders and the traits the definition asks for.
     *
     * <p>A string that names nothing, or a snapshot that cannot be read, comes
     * back as paper rather than as nothing at all — an unreadable row is still
     * a row somebody has to be able to select and delete. A head whose owner is
     * written as a placeholder comes back plain, since there is no viewer here
     * to resolve it against.
     *
     * @param source the icon value as stored
     * @return the item; never {@code null}
     * @since 1.58.0
     */
    public static @NotNull ItemStack icon(@NotNull String source) {
        return ItemRenderer.icon(source);
    }

    /**
     * Reads a banner design saved as base64.
     *
     * <p>The form a shield editor saves and a menu reads back. Kept public
     * because plugins store these in databases, not only in config files.
     *
     * @param base64 the encoded design
     * @return the design, or {@code null} when the string is not one
     */
    public static @Nullable Banner banner(@NotNull String base64) {
        return BannerCodec.decode(base64);
    }

    /**
     * Writes a banner design as base64.
     *
     * @param banner the design
     * @return the encoded string
     */
    public static @NotNull String encode(@NotNull Banner banner) {
        return BannerCodec.encode(banner);
    }
}
