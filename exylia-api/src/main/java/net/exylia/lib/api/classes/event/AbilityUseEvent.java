package net.exylia.lib.api.classes.event;

import net.exylia.lib.api.classes.ClassAbility;
import net.exylia.lib.api.classes.PlayerClass;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is using one of their class's abilities.
 *
 * <p>Fired once the plugin knows the use would otherwise go through: the
 * player is in the class, the ability is off cooldown and they hold the energy
 * it costs. Nothing has happened yet, so cancelling spends no energy, starts no
 * cooldown, applies no effect and consumes no item.
 *
 * <p>Fired alike for a right click on the ability's item and for
 * {@link net.exylia.lib.api.classes.ClassesService#useAbility}. Cancelling says
 * nothing: the handler that refused the ability is expected to tell the player
 * why.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class AbilityUseEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final PlayerClass playerClass;
    private final ClassAbility ability;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player      who is using the ability
     * @param playerClass the class it belongs to
     * @param ability     the ability being used
     */
    public AbilityUseEvent(@NotNull Player player, @NotNull PlayerClass playerClass,
                           @NotNull ClassAbility ability) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.playerClass = playerClass;
        this.ability = ability;
    }

    /**
     * The player using the ability.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The class the ability belongs to.
     *
     * @return the player's class
     */
    @NotNull
    public PlayerClass getPlayerClass() {
        return playerClass;
    }

    /**
     * The ability being used.
     *
     * @return the ability, with the cost and cooldown it is about to charge
     */
    @NotNull
    public ClassAbility getAbility() {
        return ability;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
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
