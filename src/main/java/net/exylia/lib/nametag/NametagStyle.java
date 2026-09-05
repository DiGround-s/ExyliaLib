package net.exylia.lib.nametag;

import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * How a player looks to somebody else.
 *
 * <p>Four things a vanilla client will draw differently, declared together
 * because they travel together: the colour of the name above their head,
 * whether they can be seen while invisible, whether they glow, and whether
 * they can be walked through.
 *
 * <pre>{@code
 * NametagStyle friendly = NametagStyle.of(NamedTextColor.GREEN)
 *         .showingInvisible()
 *         .withGlow();
 *
 * NametagStyle enemy = NametagStyle.of(NamedTextColor.RED);
 * }</pre>
 *
 * <h2>Why a style and not a team name</h2>
 * Minecraft draws this through scoreboard teams, but a team name is an
 * implementation detail that every caller ended up inventing for itself
 * ({@code "clan_" + id}, {@code "allies_" + id}, …) and then had to keep in
 * step with the colours. Two viewers who paint a player the same way share a
 * team without either of them knowing there is one.
 *
 * @param colour       the name colour, or {@code null} to leave the name alone
 * @param seeInvisible whether an invisible player is still faintly drawn
 * @param glowing      whether the player is outlined through walls
 * @param collides     whether the player can be pushed
 * @since 1.36.0
 */
public record NametagStyle(
        @Nullable NamedTextColor colour,
        boolean seeInvisible,
        boolean glowing,
        boolean collides
) {

    /**
     * A style with just a colour: visible when invisible is off, no glow, and
     * no collision.
     *
     * <p>Collision is off by default because the only reason to put a player in
     * a team from a plugin is to change how they look, and a team that collides
     * is the server's default anyway — turning it off is what a game usually
     * wants and what the previous implementation always did.
     *
     * @param colour the name colour
     * @return the style
     */
    public static @NotNull NametagStyle of(@NotNull NamedTextColor colour) {
        return new NametagStyle(colour, false, false, false);
    }

    /**
     * A glow and nothing else: the player is outlined through walls and their
     * name is left exactly as it was.
     *
     * <p>A colour is drawn through a scoreboard team, and a team claims the
     * player away from whatever team a tab or nametag plugin had them in. This
     * style sends no team at all — the outline rides on entity flags — so it
     * costs a server whose names belong to another plugin nothing.
     *
     * <p>The outline is white, since it takes the team's colour and there is no
     * team.
     *
     * @return the style
     * @since 1.44.0
     */
    public static @NotNull NametagStyle glowOnly() {
        return new NametagStyle(null, false, true, false);
    }

    /**
     * Returns this style, drawing the player faintly while they are invisible.
     *
     * @return the new style
     */
    public @NotNull NametagStyle showingInvisible() {
        return new NametagStyle(colour, true, glowing, collides);
    }

    /**
     * Returns this style with the player able to be pushed again.
     *
     * @return the new style
     */
    public @NotNull NametagStyle withCollision() {
        return new NametagStyle(colour, seeInvisible, glowing, true);
    }

    /**
     * Returns this style with the player outlined through walls.
     *
     * <p>Sent separately from the rest: teams carry the colour of a glow but
     * not the glow itself, so this rewrites the player's entity flags on the
     * way to the viewer. The outline takes the team's colour.
     *
     * @return the new style
     */
    public @NotNull NametagStyle withGlow() {
        return new NametagStyle(colour, seeInvisible, true, collides);
    }

    /**
     * Returns the team name that carries this style.
     *
     * <p>Derived from the style rather than chosen, so two callers who paint
     * the same way land in the same team instead of sending the client two
     * teams that mean the same thing. Prefixed to stay out of the way of teams
     * the server or another plugin owns.
     *
     * <p>Public because it is the one implementation detail a caller can
     * observe: a plugin that also sends its own team packets needs to know
     * which names are taken.
     *
     * @return the team name, at most 16 characters, or {@code null} when the
     *         style has no colour and so needs no team
     */
    public @Nullable String teamName() {
        if (colour == null) {
            // Nothing a team carries — colour, invisibility, collision — was
            // asked for, so sending one would only take the player out of the
            // team somebody else put them in.
            return null;
        }
        // Glow is deliberately absent: it rides on entity flags, not on the
        // team, so two styles that differ only by it can share one.
        return "exy_"
                + (seeInvisible ? 'i' : '_')
                + (collides ? 'c' : '_')
                + '_' + Integer.toHexString(colour.value());
    }
}
