package net.exylia.lib.util.reward;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * What became of a whole list of rewards.
 *
 * <p>Carries every {@link RewardResult} rather than a count, so a caller can say
 * <em>which</em> reward failed and tell the player something useful.
 *
 * @param results one per reward, in the order they were given
 * @since 1.33.0
 */
public record RewardDelivery(@NotNull List<RewardResult> results) {

    /** Nothing was owed. */
    public static final RewardDelivery EMPTY = new RewardDelivery(List.of());

    /** Copies the results, so a caller cannot change what already happened. */
    public RewardDelivery {
        results = List.copyOf(results);
    }

    /** How many the player actually received. */
    public int given() {
        return (int) results.stream().filter(RewardResult::isGiven).count();
    }

    /** How many did not happen and should not have. */
    public int skipped() {
        return (int) results.stream().filter(result -> result.outcome().isSkipped()).count();
    }

    /** How many went wrong. */
    public int failed() {
        return (int) results.stream().filter(result -> result.outcome().isFailure()).count();
    }

    /** Whether the player received at least one. */
    public boolean isAnyGiven() {
        return results.stream().anyMatch(RewardResult::isGiven);
    }

    /** Whether every reward that was meant to land, landed. */
    public boolean isClean() {
        return failed() == 0;
    }

    /** Just the ones that went wrong, for a log line. */
    public @NotNull List<RewardResult> failures() {
        return results.stream().filter(result -> result.outcome().isFailure()).toList();
    }

    @Override
    public String toString() {
        return "RewardDelivery[" + given() + " given, " + skipped() + " skipped, "
                + failed() + " failed]";
    }
}
