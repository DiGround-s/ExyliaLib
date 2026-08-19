package net.exylia.lib.util.reward;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What became of one reward.
 *
 * <p>Carries the reward it is about, so a caller holding a list of results can
 * say which one failed without matching them up by position.
 *
 * @param reward  the reward this is about
 * @param outcome how it ended
 * @param detail  what went wrong, for {@link RewardOutcome#FAILED}, else {@code null}
 * @param failure the exception, if one was thrown
 * @since 1.33.0
 */
public record RewardResult(@NotNull RewardEntry reward,
                           @NotNull RewardOutcome outcome,
                           @Nullable String detail,
                           @Nullable Throwable failure) {

    /** The player received it. */
    public static @NotNull RewardResult given(@NotNull RewardEntry reward) {
        return new RewardResult(reward, RewardOutcome.GIVEN, null, null);
    }

    /**
     * The reward did not land, for a reason the outcome names.
     *
     * <p>Covers both a reward that never happened and one that could not be
     * delivered; {@link RewardOutcome#isSkipped()} tells those apart.
     *
     * @param reward  the reward
     * @param outcome why it did not land
     * @return the result
     */
    public static @NotNull RewardResult skipped(@NotNull RewardEntry reward,
                                                @NotNull RewardOutcome outcome) {
        return new RewardResult(reward, outcome, null, null);
    }

    /** Something is wrong. */
    public static @NotNull RewardResult failed(@NotNull RewardEntry reward,
                                               @NotNull String detail) {
        return new RewardResult(reward, RewardOutcome.FAILED, detail, null);
    }

    /** Something threw. */
    public static @NotNull RewardResult failed(@NotNull RewardEntry reward,
                                               @NotNull String detail,
                                               @NotNull Throwable failure) {
        return new RewardResult(reward, RewardOutcome.FAILED, detail, failure);
    }

    /** Whether the player actually received it. */
    public boolean isGiven() {
        return outcome.isGiven();
    }

    @Override
    public String toString() {
        return "RewardResult[" + outcome + " " + reward.preview()
                + (detail != null ? ": " + detail : "") + "]";
    }
}
