package net.exylia.lib.api.betcore;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A game ExyliaBetCore offers.
 *
 * @param id          what commands, arenas and statistics know it by, such as {@code tictactoe}
 * @param displayName the name players read, palette tokens included
 * @param category    which kind of game it is
 * @param minPlayers  the seats a match starts with
 * @param maxPlayers  the seats a match is full at
 * @param playable    whether the server takes bets on it right now
 * @param commands    its command and aliases, the first being the command
 * @since exylia-api 2.0.0
 */
public record BetGameInfo(@NotNull String id, @NotNull String displayName, @NotNull BetCategory category,
                          int minPlayers, int maxPlayers, boolean playable, @NotNull List<String> commands) {
}
