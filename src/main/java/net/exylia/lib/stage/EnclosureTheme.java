package net.exylia.lib.stage;

import net.exylia.lib.config.Comment;

import java.util.List;

/**
 * How an {@link Enclosure} looks. A record a plugin embeds in its own config, so the server owner
 * restyles it there; the library reads no file for it.
 *
 * @param enabled     whether the walls are drawn; off still clears the blocks inside and hides
 *                    other players
 * @param block       what its walls, ceiling and floor are made of
 * @param floor       whether it has a floor; off shows the ground
 * @param reach       how far from the eyes its walls stand, in blocks (at least 2)
 * @param ceiling     how far above the eyes its ceiling is, in blocks (at least 2.2)
 * @param stars       how many faint stars are scattered on its walls; 0 for none
 * @param starColours their colours, as palette tokens or #rrggbb
 * @param starScale   their size
 * @since 1.356.0
 */
public record EnclosureTheme(
        @Comment("Draws the room. Off, the screen stands in the open world; the blocks round the player are")
        @Comment("still cleared and other players still hidden.")
        boolean enabled,
        @Comment("The block of its walls, ceiling and floor, and whether it has a floor (off shows the ground).")
        String block,
        boolean floor,
        @Comment("How far from the eyes the walls stand, and how far above the eyes the ceiling is, in blocks.")
        double reach,
        double ceiling,
        @Comment("Faint stars scattered on the walls and ceiling, twinkling slowly: how many (0 for none), their")
        @Comment("colours as palette tokens, and their size.")
        int stars,
        List<String> starColours,
        double starScale) {

    /** The library's room: dark, with a floor, 4.5 blocks to each wall and forty stars. */
    public EnclosureTheme() {
        this(true, "BLACK_CONCRETE", true, 4.5, 3.5, 40, List.of("{secondary}", "{secondary_light}", "{letters}"), 0.32);
    }

    public EnclosureTheme {
        starColours = starColours == null ? List.of() : List.copyOf(starColours);
    }

    /** The library's room; the same as {@code new EnclosureTheme()}. */
    public static EnclosureTheme defaults() {
        return new EnclosureTheme();
    }
}
