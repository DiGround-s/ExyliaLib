package net.exylia.lib.network;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Who of the players a server waited for is here.
 *
 * @param arrived the ones online on this server
 * @param missing the ones that are not
 * @since 1.347.0
 */
public record Arrival(@NotNull Set<UUID> arrived, @NotNull Set<UUID> missing) {

    public Arrival {
        arrived = Set.copyOf(arrived);
        missing = Set.copyOf(missing);
    }

    /** Splits the expected players by whether they are online. */
    static @NotNull Arrival of(@NotNull Collection<UUID> expected, @NotNull Predicate<UUID> online) {
        Set<UUID> arrived = new LinkedHashSet<>();
        Set<UUID> missing = new LinkedHashSet<>();
        for (UUID id : expected) {
            (online.test(id) ? arrived : missing).add(id);
        }
        return new Arrival(arrived, missing);
    }

    /** Whether every expected player is here. */
    public boolean complete() {
        return missing.isEmpty();
    }
}
