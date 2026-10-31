package net.exylia.lib.spatial;

import net.exylia.lib.config.Comment;

import java.util.List;

/**
 * How a spatial menu looks without a resource pack: dark tinted glass, a thin accent along each
 * panel's top, plinths with lit plates, vanilla type in the palette, and the library's menu sounds.
 * Nested in every layout file as {@code theme}; the no-argument default is the library's look.
 *
 * <p>Colours are palette tokens ({@code {primary}}) or {@code #rrggbb}, read when a piece is drawn, so
 * a palette reload restyles the next menu opened. Alphas are 0-255. Sounds are {@code NAME|volume|pitch},
 * blank for none.
 *
 * @since 1.406.0
 */
public record SpatialTheme(
        @Comment("The panels' glass: a dark sheet tinted towards this colour, by this much (0-1), this opaque (0-255).")
        String glassTint,
        double glassTintStrength,
        int glassAlpha,
        @Comment("The darker sheet behind each panel, this much larger on every side, in blocks, and this opaque.")
        double backingReach,
        int backingAlpha,
        @Comment("The thin bar along each panel's top edge: its colour and height in blocks.")
        String accent,
        double accentHeight,
        @Comment("Text sizes: panel titles, body lines, and small lines (subtitles, hints). Never drawn below min-glyph-degrees.")
        double titleScale,
        double textScale,
        double smallScale,
        @Comment("The block the carousel's and the preview's plinths are made of.")
        String pedestalBlock,
        @Comment("The plate on a plinth at rest (darkened like the glass); the ring round a plinth under the crosshair,")
        @Comment("and round the chosen one (also its label's tint and the SAVE button while there is something to save).")
        String plateIdle,
        String plateHover,
        String plateSelected,
        @Comment("The outline of an item under the crosshair, and how much it grows.")
        String hoverGlow,
        double hoverGrow,
        @Comment("The outline of the piece an admin is moving in edit mode.")
        String editOutline,
        @Comment("Lines shown for an entry's state. %price% is the entry's price. A grid cell gets a marker")
        @Comment("in its icon's corner instead: equipped-marker when worn, locked-marker when not theirs.")
        String lockedLine,
        String ownedLine,
        String equippedLine,
        String equippedMarker,
        String lockedMarker,
        @Comment("The carousel's page line, its arrows, and what an empty list says.")
        String pageLine,
        String previousArrow,
        String nextArrow,
        String emptyLine,
        @Comment("The hologram over a placed spot. %title% is the menu's title.")
        List<String> spotLines,
        @Comment("The leave item in the last hotbar slot.")
        String leaveMaterial,
        String leaveName,
        List<String> leaveLore,
        @Comment("Sounds: the crosshair landing on something, choosing, paging, opening, closing, and a refusal.")
        String hoverSound,
        String selectSound,
        String pageSound,
        String openSound,
        String closeSound,
        String errorSound,
        @Comment("The smallest a letter may look from the viewer's eyes, in degrees: every line is drawn at least")
        @Comment("this tall wherever it stands (a vanilla capital is 7 px, a small capital 5 px), and a line too")
        @Comment("long for its room at that size is cut with an ellipsis rather than shrunk.")
        double minGlyphDegrees,
        @Comment("Whether body lines (descriptions, hints, subtitles, the page line) follow the server's small-text")
        @Comment("setting like titles and buttons do. Small capitals are hard to read at body sizes.")
        boolean smallCapsBody) {

    /** The library's look. */
    public SpatialTheme() {
        this("{primary}", 0.16, 208, 0.035, 236, "{primary}", 0.022,
                0.6, 0.42, 0.35,
                "POLISHED_BLACKSTONE", "{primary}", "{secondary}", "{highlight}",
                "{highlight}", 1.12, "{info}",
                "{error}✘ Locked", "{muted}Owned", "{success}✔ Equipped", "{success}✔", "{error}🔒",
                "{muted}PAGE {letters}%page% {letters_black}/ {letters}%pages%",
                "{secondary_light}&l←", "{secondary_light}&l→", "{muted}Nothing here yet",
                List.of("%title%", "{warning}➥ Click to open"),
                "BARRIER", "{error}&lLEAVE",
                List.of("", "{secondary}Information:", " {letters_black}▎ {letters}Closes this menu.", "",
                        "{warning}➥ Right-click to leave", ""),
                "minecraft:block.note_block.hat|0.35|1.8", "minecraft:block.note_block.hat|0.7|1.0",
                "minecraft:item.book.page_turn|1.0|1.0", "minecraft:block.ender_chest.open|0.6|1.4",
                "minecraft:item.bundle.remove_one|1.0|0.5", "minecraft:entity.villager.no|0.6|1.0",
                0.95, false);
    }

    public SpatialTheme {
        spotLines = spotLines == null ? List.of() : List.copyOf(spotLines);
        leaveLore = leaveLore == null ? List.of() : List.copyOf(leaveLore);
    }

    /** The library's look; the same as {@code new SpatialTheme()}. */
    public static SpatialTheme defaults() {
        return new SpatialTheme();
    }
}
