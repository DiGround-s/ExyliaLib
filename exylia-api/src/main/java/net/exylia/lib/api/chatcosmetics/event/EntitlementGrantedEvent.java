package net.exylia.lib.api.chatcosmetics.event;

import net.exylia.lib.api.chatcosmetics.Entitlement;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A cosmetic was granted to a player.
 *
 * <p>Fired once the row is written and the player's grants have been read back, so the plugin already answers yes to owning it by the time you hear.
 *
 * <p>Fired on the player's thread when they are online, on the global thread
 * otherwise — a grant for somebody absent is still worth hearing about.
 *
 * @since 1.0.0
 */
public final class EntitlementGrantedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Entitlement entitlement;

    /**
     * @param entitlement the grant this is about
     */
    public EntitlementGrantedEvent(@NotNull Entitlement entitlement) {
        this.entitlement = entitlement;
    }

    /**
     * The grant this is about.
     *
     * @return the entitlement
     */
    public @NotNull Entitlement entitlement() {
        return entitlement;
    }

    /**
     * Who holds it.
     *
     * @return the player's uuid
     */
    public @NotNull UUID player() {
        return entitlement.player();
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * @return the handler list Bukkit registers against
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
