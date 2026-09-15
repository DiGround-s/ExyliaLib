package net.exylia.lib.economy.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A balance that lives on the player, read only on the player's thread.
 *
 * <p>An inventory or an experience bar belongs to the thread that owns the
 * player, and a balance is asked from everywhere: a scoreboard's async timer,
 * a database callback, a placeholder. So a read from the owning thread counts
 * and remembers; a read from anywhere else answers the last count and asks the
 * owning thread for a fresh one, at most one pending per player.
 *
 * @since 1.163.0
 */
public final class PlayerThreadBalances {

    private final TaskScheduler tasks;
    private final Function<Player, BigDecimal> reader;
    private final Cache<UUID, BigDecimal> last = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();
    private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();

    public PlayerThreadBalances(@NotNull TaskScheduler tasks, @NotNull Function<Player, BigDecimal> reader) {
        this.tasks = tasks;
        this.reader = reader;
    }

    /** Counts now on the owning thread; elsewhere the last count, with a fresh one on its way. */
    public @NotNull BigDecimal balance(@NotNull UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online == null) {
            last.invalidate(player);
            return BigDecimal.ZERO;
        }
        if (tasks.isOwnedBy(online)) {
            return read(online);
        }
        refresh(online);
        return last(player);
    }

    /** The count, taken on the owning thread; zero for somebody who is not here. */
    public @NotNull CompletableFuture<BigDecimal> later(@NotNull UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return CompletableFuture.completedFuture(BigDecimal.ZERO);
        }
        if (tasks.isOwnedBy(online)) {
            return CompletableFuture.completedFuture(read(online));
        }
        CompletableFuture<BigDecimal> answer = new CompletableFuture<>();
        try {
            tasks.runAtEntity(online, () -> answer.complete(read(online)),
                    () -> answer.complete(BigDecimal.ZERO));
        } catch (RuntimeException stopped) {
            answer.complete(last(player));
        }
        return answer;
    }

    /** Counts and remembers. Only on the player's thread. */
    public @NotNull BigDecimal read(@NotNull Player online) {
        BigDecimal counted = reader.apply(online);
        last.put(online.getUniqueId(), counted);
        return counted;
    }

    /** The last count, zero when there is none. */
    public @NotNull BigDecimal last(@NotNull UUID player) {
        BigDecimal counted = last.getIfPresent(player);
        return counted == null ? BigDecimal.ZERO : counted;
    }

    private void refresh(Player online) {
        UUID id = online.getUniqueId();
        if (!refreshing.add(id)) {
            return;
        }
        try {
            tasks.runAtEntity(online, () -> {
                try {
                    read(online);
                } finally {
                    refreshing.remove(id);
                }
            }, () -> refreshing.remove(id));
        } catch (RuntimeException stopped) {
            refreshing.remove(id);
        }
    }

    /**
     * A whole number of units, or {@code -1} for an amount that is not a
     * positive one an {@code int} holds. A fraction is dropped: half an
     * emerald cannot be handed over.
     */
    public static int units(@NotNull BigDecimal amount) {
        if (amount.signum() <= 0 || amount.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return -1;
        }
        return amount.intValue();
    }
}
