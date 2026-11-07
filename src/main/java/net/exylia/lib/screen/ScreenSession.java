package net.exylia.lib.screen;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One open {@link Screen}: its viewer and its state.
 *
 * <pre>{@code
 * Node.text("{letters}Duels").onClick(event -> event.session().set("tab", "duels"));
 * ...
 * String tab = session.state("tab", "queue");   // inside the render function
 * }</pre>
 *
 * <p>State is what the screen itself remembers (the open tab, a page, a picked row); it lives as long as the
 * session and is gone when it closes. Every method is safe from any thread.
 *
 * @since 1.483.0
 */
public interface ScreenSession {

    /** Who is looking at it. */
    @NotNull Player viewer();

    /** The screen being shown. */
    @NotNull Screen screen();

    /** A value this session remembers, or {@code fallback} when none is set. */
    <T> @NotNull T state(@NotNull String key, @NotNull T fallback);

    /** Remembers a value ({@code null} forgets it) and redraws. */
    void set(@NotNull String key, @Nullable Object value);

    /** Draws again now rather than at the next second. */
    void redraw();

    /** Whether it is still open. */
    boolean open();

    /** Closes it; its {@link Screen#onClose} runs. */
    void close();

    /**
     * Shows a short line at the top of the screen for three seconds, above everything, without taking the
     * pointer: feedback for something that just happened ("Joined the queue"). Written with {@code Text}.
     *
     * @since 1.488.0
     */
    default void toast(@NotNull String text) {
    }

    /**
     * Steps the screen aside while an input runs, so a chat prompt or a dialog is not drawn behind it, and brings
     * it back, with its state and page, once the input ends however it ends. Set values in {@code thenAccept} as
     * usual: they show when the screen comes back.
     *
     * <pre>{@code session.aside(() -> Inputs.of(plugin).text(player, "Search").open())
     *         .thenAccept(result -> result.ifCompleted(query -> session.set("query", query)));}</pre>
     *
     * @since 1.490.0
     */
    default <T> java.util.concurrent.@NotNull CompletionStage<T> aside(
            java.util.function.@NotNull Supplier<? extends java.util.concurrent.CompletionStage<T>> input) {
        return input.get();
    }

    /**
     * A toast in a tone: {@code success}, {@code error}, {@code info} or {@code warning} colour its edge and play a
     * quiet sound for success and error. Up to three show at once, newest at the bottom.
     *
     * @since 1.496.0
     */
    default void toast(@NotNull String tone, @NotNull String text) {
        toast(text);
    }

    /**
     * Asks a yes-or-no question in a dialog drawn on the screen itself (no window, no chat): {@code yes} runs on the
     * viewer's thread when they confirm; Cancel, a click outside or Shift does nothing.
     *
     * @param question  what is asked, written with {@code Text}
     * @param dangerous whether confirming destroys something: the button is drawn in the error tone
     * @param yesLabel  the confirm button's words, {@code null} for "Confirm"
     * @param noLabel   the cancel button's words, {@code null} for "Cancel"
     * @since 1.496.0
     */
    default void confirm(@NotNull String question, boolean dangerous, @Nullable String yesLabel,
                         @Nullable String noLabel, java.util.function.@NotNull Consumer<ScreenSession> yes) {
    }

    /**
     * Asks for a line of text through the library's inputs (a dialog from 1.21.6, a chat prompt before), the
     * screen stepped aside meanwhile. Completes with the answer, or {@code null} when they cancel.
     *
     * @param hint    a line under the prompt, or {@code null}
     * @param max     the longest answer taken
     * @param current what the field starts with
     * @since 1.496.0
     */
    default java.util.concurrent.@NotNull CompletionStage<String> ask(@NotNull String prompt, @Nullable String hint,
                                                                       int max, @NotNull String current) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /**
     * Steps back as Shift does: closes the open in-screen confirm or dialog, then goes back to the menu this one
     * was opened from (a chest menu shown as a screen). Does not close the screen.
     *
     * @return whether it went back somewhere; {@code false} at the root, where Shift closes
     * @since 1.496.0
     */
    default boolean back() {
        return false;
    }
}
