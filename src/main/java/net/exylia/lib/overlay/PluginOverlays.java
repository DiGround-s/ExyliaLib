package net.exylia.lib.overlay;

import net.exylia.lib.item.Problems;
import net.exylia.lib.ui.UiSounds;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * One plugin's lower-inventory overlays.
 *
 * <p>What this plugin put on a player is taken off when it is disabled, so a
 * reload never leaves somebody wearing buttons whose actions no longer exist.
 *
 * @since 1.79.0
 */
public interface PluginOverlays {

    /** The plugin these overlays belong to. */
    @NotNull Plugin plugin();

    /**
     * Sets what this plugin's overlays sound like unless a file says otherwise.
     *
     * @param sounds the defaults
     * @return this
     */
    @NotNull PluginOverlays sounds(@NotNull UiSounds sounds);

    // ----------------------------------------------------------------- loading

    /**
     * Compiles an overlay and registers it under an id.
     *
     * <p>Do this when configuration loads, never when a player asks: reading a
     * file is the expensive half.
     *
     * @param id     what to call it, such as {@code staff}
     * @param config the file's root section
     * @return the compiled overlay
     * @throws IllegalArgumentException if the file is not an overlay
     */
    @NotNull OverlayDefinition load(@NotNull String id, @NotNull ConfigurationSection config);

    /**
     * Compiles an overlay, reporting bad parts wherever the caller wants them.
     *
     * @param id       what to call it
     * @param config   the file's root section
     * @param problems where to report bad parts
     * @return the compiled overlay
     */
    @NotNull OverlayDefinition load(@NotNull String id, @NotNull ConfigurationSection config,
                                    @NotNull Problems problems);

    /**
     * Registers an already-compiled overlay.
     *
     * @param definition the overlay
     * @return this
     */
    @NotNull PluginOverlays register(@NotNull OverlayDefinition definition);

    /**
     * The overlay registered under an id, qualified or not.
     *
     * @param id the id
     * @return the overlay, if there is one
     */
    @NotNull Optional<OverlayDefinition> definition(@NotNull String id);

    /** Everything this plugin has registered, by id. */
    @NotNull Map<String, OverlayDefinition> definitions();

    /** Forgets every registered overlay, for a configuration reload. */
    void unload();

    // ----------------------------------------------------------------- wearing

    /**
     * Puts a registered overlay on a player.
     *
     * <p>Whatever they were wearing comes off first: a player wears one
     * overlay at a time, whichever plugin put it there.
     *
     * @param viewer the player
     * @param id     the overlay's id
     * @return whether an overlay by that id is registered
     */
    boolean show(@NotNull Player viewer, @NotNull String id);

    /**
     * Puts an overlay on a player.
     *
     * <p>Safe from any thread; the drawing hops to the player's thread first.
     * Does nothing at all when PacketEvents is absent.
     *
     * @param viewer     the player
     * @param definition the overlay
     */
    void show(@NotNull Player viewer, @NotNull OverlayDefinition definition);

    /**
     * Takes this plugin's overlay off a player.
     *
     * <p>Nothing has to be restored — the real inventory was never written to
     * — so this is the server saying what it always believed.
     *
     * @param viewer the player
     */
    void hide(@NotNull Player viewer);

    /**
     * Returns whether a player is wearing one of this plugin's overlays.
     *
     * @param viewer the player
     * @return whether this plugin put an overlay on them
     */
    boolean isShowing(@NotNull Player viewer);

    /**
     * The overlay of this plugin's a player is wearing.
     *
     * @param viewer the player
     * @return the overlay, if this plugin put one on them
     */
    @NotNull Optional<OverlayDefinition> showing(@NotNull Player viewer);

    /**
     * Redraws a player's overlay now, without waiting for its timer.
     *
     * @param viewer the player
     */
    void refresh(@NotNull Player viewer);

    /** Takes every overlay this plugin put on back off. */
    void hideAll();
}
