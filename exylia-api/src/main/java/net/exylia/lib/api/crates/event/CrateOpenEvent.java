package net.exylia.lib.api.crates.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A player opened a crate and has been paid.
 *
 * <p>Fired once per opening, after the keys are spent and the rewards handed
 * over, before the animation shows them. A bulk opening is one event whose
 * {@link #getKeys()} is the number of keys it spent. Not cancellable: what it
 * reports already happened.
 *
 * <p>Called on the player's own thread, which on Folia is their region thread.
 *
 * @since 1.10.0
 */
public class CrateOpenEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String crateId;
    private final int keys;
    private final List<String> rewards;

    /**
     * Creates the event.
     *
     * @param player  who opened the crate
     * @param crateId the crate
     * @param keys    how many keys the opening spent
     * @param rewards the display names of the rewards won, rarest first
     */
    public CrateOpenEvent(@NotNull Player player, @NotNull String crateId, int keys,
                          @NotNull List<String> rewards) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.crateId = crateId;
        this.keys = keys;
        this.rewards = List.copyOf(rewards);
    }

    /**
     * The player who opened the crate.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The crate opened.
     *
     * @return the id the crate was created with
     */
    @NotNull
    public String getCrateId() {
        return crateId;
    }

    /**
     * How many keys the opening spent: one, or more for a bulk opening.
     *
     * @return the keys spent, at least {@code 1}
     */
    public int getKeys() {
        return keys;
    }

    /**
     * The display names of the rewards won, rarest first, palette tokens
     * included as the crate shows them.
     *
     * @return an unmodifiable list, one entry per reward won
     */
    @NotNull
    public List<String> getRewards() {
        return rewards;
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
