package net.exylia.lib.scoreboard.internal;

import net.exylia.lib.scoreboard.Board;
import net.exylia.lib.scoreboard.SidebarConfig;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * A board that shows nothing and does nothing.
 *
 * <p>Returned when a config disables the board or there is no packet adapter,
 * so callers never have to null-check before chaining.
 */
final class NoopBoard implements Board {

    private final Player player;
    private final SidebarConfig config;

    NoopBoard(Player player, SidebarConfig config) {
        this.player = player;
        this.config = config;
    }

    @Override
    public @NotNull Player player() {
        return player;
    }

    @Override
    public @NotNull SidebarConfig config() {
        return config;
    }

    @Override
    public void refresh() {
    }

    @Override
    public void updateData(@NotNull Map<String, Object> data) {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean stopped() {
        return true;
    }
}
