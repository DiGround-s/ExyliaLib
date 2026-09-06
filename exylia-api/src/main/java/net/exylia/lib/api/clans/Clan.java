package net.exylia.lib.api.clans;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A clan, as it was when you asked.
 *
 * <p>A snapshot, not a live view: the plugin replaces its clan objects on every
 * change, so the values here are the ones that were current at the moment of the
 * lookup. Ask again rather than holding one across ticks.
 *
 * <p>Only the stored fields are here. Anything the plugin computes — level,
 * member count, maximum DTR, whether the clan is raidable right now — is a
 * method on {@link ClansService}, so reading a clan stays a cache hit rather
 * than a pile of work you did not ask for.
 *
 * @param id           the clan id, stable for the clan's whole life
 * @param name         the name players type and menus show
 * @param leader       the player who owns the clan
 * @param balance      the clan bank
 * @param open         whether anybody may join without an invite
 * @param friendlyFire whether clanmates can hurt each other
 * @param dtr          deaths till raidable, the current value
 * @param exp          experience earned towards the next level
 * @since 1.0.0
 */
public record Clan(
        @NotNull String id,
        @NotNull String name,
        @NotNull UUID leader,
        double balance,
        boolean open,
        boolean friendlyFire,
        double dtr,
        long exp) {
}
