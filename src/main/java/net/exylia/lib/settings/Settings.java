package net.exylia.lib.settings;

import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.settings.internal.SettingsMenu;
import net.exylia.lib.settings.internal.SettingsRuntime;
import net.exylia.lib.settings.internal.SettingsRuntime.PluginStore;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Player preferences every Exylia plugin registers into, and the one
 * {@code /settings} screen that shows them all.
 *
 * <pre>{@code
 * // onEnable
 * Settings.category(this, "requests", "ENDER_PEARL", "Requests", "How other players reach you.");
 * Settings.register(this, Setting.toggle("trade-requests", true)
 *         .category("requests").icon("EMERALD").name("Trade requests")
 *         .description("Whether players can ask you to {highlight}trade{letters}.")
 *         .build());
 *
 * // anywhere, any thread
 * if (!Settings.enabled(target, this, "trade-requests")) { refuse(); return; }
 *
 * // from the plugin's own command, when the server keeps /settings off
 * Settings.open(player);
 * }</pre>
 *
 * <h2>Reads are memory</h2>
 * A player's values are read asynchronously when they join and every question
 * is answered from memory, synchronously and from any thread. Until the read
 * is back — the first moments of a join — the answer is the default, which is
 * what a player who never touched the setting has anyway. Nothing ever waits
 * for the database, so asking from a broadcast loop or from {@code onEnable}
 * is safe.
 *
 * <h2>Writes are the database</h2>
 * A change is in memory at once and stored after the player's previous change,
 * so two quick clicks land in order. Only a value that differs from the
 * default is stored. With Redis on, the servers sharing the plugin's database
 * hear the write and read the player again.
 *
 * <h2>Where values live</h2>
 * In the registering plugin's own database ({@code exylia_player_settings}),
 * keyed by player, plugin, key and server. A setting is network-wide unless it
 * is {@link Setting.Builder#perServer() per server}, in which case each server
 * keeps its own under its network id.
 *
 * <h2>The command</h2>
 * The library adds no player command unless the server asks: {@code /settings}
 * is registered only when {@code settings.command.enabled} is {@code true} in
 * ExyliaLib's {@code config.yml}. A plugin that has its own command opens the
 * same screen with {@link #open(Player)}, and checks {@link #commandEnabled()}
 * to avoid registering the same name twice.
 *
 * <h2>Lifecycle</h2>
 * Everything a plugin registered is forgotten when it is disabled. Register
 * again from {@code onEnable}; registering a key again replaces it, so a
 * reload may simply register everything again with its new text.
 *
 * @since 1.261.0
 */
public final class Settings {

    private Settings() {
        throw new AssertionError("No instances.");
    }

    // ------------------------------------------------------------------ registry

    /**
     * Adds a setting to the plugin's list, or replaces the one with the same key.
     *
     * @param plugin  the plugin it belongs to
     * @param setting the setting
     */
    public static void register(@NotNull Plugin plugin, @NotNull Setting setting) {
        SettingsRuntime.store(plugin).register(setting);
    }

    /**
     * Removes a setting from the screen. What players stored for it stays.
     *
     * @param plugin the plugin it belongs to
     * @param key    its key
     */
    public static void unregister(@NotNull Plugin plugin, @NotNull String key) {
        SettingsRuntime.find(plugin).ifPresent(store -> store.unregister(key));
    }

    /**
     * Describes a category: the card the plugin's settings page shows for it.
     * A category used without a description is named after its id.
     *
     * @param plugin      the plugin it belongs to
     * @param id          what settings name in {@link Setting.Builder#category}
     * @param icon        what its card is drawn as
     * @param name        its name, plain text
     * @param description its description, one lore line each
     */
    public static void category(@NotNull Plugin plugin, @NotNull String id, @NotNull String icon,
                                @NotNull String name, @NotNull String... description) {
        SettingsRuntime.store(plugin).category(new SettingsRuntime.Category(id.toLowerCase(java.util.Locale.ROOT),
                icon, name, List.of(description)));
    }

    // ------------------------------------------------------------------ reads

    /**
     * Whether a toggle is on for a player.
     *
     * @return the value, the default while it is not read yet, and {@code true}
     *         for a key the plugin never registered
     */
    public static boolean enabled(@NotNull Player player, @NotNull Plugin plugin, @NotNull String key) {
        String value = value(player, plugin, key);
        return value == null || Boolean.parseBoolean(value);
    }

    /**
     * A player's value, as text.
     *
     * <p>A player without the setting's permission reads its default.
     *
     * @return the value, the default while it is not read yet, or {@code null}
     *         for a key the plugin never registered
     */
    public static @Nullable String value(@NotNull Player player, @NotNull Plugin plugin, @NotNull String key) {
        PluginStore store = SettingsRuntime.find(plugin).orElse(null);
        Setting setting = store == null ? null : store.setting(key);
        if (setting == null) return null;
        if (!setting.allowed(player)) return setting.defaultValue();
        if (setting.store() != null) {
            String stored = setting.normalise(setting.store().get(player));
            return stored == null ? setting.defaultValue() : stored;
        }
        return store.value(player.getUniqueId(), setting);
    }

    /**
     * A number setting's value.
     *
     * @return the value, its default while it is not read yet, or {@code 0}
     *         for a key the plugin never registered
     */
    public static double number(@NotNull Player player, @NotNull Plugin plugin, @NotNull String key) {
        return Setting.parse(value(player, plugin, key));
    }

    /**
     * Whether a player's values for a plugin have been read, so a change made
     * now starts from what they really have.
     */
    public static boolean isLoaded(@NotNull Player player, @NotNull Plugin plugin) {
        return SettingsRuntime.find(plugin).map(store -> store.isLoaded(player.getUniqueId())).orElse(false);
    }

    // ------------------------------------------------------------------ writes

    /**
     * Changes a player's value. A number is kept between its minimum and maximum.
     *
     * <p>Safe before the player's values are read: only this key is written.
     *
     * @return {@code false} when the key is unknown or the value is not one it can hold
     */
    public static boolean set(@NotNull Player player, @NotNull Plugin plugin, @NotNull String key,
                              @NotNull String value) {
        PluginStore store = SettingsRuntime.find(plugin).orElse(null);
        Setting setting = store == null ? null : store.setting(key);
        String checked = setting == null ? null : setting.normalise(value);
        if (checked == null) return false;
        if (setting.store() != null) {
            setting.store().set(player, checked);
        } else {
            store.write(player.getUniqueId(), setting, checked);
        }
        return true;
    }

    /**
     * Changes the value of a player who may be offline — an import, an admin
     * command. Reaches the player's memory too when they are online here.
     *
     * <pre>{@code
     * Settings.set(uuid, this, "death-messages", "false");
     * }</pre>
     *
     * @return {@code false} when the key is unknown, kept in the plugin's own
     *         store, or the value is not one it can hold
     */
    public static boolean set(@NotNull java.util.UUID player, @NotNull Plugin plugin, @NotNull String key,
                              @NotNull String value) {
        PluginStore store = SettingsRuntime.find(plugin).orElse(null);
        Setting setting = store == null ? null : store.setting(key);
        String checked = setting == null || setting.store() != null ? null : setting.normalise(value);
        if (checked == null) return false;
        store.write(player, setting, checked);
        return true;
    }

    /**
     * Moves a setting one click: flips a toggle, steps a choice or a number forward.
     *
     * @return {@code false} when the key is unknown or the player's values are
     *         not read yet, and nothing changed
     */
    public static boolean toggle(@NotNull Player player, @NotNull Plugin plugin, @NotNull String key) {
        return step(player, plugin, key, 1);
    }

    /**
     * Moves a setting one click in a direction.
     *
     * @param direction {@code 1} forward, {@code -1} back
     * @return {@code false} when nothing changed because the key is unknown or
     *         the player's values are not read yet
     */
    public static boolean step(@NotNull Player player, @NotNull Plugin plugin, @NotNull String key,
                               int direction) {
        PluginStore store = SettingsRuntime.find(plugin).orElse(null);
        Setting setting = store == null ? null : store.setting(key);
        if (setting == null) return false;
        boolean loaded = setting.store() != null ? setting.store().loaded(player) : store.isLoaded(player.getUniqueId());
        if (!loaded) return false;
        String current = value(player, plugin, key);
        return set(player, plugin, key, setting.next(current == null ? setting.defaultValue() : current, direction));
    }

    // ------------------------------------------------------------------ screens

    /**
     * Opens the settings screen. Any thread.
     *
     * @param player who to show it to
     */
    public static void open(@NotNull Player player) {
        SettingsMenu.open(player);
    }

    /**
     * Opens the announcements screen directly. Any thread.
     *
     * @param player who to show it to
     */
    public static void openAnnouncements(@NotNull Player player) {
        SettingsMenu.openAnnouncements(player);
    }

    /**
     * Whether the library registered {@code /settings} on this server. A
     * plugin with its own settings command skips it when this is {@code true}.
     */
    public static boolean commandEnabled() {
        return LibrarySettings.get().settings().command().enabled();
    }
}
