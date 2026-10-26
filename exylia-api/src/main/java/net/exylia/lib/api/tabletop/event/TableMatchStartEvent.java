package net.exylia.lib.api.tabletop.event;

import net.exylia.lib.api.tabletop.TableMatch;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Every player of a match is in the arena and the first turn is being played.
 *
 * @since exylia-api 3.0.0
 */
public class TableMatchStartEvent extends TableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final TableMatch match;

    public TableMatchStartEvent(@NotNull TableMatch match) {
        this.match = match;
    }

    public @NotNull TableMatch getMatch() {
        return match;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
