package net.exylia.lib.config;

import net.exylia.lib.text.Lore;
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
 * <p>Since 1.562.0 {@link #card} and {@link #hint} give every card the same clicks and the
 * same click lines, translated, and {@code <name>_default} shows what reset puts back.
 *
 * @since 1.555.0
 */
public final class ConfigEdits {

    private final Plugin plugin;
    private final Supplier<? extends ConfigFile<?>> file;
    private volatile Lore.Tone tone = Lore.Tone.PRIMARY;

    private ConfigEdits(Plugin plugin, Supplier<? extends ConfigFile<?>> file) {
        this.plugin = plugin;
        this.file = file;
    }

    /**
     * The tone of the cards these edits draw click lines for; {@link Lore.Tone#PRIMARY} by default.
     *
     * @since 1.562.0
     */
    public @NotNull ConfigEdits tone(@NotNull Lore.Tone tone) {
        this.tone = Objects.requireNonNull(tone, "tone");
        return this;
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

    /**
     * The value a key has in the file's defaults, as {@link #value} shows it; an empty string
     * when the record has no defaults or the file is not read.
     *
     * @since 1.562.0
     */
    public @NotNull String defaultValue(@NotNull String key) {
        ConfigFile<?> loaded = file.get();
        String shown = loaded == null ? null : ConfigMenu.displayDefault(loaded, key);
        return shown == null ? "" : shown;
    }

    /**
     * The click lines of a settings card, in the house wording and the server's language:
     * the click, what happens next, and the reset click while the value is not its default.
     * One value holding several lines; write it as a lore line of its own.
     *
     * @since 1.562.0
     */
    public @NotNull String hint(@NotNull String key) {
        ConfigFile<?> loaded = file.get();
        String shown = loaded == null ? null : ConfigMenu.cardHint(loaded, key, tone.colour().replaceAll("[{}]", ""));
        return shown == null ? "" : shown;
    }

    /** {@link #value} as a lambda for a menu context, so an open screen follows every change. */
    public @NotNull Supplier<String> live(@NotNull String key) {
        return () -> value(key);
    }

    /**
     * Every editable key as a live menu value, named {@code <prefix><key>} with dots and
     * dashes turned into underscores: {@code settings.listing-fee-percent} with prefix
     * {@code "ah_"} is {@code %ah_settings_listing_fee_percent%}. Each key also gets
     * {@code <name>_default} ({@link #defaultValue}) and {@code <name>_click} ({@link #hint})
     * since 1.562.0.
     *
     * @param prefix put before every name, so two files never collide on one screen
     */
    public @NotNull Map<String, Object> placeholders(@NotNull String prefix) {
        ConfigFile<?> loaded = file.get();
        if (loaded == null) return Map.of();
        return placeholders(prefix, ConfigMenu.keys(loaded));
    }

    /**
     * {@link #placeholders(String)} for only these keys: what a hub showing a few values of
     * several files puts in its context, since every live value is read on each redraw.
     *
     * @since 1.573.0
     */
    public @NotNull Map<String, Object> placeholders(@NotNull String prefix, @NotNull java.util.Collection<String> keys) {
        if (file.get() == null) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : keys) {
            String name = prefix + key.replace('.', '_').replace('-', '_');
            values.put(name, live(key));
            values.put(name + "_default", defaultValue(key));
            values.put(name + "_click", (Supplier<String>) () -> hint(key));
        }
        return values;
    }

    /**
     * A click on a settings card, the way every card behaves: shift-right-click puts the
     * value back to its default, a number or an amount is typed (never stepped), a switch
     * flips, a choice steps forward (right-click back), anything else is asked for prefilled.
     * {@link #hint} describes exactly this.
     *
     * @param back reopens the plugin's screen after a prompt (answered or cancelled)
     * @return {@code false} when the key is unknown or is a section or list
     * @since 1.562.0
     */
    public boolean card(@NotNull Player viewer, @NotNull String key, @Nullable ClickKind click, @NotNull Runnable back) {
        ConfigFile<?> loaded = file.get();
        if (loaded == null) return false;
        if (click == ClickKind.SHIFT_RIGHT) return ConfigMenu.reset(viewer, plugin, loaded, key.toLowerCase(Locale.ROOT));
        String name = click == ClickKind.RIGHT ? "right" : "left";
        String typed = ConfigMenu.typed(loaded, key.toLowerCase(Locale.ROOT)) ? "drop" : name;
        return ConfigMenu.click(viewer, plugin, loaded, key.toLowerCase(Locale.ROOT), typed, back);
    }

    /**
     * Puts a key back to its default value, written and reloaded like any other change.
     *
     * @return {@code false} when the record has no defaults or no such key
     * @since 1.562.0
     */
    public boolean reset(@NotNull Player viewer, @NotNull String key) {
        ConfigFile<?> loaded = file.get();
        return loaded != null && ConfigMenu.reset(viewer, plugin, loaded, key.toLowerCase(Locale.ROOT));
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
