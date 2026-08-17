package net.exylia.lib.item;

import net.exylia.lib.item.internal.BannerCodec;
import net.exylia.lib.item.internal.ItemReader;
import org.bukkit.configuration.ConfigurationSection;
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
