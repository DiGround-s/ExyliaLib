package net.exylia.lib.lobby;

import net.exylia.lib.text.Values;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * What a plugin does at each step of a table's life. The lobby seats players; the plugin decides who
 * may sit, holds what they put in, and plays.
 *
 * <pre>{@code
 * LobbyHooks<Match> hooks = new LobbyHooks<>() {
 *     public CompletableFuture<String> admit(Table<Match> table, Player player) {
 *         if (!games.isPlayable(table.subject().game())) return CompletableFuture.completedFuture(messages.disabled());
 *         return escrow.hold(table.id(), player.getUniqueId(), "", table.subject().stake())
 *                 .thenApply(hold -> hold == Escrow.Hold.HELD ? null : messages.cannotPay());
 *     }
 *     public void started(Table<Match> table) { seatAndCountDown(table); }
 *     public void dropped(Table<Match> table, Seat seat) { rounds.get(table).drop(table.seatOf(seat.player())); }
 *     public void ended(Table<Match> table, TableEnd end) { if (end != TableEnd.FINISHED) escrow.refund(table.id()); }
 *     public Values values(Table<Match> table) { return Values.of("game", table.subject().game().displayName()); }
 * };
 * }</pre>
 *
 * <p>Every hook runs on whichever thread caused the step: the command's, the expiry timer's (the
 * global thread), or the one that completed {@link #admit}'s future. A hook that touches a player
 * hops to that player's thread. Keep them short; a hook may call back into the lobby.
 *
 * @param <T> what the plugin plays at a table
 * @since 1.356.0
 */
public interface LobbyHooks<T> {

    /**
     * Whether a player may take a seat, asked once the seat is reserved for them: the host's on
     * {@link Lobby#create}, everyone else's on joining. Where a stake is held.
     *
     * <p>Complete with {@code null} to seat them, or with a line from the plugin's messages to refuse
     * (sent to them, the table's values filled in). Failing exceptionally refuses with
     * {@link LobbyMessages#failed}. Default: everyone is admitted.
     *
     * @return the refusal, or {@code null}
     */
    default @NotNull CompletableFuture<@Nullable String> admit(@NotNull Table<T> table, @NotNull Player player) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * A seat that was admitted will not play: its player left before the start, or the table ended
     * while their admission ran. Give back what {@link #admit} held for that seat alone. Not called
     * when the whole table ends, which is {@link #ended}.
     */
    default void released(@NotNull Table<T> table, @NotNull Seat seat) {
    }

    /** A player sat down and was admitted; the host's seat included, once the table opens. */
    default void joined(@NotNull Table<T> table, @NotNull Seat seat) {
    }

    /**
     * The table is full, or its host started it early, and every seat is admitted: seat the players and
     * call {@link Lobby#play} once the round begins, or {@link Lobby#cancel(Table)} when it cannot.
     */
    default void started(@NotNull Table<T> table) {
    }

    /**
     * A player left a table being played: their claim is released and their seat marked as left.
     * Play on without them, or end it with {@link Lobby#finish} or {@link Lobby#cancel(Table)}.
     */
    default void dropped(@NotNull Table<T> table, @NotNull Seat seat) {
    }

    /**
     * The table ended, once, whatever ended it. Every claim it held is already released. Anything but
     * {@link TableEnd#FINISHED} gives back what every seat was admitted with.
     */
    default void ended(@NotNull Table<T> table, @NotNull TableEnd end) {
    }

    /**
     * The plugin's own values for every message about a table ({@code %game%}, {@code %amount%}...).
     * The lobby adds its own on top: {@code %seated%}, {@code %seats%}, {@code %min%},
     * {@code %missing%}, {@code %command%}, and {@code %player%} where a message is about one.
     */
    default @NotNull Values values(@NotNull Table<T> table) {
        return Values.of();
    }
}
