package net.exylia.lib.util.crate;

import net.exylia.lib.util.crate.internal.AdminScreens;
import net.exylia.lib.util.showcase.PluginShowcases;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The admin menu a crate-backed cosmetic plugin gets for free: a hub with the
 * catalogue (give a cosmetic's item, give crate keys, preview it), the players
 * online (crate keys, unlocks one by one, what they have equipped, clears
 * behind a confirmation), the showcases and the crate blocks (teleport,
 * remove, add, clear).
 *
 * <pre>{@code
 * crates.admin()
 *         .permission("exyliaarrows.admin")
 *         .showcases(showcases)
 *         .preview((viewer, id) -> preview(viewer, id))
 *         .equipped(uuid -> players.cached(uuid).effect(), players::clearEffect)
 *         .favourites(players::clearFavourites);
 *
 * // The admin command with no arguments, for a player:
 * crates.admin().open(player);
 * }</pre>
 *
 * <p>Everything here is optional except {@link #open}: a screen whose hook was
 * not given hides its button. The screens and their words are the library's
 * ({@code plugins/ExyliaLib/lang/<code>/menus/cosmetics/} and its phrases), so
 * every cosmetic plugin looks and reads the same. The hooks are read on every
 * opening, so setting them again on reload is enough.
 *
 * <p>The {@code equipped} lookup runs every second while a screen shows it, on
 * the viewer's thread: it must read memory, never a database.
 *
 * @since 1.342.0
 */
public final class CrateAdmin {

    private final PluginCrates crates;
    private volatile String permission;
    private volatile @Nullable AdminScreens.Places showcases;
    private volatile @Nullable BiConsumer<Player, String> preview;
    private volatile @Nullable Function<UUID, String> equipped;
    private volatile @Nullable Consumer<Player> unequip;
    private volatile @Nullable Consumer<Player> favourites;

    CrateAdmin(@NotNull Plugin plugin, @NotNull PluginCrates crates) {
        this.crates = crates;
        this.permission = plugin.getName().toLowerCase(Locale.ROOT) + ".admin";
    }

    /** What an admin needs to use the screens; {@code <plugin>.admin} by default. */
    public @NotNull CrateAdmin permission(@NotNull String permission) {
        this.permission = permission;
        return this;
    }

    /** The plugin's showcases, listed under their own card. */
    public @NotNull CrateAdmin showcases(@Nullable PluginShowcases showcases) {
        this.showcases = showcases == null ? null : AdminScreens.Places.of(showcases);
        return this;
    }

    /**
     * Showcases a plugin keeps in a class of its own, by what the card needs.
     *
     * @param all        every place, as {@code ExyliaLocation} strings
     * @param add        adds one where the admin stands
     * @param removeNear removes the one nearest a spot
     * @param clear      removes every one
     */
    public @NotNull CrateAdmin showcases(@NotNull Supplier<List<String>> all, @NotNull Consumer<Location> add,
                                         @NotNull Consumer<Location> removeNear, @NotNull Runnable clear) {
        this.showcases = new AdminScreens.Places(all, add, removeNear, clear);
        return this;
    }

    /** Plays a cosmetic, by its crate id, for the admin who asked. Called on their thread, window closed. */
    public @NotNull CrateAdmin preview(@Nullable BiConsumer<Player, String> preview) {
        this.preview = preview;
        return this;
    }

    /**
     * What a player has on, and how to take it off.
     *
     * @param current the crate id a player has equipped, or {@code null}; from memory, any thread
     * @param takeOff takes it off; run on that player's thread
     */
    public @NotNull CrateAdmin equipped(@Nullable Function<UUID, String> current,
                                        @Nullable Consumer<Player> takeOff) {
        this.equipped = current;
        this.unequip = takeOff;
        return this;
    }

    /** Empties a player's favourites, for plugins that keep a shortlist. Run on that player's thread. */
    public @NotNull CrateAdmin favourites(@Nullable Consumer<Player> clear) {
        this.favourites = clear;
        return this;
    }

    /**
     * Opens the hub. The crate must be started.
     *
     * @param admin who looks; must hold the permission, checked by the caller's command
     */
    public void open(@NotNull Player admin) {
        crates.openAdmin(admin);
    }

    AdminScreens.Hooks hooks() {
        return new AdminScreens.Hooks(permission, showcases, preview, equipped, unequip, favourites);
    }
}
