package net.exylia.lib.session;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * One plugin's way in and out of {@link Sessions}.
 *
 * <p>Taken once with {@link Sessions#of}. Everything here is scoped to the
 * plugin that took it, which is what lets a plugin ask about "my claim" rather
 * than "the claim" and makes re-entrancy safe: a plugin cannot lose a race
 * against itself.
 *
 * @since 1.50.0
 */
public final class PluginSessions {

    private final String plugin;

    PluginSessions(String plugin) {
        this.plugin = plugin;
    }

    /**
     * Takes this player, if nothing else has them.
     *
     * <p>Atomic: two plugins calling this in the same tick, or on two threads,
     * cannot both succeed. That is the whole point — the pattern this replaces
     * asked whether a player was free and then took them as a second step, and
     * everything that went wrong went wrong in between those two steps.
     *
     * <p>If this plugin already holds the player the same claim comes back with
     * its activity renamed and its <em>token unchanged</em>, so work already in
     * flight for that hold stays valid. A plugin moving a player from its queue
     * into its match is one continuous hold, not two.
     *
     * @param player   the player
     * @param kind     what they will be doing, in this plugin's own words
     * @param onRelease how to give the player back when somebody asks; runs on
     *                  whatever thread asked, and is expected to end with
     *                  {@link Claim#release()}
     * @return the claim, or empty if another plugin has the player
     */
    public @NotNull Optional<Claim> claim(@NotNull Player player, @NotNull String kind, @Nullable Runnable onRelease) {
        return claim(player.getUniqueId(), kind, onRelease);
    }

    /**
     * Takes this player, if nothing else has them.
     *
     * @param player    the player
     * @param kind      what they will be doing
     * @param onRelease how to give the player back
     * @return the claim, or empty if another plugin has the player
     */
    public @NotNull Optional<Claim> claim(@NotNull UUID player, @NotNull String kind, @Nullable Runnable onRelease) {
        Claim mine = Sessions.current(player);
        if (mine != null && mine.plugin().equals(plugin)) {
            return Optional.of(mine.as(kind));
        }
        return Optional.ofNullable(Sessions.open(player, plugin, kind, onRelease));
    }

    /**
     * Takes this player with no way to hand them back.
     *
     * <p>For a hold nothing else may interrupt. An eviction request against it
     * drops the claim outright rather than asking, so use it only where losing
     * the hold without ceremony is correct.
     *
     * @param player the player
     * @param kind   what they will be doing
     * @return the claim, or empty if another plugin has the player
     */
    public @NotNull Optional<Claim> claim(@NotNull Player player, @NotNull String kind) {
        return claim(player, kind, null);
    }

    /**
     * This plugin's claim on the player, if it has one.
     *
     * @param player the player
     * @return the claim, or empty if this plugin does not hold them
     */
    public @NotNull Optional<Claim> mine(@NotNull UUID player) {
        Claim claim = Sessions.current(player);
        return claim != null && claim.plugin().equals(plugin) ? Optional.of(claim) : Optional.empty();
    }

    /**
     * This plugin's claim on the player, if it has one.
     *
     * @param player the player
     * @return the claim, or empty if this plugin does not hold them
     */
    public @NotNull Optional<Claim> mine(@NotNull Player player) {
        return mine(player.getUniqueId());
    }

    /**
     * Whether this plugin holds the player.
     *
     * @param player the player
     * @return true if it does
     */
    public boolean holds(@NotNull UUID player) {
        return Sessions.isHeldBy(player, plugin);
    }

    /**
     * Whether this plugin holds the player for this activity.
     *
     * @param player the player
     * @param kind   the activity
     * @return true if it does
     */
    public boolean holds(@NotNull UUID player, @NotNull String kind) {
        Claim claim = Sessions.current(player);
        return claim != null && claim.plugin().equals(plugin) && claim.kind().equals(kind);
    }

    /**
     * Ends this plugin's claim on the player, if it has one.
     *
     * <p>Never touches another plugin's claim, so it is safe to call from a
     * cleanup path that does not know how the player got where they are.
     *
     * @param player the player
     * @return true if a claim of this plugin's was ended
     */
    public boolean release(@NotNull UUID player) {
        return mine(player).map(Claim::release).orElse(false);
    }

    /**
     * Ends this plugin's claim on the player, if it has one.
     *
     * @param player the player
     * @return true if a claim of this plugin's was ended
     */
    public boolean release(@NotNull Player player) {
        return release(player.getUniqueId());
    }
}
