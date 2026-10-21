package net.exylia.lib.nametag;

import net.kyori.adventure.text.Component;
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
 * @param prefix       text drawn before the name, or {@code null} for none (since 1.330.0)
 * @param suffix       text drawn after the name, or {@code null} for none (since 1.330.0)
 * @since 1.36.0
 */
public record NametagStyle(
        @Nullable NamedTextColor colour,
        boolean seeInvisible,
        boolean glowing,
        boolean collides,
        @Nullable Component prefix,
        @Nullable Component suffix
) {

    /**
     * A style without prefix or suffix, the shape this record had before 1.330.0.
     *
     * @param colour       the name colour, or {@code null} to leave the name alone
     * @param seeInvisible whether an invisible player is still faintly drawn
     * @param glowing      whether the player is outlined through walls
     * @param collides     whether the player can be pushed
     */
    public NametagStyle(@Nullable NamedTextColor colour, boolean seeInvisible, boolean glowing, boolean collides) {
        this(colour, seeInvisible, glowing, collides, null, null);
    }

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
        return new NametagStyle(colour, true, glowing, collides, prefix, suffix);
    }

    /**
     * Returns this style with the player able to be pushed again.
     *
     * @return the new style
     */
    public @NotNull NametagStyle withCollision() {
        return new NametagStyle(colour, seeInvisible, glowing, true, prefix, suffix);
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
        return new NametagStyle(colour, seeInvisible, true, collides, prefix, suffix);
    }

    /**
     * Returns this style with text drawn before the name, such as a
     * {@code [COMBAT]} tag.
     *
     * <p>Like the colour, a prefix is carried by a scoreboard team, so it takes
     * the player out of whatever team a tab or nametag plugin put them in for
     * this viewer while it is painted.
     *
     * @param prefix the text, or {@code null} for none
     * @return the new style
     * @since 1.330.0
     */
    public @NotNull NametagStyle withPrefix(@Nullable Component prefix) {
        return new NametagStyle(colour, seeInvisible, glowing, collides, prefix, suffix);
    }

    /**
     * Returns this style with text drawn after the name.
     *
     * @param suffix the text, or {@code null} for none
     * @return the new style
     * @see #withPrefix(Component)
     * @since 1.330.0
     */
    public @NotNull NametagStyle withSuffix(@Nullable Component suffix) {
        return new NametagStyle(colour, seeInvisible, glowing, collides, prefix, suffix);
    }

    /**
     * Whether this style needs a scoreboard team: a colour, a prefix or a suffix.
     *
     * @return {@code true} when painting it sends a team
     * @since 1.330.0
     */
    public boolean needsTeam() {
        return colour != null || prefix != null || suffix != null;
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
        if (!needsTeam()) {
            // Nothing a team carries — colour, invisibility, collision — was
            // asked for, so sending one would only take the player out of the
            // team somebody else put them in.
            return null;
        }
        // Glow is deliberately absent: it rides on entity flags, not on the
        // team, so two styles that differ only by it can share one.
        String flags = (seeInvisible ? "i" : "_") + (collides ? "c" : "_");
        if (prefix == null && suffix == null) {
            return "exy_" + flags + '_' + Integer.toHexString(colour.value());
        }
        // Text cannot fit in a 16 character name, so a style that carries some
        // is named by a hash of everything the team draws.
        int hash = java.util.Objects.hash(colour, prefix, suffix);
        return "ext" + flags + Integer.toString(hash & 0x7fffffff, 36);
    }
}
