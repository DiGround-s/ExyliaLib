package net.exylia.lib.session;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One plugin's answer to "is this a good moment to take this player away?"
 *
 * <p>A {@linkplain Claim claim} answers whether a player is <em>already</em>
 * somewhere. This answers the softer question next to it: the player is free,
 * but something about where they are standing means a mode should not start.
 * A player in a party that has not queued yet is the case this was written
 * for — nothing holds them, so {@link Sessions#isFree(UUID)} is true, and yet
 * putting them on staff duty strands the rest of the party waiting for
 * somebody who has gone off to moderate.
 *
 * <p>Deliberately not enforced by {@link PluginSessions#claim}. A rule is
 * advice offered to whoever asks for it, not a veto over every claim on the
 * server: a plugin that would rather take the player anyway — a punishment, an
 * event that starts on a timer — simply does not ask. What a rule must never
 * do is decide on its own behalf whether some other plugin's feature works.
 *
 * <p>One rule per plugin, dropped when that plugin is disabled. A rule is
 * never asked about a claim its own plugin is taking.
 *
 * @since 1.93.0
 */
@FunctionalInterface
public interface ReadinessRule {

    /**
     * Why this player should be left where they are, if they should.
     *
     * <p>Called on whatever thread asked, usually the server thread, and read
     * straight into a message: keep it short, lowercase and in the player's
     * own terms — {@code "in a party"}, {@code "waiting for a duel"} — not the
     * name of an internal state.
     *
     * @param player the player somebody wants to take
     * @param kind   what they would be doing, in the asking plugin's words
     * @return the reason not to, or {@code null} when this plugin has none
     */
    @Nullable String notReady(@NotNull UUID player, @NotNull String kind);
}
