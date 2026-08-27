package net.exylia.lib.util.editor;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * The open editor, as a custom button sees it.
 *
 * <p>What a button gets when it is clicked: who clicked, what the list holds
 * right now, and one way to change it. The screen redraws afterwards, so a
 * button never has to ask for that.
 *
 * <pre>{@code
 * EditorButton.<LootEntry>of("CHEST_MINECART")
 *         .name("{highlight}&lLOAD DEFAULTS")
 *         .onClick(view -> {
 *             view.replaceAll(Loot.parseAll(preset));
 *             Text.from(this, "{success}Preset loaded").send(view.viewer());
 *         })
 *         .build();
 * }</pre>
 *
 * <h2>Nothing here is persisted</h2>
 * The list is the editor's working copy, so a button that replaces forty rows is
 * as revertible as any other edit: the viewer still has to press save, and
 * cancel still throws the lot away. That is what makes a destructive button —
 * a preset, a bulk import — safe to offer at all.
 *
 * @param <T> what is being edited
 * @since 1.58.0
 */
public interface EditorView<T> {

    /**
     * Who clicked the button.
     *
     * @return the viewer
     */
    @NotNull Player viewer();

    /**
     * The list as it stands, including every unsaved change.
     *
     * @return an unmodifiable view; change it through {@link #replaceAll}
     */
    @NotNull List<T> entries();

    /**
     * Replaces the whole list.
     *
     * <p>The only mutator, because it is the only one a button has ever needed:
     * appending is {@code replaceAll} over the current list plus the new rows,
     * and it reads as what it is. The page is clamped afterwards, so a button
     * that shortens the list does not leave the viewer looking at a page that no
     * longer exists.
     *
     * @param entries what the list should hold now
     */
    void replaceAll(@NotNull List<T> entries);

    /**
     * Asks the viewer something, then brings the editor back.
     *
     * <pre>{@code
     * .onClick(view -> view.ask(() -> EditorForm.of(plugin, view.viewer(), "{primary}&lSETTINGS")
     *         .text(NAME, "Name", settings.name())
     *         .ask(values -> values.getText(NAME))
     *         .thenAccept(name -> name.ifPresent(settings::name))))
     * }</pre>
     *
     * <p>A button that opens a dialog, an anvil or a search cannot simply open
     * it: every one of those needs the screen, and the close would read as the
     * viewer walking away from the editor. This is the same door
     * {@link EditorDescriptor#edit} goes through — the window is taken down for
     * the question and put back, on the page it was on, when the answer arrives.
     *
     * <p>What the answer <em>means</em> is the caller's business: the stage is
     * waited on, never read. A button that changes the list does it in the
     * stage's own callback, through {@link #replaceAll}, and one that changes
     * something else — the gating around the list, a setting — writes it
     * wherever it keeps it.
     *
     * <p>A question that fails is logged and the editor still comes back: a
     * screen that never reopens is worse than an answer that was lost.
     *
     * @param question asked once, on the viewer's own thread; must not be
     *                 {@code null} and must not answer with {@code null}
     * @since 1.71.0
     */
    void ask(@NotNull Supplier<CompletionStage<?>> question);
}
