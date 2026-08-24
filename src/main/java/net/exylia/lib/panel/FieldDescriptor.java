package net.exylia.lib.panel;

import net.exylia.lib.input.InputResult;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;

/**
 * Everything a list panel needs to know about one kind of element.
 *
 * <p>This is the whole extension point. Supplying one of these is what makes a
 * paginated, searchable, copyable, undoable editor exist for a type — there is
 * no panel to write, no menu file to add, no session, registry or clipboard
 * class. ExyliaCommons had five list editors that differed only in these nine
 * answers; here there is one editor and nine answers.
 *
 * <pre>{@code
 * final class WarpDescriptor implements FieldDescriptor<Warp> {
 *
 *     private final WarpStore store;
 *
 *     WarpDescriptor(WarpStore store) {
 *         this.store = store;
 *     }
 *
 *     @Override public String label(Warp warp)    { return warp.displayName(); }
 *     @Override public String icon(Warp warp)     { return "ENDER_PEARL"; }
 *     @Override public String identity(Warp warp) { return warp.id().toString(); }
 *
 *     @Override public Warp create()              { return Warp.named("new-warp"); }
 *     @Override public Warp duplicate(Warp warp)  { return warp.withId(UUID.randomUUID()); }
 *
 *     @Override
 *     public CompletionStage<InputResult<Warp>> edit(Player viewer, Warp warp) {
 *         return Inputs.of(plugin).text(viewer, "New name").open()
 *                 .thenApply(result -> result.map(warp::withName));
 *     }
 *
 *     @Override public List<Warp> load()               { return store.all(); }
 *     @Override public void save(List<Warp> warps)     { store.replaceAll(warps); }
 * }
 *
 * Panels.of(this).list(new WarpDescriptor(store)).open(player);
 * }</pre>
 *
 * <h2>Identity is not the payload</h2>
 * {@link #identity(Object)} answers "which row is this", and it must keep
 * answering the same thing while the element is edited. It is what makes a
 * duplicate distinguishable from the row it was copied from — two warps named
 * {@code spawn} are still two warps. If a type genuinely has no identity beyond
 * its contents, returning the label is correct; what is never correct is
 * returning something that changes when the element is edited, because a paste
 * and its original would then merge on the next redraw.
 *
 * <p>The panel never addresses a row by slot, page or list index. That is not an
 * implementation note — it is the bug this module was written to make
 * impossible, and it is why {@link #identity(Object)} exists at all.
 *
 * <h2>Threads</h2>
 * <ul>
 *   <li>{@link #label}, {@link #icon}, {@link #identity}, {@link #create},
 *       {@link #duplicate} and {@link #matches} are called from <b>any thread</b>
 *       and must be <b>pure</b>: no Bukkit API, no I/O, no mutation of anything
 *       the caller can see. They run while filtering and while drawing, which is
 *       often enough that a database read in one of them is felt.</li>
 *   <li>{@link #edit} is called on the thread that owns the viewer, and may
 *       answer whenever it likes — that is why it returns a stage. Anything it
 *       does with the game must get back to the viewer's thread first.</li>
 *   <li>{@link #load} is called once, as the panel opens, on the viewer's
 *       thread.</li>
 *   <li>{@link #save} is called <b>off the viewer's thread</b>, through
 *       {@code runAsync}, so it is the right place for a database write and the
 *       wrong place for anything that touches the game.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * A descriptor is a description, not a session: one instance may serve every
 * viewer and every open panel, and it is never told a panel closed. Anything
 * per-viewer belongs to the panel, which gives it back when the screen goes.
 *
 * @param <T> the element type; immutable is strongly preferred, because undo
 *            keeps references to previous states rather than deep copies
 * @since 1.50.0
 */
public interface FieldDescriptor<T> {

    /**
     * What the row says.
     *
     * <p>Also what the default {@link #matches} searches, so it should contain
     * whatever a viewer would type to find the element.
     *
     * @param entry the element
     * @return its display text; never {@code null}, never blank
     */
    @NotNull String label(@NotNull T entry);

    /**
     * What the row is drawn as.
     *
     * <p>A material name, or any head source the item module accepts
     * ({@code basehead-…}, {@code playerhead-…}). Resolved when the row is drawn,
     * so it may differ per element.
     *
     * @param entry the element
     * @return the material or head source; never {@code null}
     */
    @NotNull String icon(@NotNull T entry);

    /**
     * Which row this is.
     *
     * <p>Must be stable across an edit of the same element and distinct between
     * two elements that are different rows — including a copy and its original.
     * See the class documentation for why this matters more than it looks.
     *
     * @param entry the element
     * @return its identity; never {@code null}, never blank
     */
    @NotNull String identity(@NotNull T entry);

    /**
     * A new, empty element, for the add button.
     *
     * <p>Pure and any-thread. Whatever it returns is what the viewer will then
     * edit, so returning something already valid saves them a step.
     *
     * @return the new element
     */
    @NotNull T create();

    /**
     * A copy of an element, with a new identity.
     *
     * <p>The payload must match and the identity must not, or the copy and the
     * original become the same row the next time the list is drawn. Where a type
     * has a builder that preserves its id — {@code RewardEntry.toBuilder()} — the
     * copy is the one that must <em>not</em> use it.
     *
     * @param entry what was copied
     * @return a distinct element carrying the same payload
     */
    @NotNull T duplicate(@NotNull T entry);

    /**
     * Asks the viewer to change an element.
     *
     * <p>Returns a stage because a question is answered later, by a person. An
     * answer that {@link InputResult#completed() completed} replaces the element
     * in the working copy; anything else — cancelled, timed out, the viewer left
     * — leaves it exactly as it was, which is why this returns a result rather
     * than a value.
     *
     * <p>Nothing here is persisted. What comes back lands in the panel's working
     * copy, and only a save writes it out.
     *
     * @param viewer who is editing
     * @param entry  what they clicked
     * @return the new element, or a result saying they did not give one
     */
    @NotNull CompletionStage<InputResult<T>> edit(@NotNull Player viewer, @NotNull T entry);

    /**
     * The list as it is stored right now.
     *
     * <p>Called once, when a panel opens. The returned list is copied
     * immediately and never mutated, so returning an internal list is safe —
     * though returning an immutable one is better.
     *
     * @return every element, in the order they should be shown
     */
    @NotNull List<T> load();

    /**
     * Writes the list back.
     *
     * <p>Called <b>off the viewer's thread</b>, at most once per save, and only
     * when something actually changed: opening a list to look at it must not
     * rewrite the owner's file. The list given is the whole list, not a delta.
     *
     * @param entries what to store
     */
    void save(@NotNull List<T> entries);

    /**
     * Whether an element matches what a viewer typed.
     *
     * <p>The default searches {@link #label} case-insensitively, which is right
     * for most types. Override it when an element has more than one name worth
     * finding — an id, a permission node, a material — so that typing any of them
     * works.
     *
     * <p>Pure and any-thread: this runs once per element per keystroke.
     *
     * @param entry the element
     * @param query what was typed; already trimmed, never {@code null}
     * @return whether the element should be shown
     */
    default boolean matches(@NotNull T entry, @NotNull String query) {
        return label(entry).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }
}
