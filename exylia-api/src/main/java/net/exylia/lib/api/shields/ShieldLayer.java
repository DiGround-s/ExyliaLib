package net.exylia.lib.api.shields;

import org.jetbrains.annotations.NotNull;

/**
 * One layer of a shield design: a vanilla banner pattern drawn in a dye colour.
 *
 * <p>Both halves are the ids the server's own configuration uses, not Bukkit
 * enums. That is deliberate: a layer is stored as text, permissions are built
 * from these ids ({@code exyliashields.pattern.<pattern>} and
 * {@code exyliashields.color.<color>}), and a pattern the running server does
 * not know is still a line of somebody's design rather than an error. Layers
 * naming something the server cannot draw are dropped when the shield is drawn,
 * not when it is read.
 *
 * @param pattern the vanilla pattern key, such as {@code stripe_top}
 * @param color   the dye colour name, such as {@code RED}
 * @since 1.0.0
 */
public record ShieldLayer(@NotNull String pattern, @NotNull String color) {
}
