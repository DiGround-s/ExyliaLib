package net.exylia.lib.config;

import net.exylia.lib.ui.ClickKind;
import net.exylia.lib.web.internal.ConfigMenu;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The plumbing behind a plugin's own settings cards: the plugin designs the screen
 * (which settings, grouped how, explained in its own words) and this reads and writes
 * the values.
 *
 * <p>A click goes through the path {@link ConfigMenus} uses: switches and choices step,
 * numbers step ({@code shift} ×10) or are typed on {@code drop}, a length of time, text,
 * lines or an item are asked for prefilled, the value is checked against its
 * {@link Menu.Range}, {@link Menu.AtLeast} and {@link Menu.AtMost}, written with
 * {@link ConfigFile#set} and the plugin reloaded through its declared {@code Reloads}.
 *
 * <pre>{@code
 * ConfigEdits fees = ConfigEdits.of(plugin, AuctionHouseDefaults::file);
 * context.put("fee", fees.live("settings.listing-fee-percent"));           // lore: %fee%
 * actions.registerSync("ah_setting", "myplugin.admin", (ctx, args) -> {
 *     fees.click(ctx.player(), args.string(0, ""), ctx.require(UiKeys.CLICK), () -> reopen(ctx.player()));
 *     return ActionResult.success();
 * });
 * }</pre>
 *
 * <p>The plugin gates the action with its own permission. Any thread.
 *
 * @since 1.555.0
 */
public final class ConfigEdits {

    private final Plugin plugin;
    private final Supplier<? extends ConfigFile<?>> file;

    private ConfigEdits(Plugin plugin, Supplier<? extends ConfigFile<?>> file) {
        this.plugin = plugin;
        this.file = file;
    }

    /**
     * The edits of one file.
     *
     * @param plugin the owner, whose {@code Reloads} run after a change
     * @param file   the loaded file, read on every call so a reload is followed
     */
    public static @NotNull ConfigEdits of(@NotNull Plugin plugin, @NotNull Supplier<? extends ConfigFile<?>> file) {
        return new ConfigEdits(Objects.requireNonNull(plugin, "plugin"), Objects.requireNonNull(file, "file"));
    }

    /**
     * The value of a key as the settings screens show it, coloured ({@code {success}Enabled},
     * {@code {info}48h}...), or an empty string before the file is read.
     *
     * @param key the dotted YAML key, such as {@code settings.listing-fee-percent}
     * @throws IllegalArgumentException when the file has no such key
     */
    public @NotNull String value(@NotNull String key) {
        ConfigFile<?> loaded = file.get();
        if (loaded == null) return "";
        String shown = ConfigMenu.display(loaded, key);
        if (shown == null) throw new IllegalArgumentException("No key " + key + " in " + loaded.name());
        return shown;
    }

    /** {@link #value} as a lambda for a menu context, so an open screen follows every change. */
    public @NotNull Supplier<String> live(@NotNull String key) {
        return () -> value(key);
    }

    /**
     * Every editable key as a live menu value, named {@code <prefix><key>} with dots and
     * dashes turned into underscores: {@code settings.listing-fee-percent} with prefix
     * {@code "ah_"} is {@code %ah_settings_listing_fee_percent%}.
     *
     * @param prefix put before every name, so two files never collide on one screen
     */
    public @NotNull Map<String, Object> placeholders(@NotNull String prefix) {
        ConfigFile<?> loaded = file.get();
        if (loaded == null) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : ConfigMenu.keys(loaded)) {
            values.put(prefix + key.replace('.', '_').replace('-', '_'), live(key));
        }
        return values;
    }

    /**
     * Applies one click to a key.
     *
     * @param viewer who clicked; their inventory is closed when a value is asked for
     * @param key    the dotted YAML key
     * @param click  the click; {@code DROP} (Q) types a number
     * @param back   reopens the plugin's screen after a prompt (answered or cancelled)
     * @return {@code false} when the key is unknown or is a section or list, which this does not edit
     */
    public boolean click(@NotNull Player viewer, @NotNull String key, @Nullable ClickKind click, @NotNull Runnable back) {
        ConfigFile<?> loaded = file.get();
        if (loaded == null) return false;
        String name = click == null ? "left" : switch (click) {
            case RIGHT -> "right";
            case SHIFT_LEFT -> "shift_left";
            case SHIFT_RIGHT -> "shift_right";
            case DROP, CONTROL_DROP -> "drop";
            default -> "left";
        };
        return ConfigMenu.click(viewer, plugin, loaded, key.toLowerCase(Locale.ROOT), name, back);
    }
}
