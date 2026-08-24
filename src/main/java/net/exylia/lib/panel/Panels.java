package net.exylia.lib.panel;

import net.exylia.lib.panel.internal.PanelRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Configuration panels: a record edited on screen, with undo and a diff before
 * anything is written.
 *
 * <pre>{@code
 * // the whole editor for a config file — no per-record code
 * Panels.of(this).settings(effectConfigFile).open(player);
 * }</pre>
 *
 * <h2>What a panel is</h2>
 * A panel edits a <em>working copy</em>. Every edit pushes a snapshot, nothing
 * reaches the config file until save, and a save whose {@link PanelDiff} is
 * empty writes nothing at all. That is what makes opening a panel to look at
 * something harmless.
 *
 * <h2>What is where</h2>
 * {@link PluginPanels} is a plugin's own panels, so releasing one plugin leaves
 * another's alone. {@link PanelSession} is one player's open panel, and the only
 * thing a click is checked against. {@link PanelDiff} is what a save would
 * change, by component name.
 *
 * <h2>Themes</h2>
 * Slots, sizes, titles and colours come from layout files owned by ExyliaLib,
 * the same way the palette is: a server themes every panel once rather than
 * twenty plugins shipping two files each. A missing or malformed layout falls
 * back to a built-in default, is reported once, and still opens a usable panel.
 *
 * <h2>Threads</h2>
 * {@code open} is safe from anywhere and moves itself onto the thread that owns
 * the player. The editing half of a session — undo, diff, the working copy —
 * touches no Bukkit API and is safe from any thread.
 *
 * <h2>Palette reload</h2>
 * This module caches nothing derived from the palette, so it has no
 * {@code invalidateAll()} and no hook in {@code loadPalette}. It holds layouts
 * and item <em>definitions</em>, which carry raw text such as
 * {@code {primary}}; what that resolves to is decided when a panel is drawn.
 * Stated rather than left silent, and enforced by {@code PanelPaletteTest}.
 * See {@code docs/reload.md}.
 *
 * @since 1.50.0
 */
public final class Panels {

    /**
     * How many edits a panel remembers.
     *
     * <p>A memory bound, not a preference, which is why it is a constant rather
     * than a setting. A snapshot is a reference — the previous record instance
     * the next edit would have discarded anyway — so the realistic worst case, a
     * five-hundred entry list, is about eighty kilobytes for as long as the
     * panel is open. Twenty is above what anybody undoes in one sitting and
     * below where the copying starts to show.
     */
    private static final int UNDO_LIMIT = 20;

    private Panels() {
        throw new AssertionError("No instances.");
    }

    /**
     * The panels of a plugin.
     *
     * <p>Per plugin so that disabling one closes its panels and leaves everyone
     * else's alone, and so a save is attributed to the plugin that owns the
     * config being edited.
     *
     * @param plugin the plugin
     * @return its panels
     */
    public static @NotNull PluginPanels of(@NotNull Plugin plugin) {
        return new PluginPanels(PanelRuntime.of(plugin));
    }

    /**
     * The panel a player has open, whoever owns it.
     *
     * <p>Resolved from the window rather than from a map keyed by player, so a
     * player who opened a chest on top of a panel resolves to empty — they are
     * looking at the chest.
     *
     * @param viewer the player
     * @return the session, or empty when they have no panel open
     */
    public static @NotNull Optional<PanelSession> session(@NotNull Player viewer) {
        return Optional.ofNullable(PanelRuntime.publicSessionOf(viewer));
    }

    /**
     * How many edits a panel remembers before the oldest is discarded.
     *
     * <p>Exposed so a panel can say so on screen, and so a test asserts against
     * the bound rather than against a number it copied.
     *
     * @return the bound, currently 20
     */
    public static int undoLimit() {
        return UNDO_LIMIT;
    }

    /**
     * Releases everything a plugin's panels hold; lifecycle calls this.
     *
     * <p>Panels already on screen are closed, and the delayed steps their
     * buttons started are cancelled.
     *
     * @param pluginName the plugin being disabled
     */
    public static void release(@NotNull String pluginName) {
        PanelRuntime.release(pluginName);
    }

    /** Releases every panel of every plugin. */
    public static void releaseAll() {
        PanelRuntime.releaseAll();
    }

    /** How many plugins have panels, for diagnostics. */
    public static int registered() {
        return PanelRuntime.registered();
    }
}
