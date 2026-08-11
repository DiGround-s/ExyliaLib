package net.exylia.lib.text;

import net.exylia.lib.config.Comment;

/**
 * The named colours every Exylia plugin writes against.
 *
 * <p>Messages refer to a role, not to a hex value: {@code {primary}} rather than
 * {@code <#8a51c4>}. A server owner changes the palette once and every plugin
 * follows, and a plugin author never has to guess which purple to use.
 *
 * <p>The defaults are the Exylia identity. They are generated into
 * {@code ExyliaLib/colors.yml} on first start and can be edited there.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Text.of("{primary}Welcome {highlight}back{primary}!").send(player);
 * }</pre>
 *
 * <p>Every component is a hex string such as {@code #8a51c4}. Values that are
 * not valid hex are reported and the built-in default is used instead, so a typo
 * cannot leave a server without colours.
 *
 * @since 1.2.0
 */
@Comment("Colour palette shared by every Exylia plugin.")
@Comment("Messages use names like {primary}, so changing a value here")
@Comment("recolours every plugin at once. Values are hex, for example #8a51c4.")
public record Palette(
        @Comment("Main brand colour. Titles, item names, key words.")
        String primary,

        @Comment("Supporting colour. Section labels, secondary emphasis.")
        String secondary,

        @Comment("Lighter secondary, for softer emphasis.")
        String secondaryLight,

        @Comment("Body text.")
        String letters,

        @Comment("Muted body text, used for lore guides and separators.")
        String lettersBlack,

        @Comment("Something went wrong.")
        String error,

        @Comment("Something worked.")
        String success,

        @Comment("Lighter success, for supporting lines.")
        String successLight,

        @Comment("Caution, or an action that needs attention.")
        String warning,

        @Comment("Lighter warning, for supporting lines.")
        String warningLight,

        @Comment("Neutral information.")
        String info,

        @Comment("Lighter info, for supporting lines.")
        String infoLight,

        @Comment("Accent for rare highlights.")
        String accent,

        @Comment("Disabled or unimportant text.")
        String neutral,

        @Comment("Important values inside a sentence.")
        String highlight,

        @Comment("Very low emphasis text.")
        String muted
) {

    /**
     * The Exylia palette.
     *
     * <p>These values are the identity, and are what a fresh
     * {@code colors.yml} contains.
     */
    public Palette() {
        this(
                "#8a51c4",  // primary
                "#aa76de",  // secondary
                "#b48fd9",  // secondary_light
                "#e7cfff",  // letters
                "#a89ab5",  // letters_black
                "#a33b53",  // error
                "#8fffc1",  // success
                "#a1ffc3",  // success_light
                "#ff9500",  // warning
                "#ffd2a8",  // warning_light
                "#59a4ff",  // info
                "#7db7ff",  // info_light
                "#ff6b9d",  // accent
                "#6c757d",  // neutral
                "#ffd700",  // highlight
                "#868e96"   // muted
        );
    }
}
