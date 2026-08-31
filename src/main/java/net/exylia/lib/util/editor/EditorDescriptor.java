package net.exylia.lib.util.editor;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Everything the list editor needs to know about one kind of thing.
 *
 * <p>Implement this and a type gets pagination, add, edit, delete, copy, paste,
 * save and cancel — the whole screen — without a menu, a session or a clipboard
 * being written for it.
 *
 * <pre>{@code
 * final class WarpDescriptor implements EditorDescriptor<Warp> {
 *
 *     public String label(Warp warp)      { return warp.name(); }
 *     public String icon(Warp warp)       { return "ENDER_PEARL"; }
 *     public List<String> lore(Warp warp) { return List.of(" {letters_black}▎ {info}" + warp.world()); }
 *     public Warp create()                { return new Warp("new-warp", "world"); }
 *     public Warp copy(Warp warp)         { return warp.withName(warp.name() + "-copy"); }
 *
 *     public CompletionStage<Optional<Warp>> edit(Player viewer, Warp warp) {
 *         return Editors.form(viewer, "{primary}&lWARP", warp, …);
 *     }
 * }
 * }</pre>
 *
 * <h2>Why this exists rather than five editors</h2>
 * ExyliaCommons had five of these screens — rewards, loot, potions, commands,
 * messages — copy-pasted from each other and drifting apart. One of them
 * addressed rows by slot index, so editing a row after a search edited a
 * different row. There is one screen here, and a new type is one interface.
 *
 * <h2>Contracts</h2>
 * Everything except {@link #edit} is called while drawing a page and must be
 * cheap, pure and free of Bukkit calls that need a main thread — a page draws
 * up to 45 rows, and it redraws after every click. Nothing here may throw: a
 * row nobody can describe is still drawn, as itself, so an admin can delete it.
 *
 * @param <T> what is being edited
 * @since 1.56.0
 */
public interface EditorDescriptor<T> {

    /**
     * The name of a row, in Exylia text notation.
     *
     * @param entry the row's element
     * @return the name; never {@code null} or blank
     */
    @NotNull String label(@NotNull T entry);

    /**
     * What a row is drawn as: a material name, a head string, or a
     * {@code bytes:} snapshot — the same grammar every menu file writes.
     *
     * @param entry the row's element
     * @return the icon source
     */
    @NotNull String icon(@NotNull T entry);

    /**
     * The detail lines under a row's name, in Exylia text notation.
     *
     * <p>The click hints — edit, delete, copy — are added by the editor, so a
     * descriptor writes only what the element <em>is</em>.
     *
     * @param entry the row's element
     * @return the lines, possibly none
     */
    @NotNull List<String> lore(@NotNull T entry);

    /**
     * The same lines, with the rest of the list in view.
     *
     * <p>What {@link #lore(Object)} cannot say: an entry's odds mean nothing on
     * their own. Twenty rows at twenty percent and five rows at twenty percent
     * read identically row by row, and are not the same table. Override this
     * where a row's share of its siblings is worth drawing.
     *
     * <p>Called instead of {@link #lore(Object)} while drawing, so an override
     * replaces it rather than adding to it. The list is the editor's own and
     * must not be modified.
     *
     * @param entry    the row's element
     * @param siblings every element on the screen, this one included
     * @return the lines, possibly none
     */
    default @NotNull List<String> lore(@NotNull T entry, @NotNull List<T> siblings) {
        return lore(entry);
    }

    /**
     * A new element, for the add button.
     *
     * <p>Returned blank rather than asked for: the editor opens {@link #edit} on
     * it immediately, so a half-configured element only ever exists inside the
     * screen that is configuring it.
     *
     * @return a new element
     */
    @NotNull T create();

    /**
     * A new element, asked for rather than assumed.
     *
     * <p>The default hands back {@link #create()} without asking anything, which
     * is right for a type whose blank state is obvious — a warp, a message, a
     * location. Override it where creating the thing <em>is</em> a question: a
     * reward has to be told whether it gives an item, a command or money before
     * a form over it can even name its fields.
     *
     * <p>Answering with nothing cancels the add, and no row appears.
     *
     * @param viewer who is adding
     * @return the new element, or nothing
     */
    default @NotNull CompletionStage<Optional<T>> create(@NotNull Player viewer) {
        return java.util.concurrent.CompletableFuture.completedFuture(Optional.of(create()));
    }

    /**
     * Everything one press of add should put in the list.
     *
     * <p>The default is {@link #create(Player)} wrapped, which is what add has
     * always meant: one element, configured through {@link #edit} before it
     * becomes a row. Override it where a single gesture can honestly produce
     * several — importing a chest's contents, loading a preset — and the rows
     * are added as they are, without an edit each.
     *
     * <p>Answering with nothing cancels the add. Exactly one element still goes
     * through {@link #edit}; more than one does not, because a form per row is
     * not what somebody importing thirty items asked for.
     *
     * @param viewer who is adding
     * @return the new elements, possibly none
     * @since 1.77.0
     */
    default @NotNull CompletionStage<List<T>> createAll(@NotNull Player viewer) {
        return create(viewer).thenApply(created ->
                created.<List<T>>map(List::of).orElseGet(List::of));
    }

    /**
     * The same element again, under a new identity.
     *
     * <p>What paste means. An implementation that returns the element unchanged
     * makes two rows that are the same object, and deleting one deletes both.
     *
     * @param entry what to duplicate
     * @return the duplicate
     */
    @NotNull T copy(@NotNull T entry);

    /**
     * Asks the viewer what this element should be.
     *
     * <p>Normally one dialog with every field prefilled — see
     * {@link EditorForm}. The stage completes with the edited element, or with
     * {@link Optional#empty()} when the viewer backed out; either way the editor
     * redraws its list afterwards.
     *
     * @param viewer who is editing
     * @param entry  the element as it stands
     * @return the edited element, or nothing
     */
    @NotNull CompletionStage<Optional<T>> edit(@NotNull Player viewer, @NotNull T entry);

    /**
     * Which clipboard bucket this type copies into.
     *
     * <p>Two editors sharing a key can paste into each other, which is the
     * point: a loot table copied out of a chest belongs in a spawner. Two that
     * do not share one cannot, which is also the point.
     *
     * @return a stable key; the type's own name by default
     */
    default @NotNull String typeKey() {
        return getClass().getName();
    }

    /**
     * Whether an element is complete enough to be worth keeping.
     *
     * <p>Incomplete rows are still drawn and still saved — an editor is where a
     * half-configured element gets finished — but they are marked, so nobody
     * closes the screen thinking a row does something it does not.
     *
     * @param entry the element
     * @return whether it is finished
     */
    default boolean isComplete(@NotNull T entry) {
        return true;
    }
}
