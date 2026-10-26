package net.exylia.lib.api.tabletop;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A game ExyliaTabletop offers.
 *
 * @param id          what commands, arenas and statistics know it by, such as {@code tictactoe}
 * @param displayName the name players read, palette tokens included
 * @param minPlayers  the seats a match starts with
 * @param maxPlayers  the seats a match is full at
 * @param playable    whether the server accepts matches of it right now
 * @param commands    its command and aliases, the first being the command
 * @since exylia-api 3.0.0
 */
public record TableGameInfo(@NotNull String id, @NotNull String displayName,
                            int minPlayers, int maxPlayers, boolean playable, @NotNull List<String> commands) {
}
