package net.exylia.lib.util.editor;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
}
