package net.exylia.lib.schematic;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What happened to one schematic operation.
 *
 * <pre>{@code
 * schematics.paste("arena_1", origin).thenAccept(result -> {
 *     if (result.isSuccess()) {
 *         startMatch();
 *     } else if (result.outcome() == SchematicOutcome.NOT_FOUND) {
 *         admin.sendMessage("Save the arena first.");
 *     }
 *     // FAILED and UNSUPPORTED are already on the console.
 * });
 * }</pre>
 *
 * <p>Nothing here completes exceptionally: a failure is a value, so a caller
 * that forgets an {@code exceptionally} cannot silently lose it.
 *
 * @param name    the schematic the operation was about
 * @param outcome how it ended
 * @param reason  why, for {@link SchematicOutcome#FAILED} and
 *                {@link SchematicOutcome#UNSUPPORTED}; {@code null} otherwise
 * @since 1.48.0
 */
public record SchematicResult(@NotNull String name, @NotNull SchematicOutcome outcome,
                              @Nullable String reason) {

    /** The operation completed. */
    public static @NotNull SchematicResult success(@NotNull String name) {
        return new SchematicResult(name, SchematicOutcome.SUCCESS, null);
    }

    /** There is no such schematic. */
    public static @NotNull SchematicResult notFound(@NotNull String name) {
        return new SchematicResult(name, SchematicOutcome.NOT_FOUND, null);
    }

    /** There is no engine to do it with. */
    public static @NotNull SchematicResult unsupported(@NotNull String name,
                                                       @NotNull String reason) {
        return new SchematicResult(name, SchematicOutcome.UNSUPPORTED, reason);
    }

    /** It was attempted and did not finish. */
    public static @NotNull SchematicResult failed(@NotNull String name,
                                                  @NotNull String reason) {
        return new SchematicResult(name, SchematicOutcome.FAILED, reason);
    }

    /**
     * Returns whether the whole operation completed.
     *
     * @return {@code true} only for {@link SchematicOutcome#SUCCESS}
     */
    public boolean isSuccess() {
        return outcome == SchematicOutcome.SUCCESS;
    }
}
