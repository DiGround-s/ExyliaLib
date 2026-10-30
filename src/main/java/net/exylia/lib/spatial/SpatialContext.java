package net.exylia.lib.spatial;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One open spatial menu, as its sources, actions and preview see it: who looks at it, what they have
 * chosen, which page each list is on, and the menu's own state.
 *
 * <p>State set here is the menu's alone and goes when it closes. Every value set is also a
 * {@code %key%} placeholder in the layout's TEXT lines and titles. Used from the viewer's thread,
 * where every callback runs.
 *
 * @since 1.406.0
 */
public interface SpatialContext {

    @NotNull Player viewer();

    /** The menu's id, as registered. */
    @NotNull String menu();

    /** A value of this menu's state, or {@code null}. */
    @Nullable Object get(@NotNull String key);

    /** A value of this menu's state, or {@code fallback} when it is unset or of another type. */
    <T> @NotNull T get(@NotNull String key, @NotNull T fallback);

    /** Sets a value of this menu's state, or removes it with {@code null}. Redraws nothing by itself. */
    void set(@NotNull String key, @Nullable Object value);

    /** The page a source's list is on, from 0. */
    int page(@NotNull String source);

    /** Moves a source's list to a page (wrapping round either end) and redraws it. */
    void page(@NotNull String source, int page);

    /** The id of the entry chosen in a source, or {@code null}. */
    @Nullable String selected(@NotNull String source);

    /** Chooses an entry in a source by id ({@code null} for none); its marker glides there on the next redraw. */
    void select(@NotNull String source, @Nullable String entry);

    /**
     * Reads every source, the TEXT lines and the preview again and redraws what changed: equal
     * entries keep what they drew, a moved selection glides, nothing pops in again.
     */
    void refresh();

    /**
     * Plays the preview again from the start, now: an effect from its first step (its mannequin back
     * if it had vanished), an NPC or an item drawn afresh.
     *
     * @since 1.408.0
     */
    void replay();

    /** Closes the menu, deflating it. */
    void close();

    // ── Drafts (since 1.414.0) ────────────────────────────────────────────

    /**
     * The id of the entry under the crosshair in a source, or {@code null}: what an information panel
     * shows while the player browses, before they click. A change redraws the TEXT lines and titles.
     *
     * @since 1.414.0
     */
    default @Nullable String hovered(@NotNull String source) {
        return null;
    }

    /**
     * What the player has drafted for {@code key}, or the saved value when they have not: what the
     * preview and the panels show. A key that names a source is that source's selection. See
     * {@link SpatialMenu#saved}.
     *
     * @since 1.414.0
     */
    default @Nullable Object draft(@NotNull String key) {
        return get(key);
    }

    /**
     * Drafts a value for {@code key}: shown at once, stored only by SAVE. {@code null} or the saved
     * value itself is no draft. For a key that names a source it selects that entry. Redraws nothing
     * by itself.
     *
     * @since 1.414.0
     */
    default void draft(@NotNull String key, @Nullable Object value) {
        set(key, value);
    }

    /**
     * Whether any draft differs from what is saved: the SAVE button is lit, and leaving discards it.
     *
     * @since 1.414.0
     */
    default boolean dirty() {
        return false;
    }

    /**
     * The keys whose draft differs from what is saved, in the order the saved map gives them.
     *
     * @since 1.414.0
     */
    default @NotNull java.util.Set<String> changed() {
        return java.util.Set.of();
    }

    /**
     * Drops every draft, back to what is saved (a source's selection too), and redraws: the REVERT
     * button, or the layout action {@code revert}.
     *
     * @since 1.414.0
     */
    default void revert() {
    }

    /**
     * Runs the menu's {@link SpatialMenu#onSave save} and redraws: the SAVE button, or the layout
     * action {@code save}.
     *
     * @since 1.414.0
     */
    default void save() {
    }

    // ── Undo (since 1.423.0) ──────────────────────────────────────────────

    /**
     * Whether UNDO has a step to take back: a click (an entry chosen, a button pressed) that changed the
     * drafts since the menu opened or was last saved. What an UNDO label reads by.
     *
     * @since 1.423.0
     */
    default boolean canUndo() {
        return false;
    }

    /**
     * Takes the newest step back: the drafts (and the selections of saved sources) go back to what they
     * were before the last click that changed them, and the menu redraws. The layout action {@code undo}
     * runs it when the plugin registers no action of that name. Every click is one step, however many
     * drafts it changed; REVERT is a step too, so UNDO brings back what it dropped. SAVE starts the history
     * again. Nothing to undo plays the theme's error sound.
     *
     * @since 1.423.0
     */
    default void undo() {
    }

    // ── Confirm in place (since 1.416.0) ──────────────────────────────────

    /**
     * A click that asks before it acts, without leaving the menu: the first call with {@code key} arms
     * it and answers {@code false} (the plugin's words then read CONFIRM, its button warms like a dirty
     * SAVE), a second call with the same key within six seconds answers {@code true}. Choosing an entry,
     * turning a page, any other button, reverting or waiting disarms it.
     *
     * <pre>{@code
     * .action("buy", ctx -> {
     *     if (!ctx.confirm("buy:" + id)) { ctx.refresh(); return; }   // first click: the label reads CONFIRM
     *     pay(ctx);                                                   // second click
     * })
     * }</pre>
     *
     * @param key {@code "<action>"} or {@code "<action>:<subject>"}: the button whose action is the part
     *            before the colon is the one lit; a new subject re-arms rather than confirms
     * @since 1.416.0
     */
    default boolean confirm(@NotNull String key) {
        return true;
    }

    /**
     * Whether {@code key} (or a subject under it, {@code key:...}) waits for its second click: what a
     * label reads CONFIRM by.
     *
     * @since 1.416.0
     */
    default boolean confirming(@NotNull String key) {
        return false;
    }

    /** Drops a waiting confirmation. @since 1.416.0 */
    default void disarm() {
    }
}
