package net.exylia.lib.api.clans.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A clan's deaths-till-raidable has moved.
 *
 * <p>Fired once the new value is stored, from the one place every change goes
 * through: a member dying, regeneration, an administrator setting it, and
 * {@link net.exylia.lib.api.clans.ClansService#adjustDtr}. Going raidable and
 * recovering from it are the same event, told apart by
 * {@link #becameRaidable()} and {@link #recovered()} — whether a clan counts as
 * raidable is the plugin's rule, and it is decided before the event is built.
 *
 * <p><strong>Frequently asynchronous.</strong> Regeneration runs off the main
 * thread, so most of these events are fired from a background thread and
 * report {@link #isAsynchronous()} as {@code true}. Read the values, and
 * schedule anything that touches the world.
 *
 * @since 1.3.0
 */
public class ClanDtrChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String clanId;
    private final double previous;
    private final double dtr;
    private final boolean wasRaidable;
    private final boolean raidable;

    /**
     * Creates the event.
     *
     * @param clanId      the clan
     * @param previous    the DTR before the change
     * @param dtr         the DTR after it
     * @param wasRaidable whether the clan was raidable before
     * @param raidable    whether it is raidable now
     */
    public ClanDtrChangeEvent(@NotNull String clanId, double previous, double dtr,
                              boolean wasRaidable, boolean raidable) {
        super(!Bukkit.isPrimaryThread());
        this.clanId = clanId;
        this.previous = previous;
        this.dtr = dtr;
        this.wasRaidable = wasRaidable;
        this.raidable = raidable;
    }

    /**
     * The clan whose DTR moved.
     *
     * @return the clan id
     */
    @NotNull
    public String getClanId() {
        return clanId;
    }

    /**
     * The DTR before the change.
     *
     * @return the previous value
     */
    public double getPrevious() {
        return previous;
    }

    /**
     * The DTR after the change.
     *
     * @return the current value
     */
    public double getDtr() {
        return dtr;
    }

    /**
     * Whether the clan's land can be raided now.
     *
     * @return {@code true} when it is raidable after the change
     */
    public boolean isRaidable() {
        return raidable;
    }

    /**
     * Whether this change is the one that opened the clan to raiding.
     *
     * @return {@code true} when the clan was not raidable and now is
     */
    public boolean becameRaidable() {
        return !wasRaidable && raidable;
    }

    /**
     * Whether this change is the one that closed the clan to raiding.
     *
     * @return {@code true} when the clan was raidable and no longer is
     */
    public boolean recovered() {
        return wasRaidable && !raidable;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * The handler list Bukkit requires.
     *
     * @return the handler list
     */
    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
