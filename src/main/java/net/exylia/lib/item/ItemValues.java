package net.exylia.lib.item;

import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Values stored on an {@code ItemStack} at runtime, under one plugin's name.
 *
 * <pre>{@code
 * ItemValues values = Items.of(this).values();
 *
 * values.set(sword, "kills", kills + 1);
 * long kills = values.number(sword, "kills", 0);
 * boolean ours = values.has(sword, "id");
 * }</pre>
 *
 * <p>The counterpart of the {@code nbt} block in an item file. That block
 * writes what the definition knows when the item is built; this writes what
 * happens to the item afterwards — a use counter, a stat track, which tool it
 * is. Both land in the same place, under the same namespace, so a value
 * declared in YAML is readable here and a value written here survives the
 * server restart.
 *
 * <h2>The namespace is the plugin's</h2>
 * Every key is filed under the owning plugin, exactly as
 * {@link Traits#data()} files the declarative ones. Two plugins can both store
 * {@code id} on the same item without seeing each other's value, and reading
 * back an item written by an ExyliaCommons-era build of the same plugin finds
 * it where it was left: Commons keyed by the consumer plugin too.
 *
 * <h2>Reading does not insist on the type it was written as</h2>
 * {@link #text} on a value stored as a number answers with its digits, and
 * {@link #number} on {@code "5"} answers {@code 5}. This is not politeness: the
 * declarative writer picks the type from what the value <em>looks</em> like, so
 * a file saying {@code uses: 3} stores an integer while the same key set from
 * code might be a string. Commons was strict here and the mismatch read as
 * "the key is missing", which is indistinguishable from a fresh item and cost a
 * player their charges.
 *
 * <p>What it will not do is invent one. {@code number} on {@code "banana"}
 * gives the fallback, not zero.
 *
 * <h2>Threads</h2>
 * Reading and writing item meta is main-thread work, like anything that touches
 * an inventory. Nothing here is cached: an {@code ItemStack} is a value the
 * caller holds, and a cache keyed on one would be wrong the moment it is
 * copied.
 *
 * @since 1.63.0
 */
public final class ItemValues {

    private final Plugin plugin;

    ItemValues(Plugin plugin) {
        this.plugin = plugin;
    }

    /** The plugin whose namespace these values are filed under. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Whether this plugin stored anything under that key.
     *
     * @param item the item, may be {@code null} or air
     * @param key  the key, in this plugin's namespace
     * @return whether a value is there, of any type
     */
    public boolean has(@Nullable ItemStack item, @NotNull String key) {
        PersistentDataContainerView container = read(item);
        NamespacedKey namespaced = key(key);
        return container != null && namespaced != null && container.has(namespaced);
    }

    /**
     * The value as text, whatever type it was written as.
     *
     * @param item the item, may be {@code null} or air
     * @param key  the key, in this plugin's namespace
     * @return the value, or empty when the key is not there
     */
    public @NotNull Optional<String> text(@Nullable ItemStack item, @NotNull String key) {
        return Optional.ofNullable(raw(item, key)).map(String::valueOf);
    }

    /**
     * The value as text, or a fallback.
     *
     * @param item     the item, may be {@code null} or air
     * @param key      the key, in this plugin's namespace
     * @param fallback what to answer when the key is not there
     * @return the value, or {@code fallback}
     */
    public @NotNull String text(@Nullable ItemStack item, @NotNull String key, @NotNull String fallback) {
        Object value = raw(item, key);
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * The value as a whole number.
     *
     * @param item     the item, may be {@code null} or air
     * @param key      the key, in this plugin's namespace
     * @param fallback what to answer when the key is missing or not a number
     * @return the value, or {@code fallback}
     */
    public long number(@Nullable ItemStack item, @NotNull String key, long fallback) {
        Object value = raw(item, key);
        if (value instanceof Number found) {
            return found.longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /**
     * The value as a decimal.
     *
     * @param item     the item, may be {@code null} or air
     * @param key      the key, in this plugin's namespace
     * @param fallback what to answer when the key is missing or not a number
     * @return the value, or {@code fallback}
     */
    public double decimal(@Nullable ItemStack item, @NotNull String key, double fallback) {
        Object value = raw(item, key);
        if (value instanceof Number found) {
            return found.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /**
     * The value as a yes or no.
     *
     * <p>A number counts: anything other than zero is true, which is how the
     * value arrives when a file wrote {@code 1}.
     *
     * @param item     the item, may be {@code null} or air
     * @param key      the key, in this plugin's namespace
     * @param fallback what to answer when the key is missing or unreadable
     * @return the value, or {@code fallback}
     */
    public boolean flag(@Nullable ItemStack item, @NotNull String key, boolean fallback) {
        Object value = raw(item, key);
        if (value instanceof Byte stored) {
            return stored != 0;
        }
        if (value instanceof Number found) {
            return found.doubleValue() != 0.0;
        }
        if (value == null) {
            return fallback;
        }
        return switch (String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "yes", "1" -> true;
            case "false", "no", "0" -> false;
            default -> fallback;
        };
    }

    /**
     * Every key this plugin stored on the item.
     *
     * <p>For the cases where the keys are not known up front — a stat track
     * naming its own counters, say. Keys belonging to other plugins are not in
     * here.
     *
     * @param item the item, may be {@code null} or air
     * @return the keys without their namespace, in the order the item holds
     *         them; empty when there are none
     */
    public @NotNull Set<String> keys(@Nullable ItemStack item) {
        PersistentDataContainerView container = read(item);
        if (container == null) {
            return Set.of();
        }
        Set<String> found = new LinkedHashSet<>();
        for (NamespacedKey stored : container.getKeys()) {
            if (stored.getNamespace().equals(namespace())) {
                found.add(stored.getKey());
            }
        }
        return Collections.unmodifiableSet(found);
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Stores text on the item.
     *
     * @param item  the item to write onto; {@code null} or air is ignored
     * @param key   the key, in this plugin's namespace
     * @param value what to store
     */
    public void set(@Nullable ItemStack item, @NotNull String key, @NotNull String value) {
        write(item, key, PersistentDataType.STRING, value);
    }

    /**
     * Stores a whole number on the item.
     *
     * @param item  the item to write onto; {@code null} or air is ignored
     * @param key   the key, in this plugin's namespace
     * @param value what to store
     */
    public void set(@Nullable ItemStack item, @NotNull String key, long value) {
        write(item, key, PersistentDataType.LONG, value);
    }

    /**
     * Stores a decimal on the item.
     *
     * @param item  the item to write onto; {@code null} or air is ignored
     * @param key   the key, in this plugin's namespace
     * @param value what to store
     */
    public void set(@Nullable ItemStack item, @NotNull String key, double value) {
        write(item, key, PersistentDataType.DOUBLE, value);
    }

    /**
     * Stores a yes or no on the item.
     *
     * @param item  the item to write onto; {@code null} or air is ignored
     * @param key   the key, in this plugin's namespace
     * @param value what to store
     */
    public void set(@Nullable ItemStack item, @NotNull String key, boolean value) {
        write(item, key, PersistentDataType.BOOLEAN, value);
    }

    /**
     * Removes a value, if it is there.
     *
     * @param item the item to write onto; {@code null} or air is ignored
     * @param key  the key, in this plugin's namespace
     */
    public void clear(@Nullable ItemStack item, @NotNull String key) {
        if (empty(item)) {
            return;
        }
        NamespacedKey namespaced = key(key);
        ItemMeta meta = item.getItemMeta();
        if (namespaced == null || meta == null) {
            return;
        }
        meta.getPersistentDataContainer().remove(namespaced);
        item.setItemMeta(meta);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * The stored value in whatever type it was written as.
     *
     * <p>Every type the two writers can produce is tried, in the order that
     * costs the least: the declarative writer files booleans, integers, doubles
     * and strings, and this class adds longs. A type nobody in the library
     * writes is not looked for; a plugin storing one with the Bukkit API
     * directly reads it back the same way.
     */
    private @Nullable Object raw(@Nullable ItemStack item, @NotNull String key) {
        PersistentDataContainerView container = read(item);
        NamespacedKey namespaced = key(key);
        if (container == null || namespaced == null) {
            return null;
        }
        for (PersistentDataType<?, ?> type : TYPES) {
            Object value = container.get(namespaced, cast(type));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** The types the library writes, cheapest and most common first. */
    private static final PersistentDataType<?, ?>[] TYPES = {
            PersistentDataType.STRING,
            PersistentDataType.INTEGER,
            PersistentDataType.LONG,
            PersistentDataType.DOUBLE,
            PersistentDataType.BOOLEAN,
            PersistentDataType.BYTE,
    };

    @SuppressWarnings("unchecked")
    private static PersistentDataType<Object, Object> cast(PersistentDataType<?, ?> type) {
        return (PersistentDataType<Object, Object>) type;
    }

    private <T> void write(@Nullable ItemStack item, @NotNull String key,
                           @NotNull PersistentDataType<?, T> type, @NotNull T value) {
        if (empty(item)) {
            return;
        }
        NamespacedKey namespaced = key(key);
        ItemMeta meta = item.getItemMeta();
        if (namespaced == null || meta == null) {
            return;
        }
        // One entry per key, whatever type it held before: the container is a
        // map keyed by the name alone, so a rewrite replaces rather than
        // shadows. That is what lets raw() try the types in any order.
        meta.getPersistentDataContainer().set(namespaced, type, value);
        item.setItemMeta(meta);
    }

    /**
     * Whether there is no item here to write on.
     *
     * <p>Compared against the constant rather than asked with
     * {@code Material.isAir()}: that walks to the block registry, which is a
     * live-server lookup on a path that runs on every block break.
     */
    private static boolean empty(@Nullable ItemStack item) {
        return item == null || item.getType() == org.bukkit.Material.AIR;
    }

    /**
     * Reads the container without copying the meta.
     *
     * <p>{@code getItemMeta()} clones the whole meta, and every plugin asks an
     * item what it is on every hit and every click. Paper's read-only view
     * answers straight off the item.
     */
    private @Nullable PersistentDataContainerView read(@Nullable ItemStack item) {
        if (empty(item)) {
            return null;
        }
        return item.getPersistentDataContainer();
    }

    /**
     * A namespaced key, or {@code null} when the name cannot be one.
     *
     * <p>Minecraft only allows lowercase letters, digits and a few separators.
     * A caller passing anything else has a bug, but throwing from a read that
     * runs on every block break would turn it into a crash loop.
     */
    private @Nullable NamespacedKey key(@NotNull String key) {
        try {
            return new NamespacedKey(plugin, key);
        } catch (IllegalArgumentException notAKey) {
            return null;
        }
    }

    private String namespace() {
        return plugin.getName().toLowerCase(java.util.Locale.ROOT);
    }
}
