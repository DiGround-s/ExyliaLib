package net.exylia.lib.objectives;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which players wait for which kinds of objective, so a {@code wants}
 * predicate is one set lookup instead of a scan of every player's quests.
 *
 * <pre>{@code
 * ObjectiveIndex<ObjectiveType> waiting = new ObjectiveIndex<>();
 * waiting.set(player.getUniqueId(), activeMissionTypes(player));
 * Objectives.of(this).listen(ObjectiveType.FISH, p -> waiting.has(ObjectiveType.FISH, p.getUniqueId()), this::count);
 * }</pre>
 *
 * <p>Keyed by anything: a plugin may index its own step kinds, including ones
 * the library does not fire. Safe from any thread.
 *
 * @param <K> what players wait for
 * @since 1.620.0
 */
public final class ObjectiveIndex<K> {

    private final Map<K, Set<UUID>> byKey = new ConcurrentHashMap<>();

    /** Whether this player waits for this kind. */
    public boolean has(@NotNull K key, @NotNull UUID player) {
        Set<UUID> players = byKey.get(key);
        return players != null && players.contains(player);
    }

    /** Whether anybody waits for this kind. */
    public boolean any(@NotNull K key) {
        Set<UUID> players = byKey.get(key);
        return players != null && !players.isEmpty();
    }

    /** Replaces what a player waits for. */
    public void set(@NotNull UUID player, @NotNull Collection<? extends K> keys) {
        for (Map.Entry<K, Set<UUID>> entry : byKey.entrySet()) {
            if (!keys.contains(entry.getKey())) entry.getValue().remove(player);
        }
        for (K key : keys) byKey.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(player);
    }

    /** Forgets a player, on quit. */
    public void remove(@NotNull UUID player) {
        for (Set<UUID> players : byKey.values()) players.remove(player);
    }
}
