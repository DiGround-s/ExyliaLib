package net.exylia.lib.panel.internal;

import org.jetbrains.annotations.ApiStatus;
import net.exylia.lib.input.InputResult;
import net.exylia.lib.input.Inputs;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * The only way a panel asks a player anything.
 *
 * <p>The engine never calls {@link Inputs} directly. Every prompt goes through
 * here, so a test scripts the answers instead of standing up a transport — a
 * question is otherwise answered by a chat listener, an anvil or a native
 * dialog, none of which exist without a server.
 *
 * <p>That indirection is also what keeps the input module a detail: what a
 * panel needs is "ask for text and tell me what came back", and the three
 * shapes below are the whole of it.
 */
@ApiStatus.Internal
public final class PanelPrompts {

    /** The real thing: asks through the input module. */
    private static final Prompts LIVE = new Prompts() {

        @Override
        public @NotNull CompletionStage<InputResult<String>> text(@NotNull Plugin plugin,
                                                                  @NotNull Player viewer,
                                                                  @NotNull String prompt) {
            return Inputs.of(plugin).text(viewer, prompt).open();
        }

        @Override
        public @NotNull CompletionStage<InputResult<Boolean>> confirm(@NotNull Plugin plugin,
                                                                      @NotNull Player viewer,
                                                                      @NotNull String prompt,
                                                                      boolean dangerous) {
            var request = Inputs.of(plugin).confirm(viewer, prompt);
            if (dangerous) {
                // Typing the word rather than clicking yes. Reserved for the
                // operations a misclick cannot be taken back from.
                request.dangerous();
            }
            return request.open();
        }

        @Override
        public <T> @NotNull CompletionStage<InputResult<T>> search(@NotNull Plugin plugin,
                                                                   @NotNull Player viewer,
                                                                   @NotNull String prompt,
                                                                   @NotNull List<T> choices,
                                                                   @NotNull Function<T, String> label) {
            return Inputs.of(plugin).search(viewer, prompt, choices).label(label::apply).open();
        }
    };

    private static volatile Prompts prompts = LIVE;

    private PanelPrompts() {
        throw new AssertionError("No instances.");
    }

    /**
     * The three questions a panel ever asks.
     *
     * <p>Each returns a stage rather than a value because a question is
     * answered later, by a player, on a thread nobody chose. Whoever resumes
     * from one of these and then touches the game must get back to the viewer's
     * thread first.
     */
    public interface Prompts {

        /** Asks for free text — a name, a message, a number typed as text. */
        @NotNull CompletionStage<InputResult<String>> text(@NotNull Plugin plugin,
                                                           @NotNull Player viewer,
                                                           @NotNull String prompt);

        /**
         * Asks yes or no.
         *
         * @param dangerous whether the answer must be typed rather than clicked
         */
        @NotNull CompletionStage<InputResult<Boolean>> confirm(@NotNull Plugin plugin,
                                                               @NotNull Player viewer,
                                                               @NotNull String prompt,
                                                               boolean dangerous);

        /** Asks which one, over a list that may not fit on a screen. */
        <T> @NotNull CompletionStage<InputResult<T>> search(@NotNull Plugin plugin,
                                                            @NotNull Player viewer,
                                                            @NotNull String prompt,
                                                            @NotNull List<T> choices,
                                                            @NotNull Function<T, String> label);
    }

    /** What the engine asks through. */
    public static @NotNull Prompts get() {
        return prompts;
    }

    /**
     * Test seam: scripts the answers.
     *
     * <p>Precedent: {@code Engines.install}. A fake here replaces every
     * transport at once, so what the panel <em>does</em> with an answer — commit
     * the edit, push undo, reject and leave the working copy alone — is tested
     * with no server and no input module.
     *
     * @param replacement the prompts to use, or {@code null} to restore the
     *                    real input module
     */
    public static void install(@Nullable Prompts replacement) {
        prompts = replacement == null ? LIVE : replacement;
    }
}
