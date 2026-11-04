package net.exylia.lib.spatial.crate;

import net.exylia.lib.config.Comment;
import net.exylia.lib.spatial.SpatialLayout;
import net.exylia.lib.spatial.SpatialTheme;

import java.util.List;

/**
 * How the in-world crate opening looks: the file {@code plugins/<Plugin>/spatial/crate.yml}, written with
 * these defaults the first time and given the keys a newer library adds, its values never overwritten.
 *
 * <p>Coordinates are blocks on the board, a plane {@code distance} ahead of the eyes: {@code up} from eye
 * height, {@code across} to the right. Every line is a template: {@code %name%}, {@code %rarity%},
 * {@code %refund%}, {@code %current%}, {@code %total%} and {@code %keys%} are filled where they make sense.
 * A line left as the library wrote it is shown in the server's language (ExyliaLib's {@code language});
 * a line you change is shown as you wrote it.
 *
 * @since 1.416.0
 */
public record CrateSceneLayout(
        @Comment("The board's distance from the eyes, in blocks; brought nearer (never under 2) when a wall is close.")
        double distance,
        @Comment("Hide other players from the viewer, and the viewer from them, while it plays.")
        boolean hideOthers,
        @Comment("Hold the viewer still while it plays. They can still look round, and sneak to skip.")
        boolean freeze,
        @Comment("The glass, plates, text sizes and sounds, as every spatial menu's theme (its leave item is not used).")
        SpatialTheme theme,
        @Comment("The line across the top, the crate counter under it and the hint at its side.")
        Header header,
        @Comment("The reel: rewards sliding past the marker and slowing onto the prize the server already drew.")
        @Comment("slots: how many show at once. seconds: how long it runs. faces: how many go past.")
        @Comment("near-miss: the face after the prize is of the best rarity, so it is seen going past the line.")
        Reel reel,
        @Comment("The crate the reel stands over, built from blocks. Its lid opens as the prize is revealed.")
        Crate crate,
        @Comment("The reveal: the prize's name, rarity and what became of it, a burst and a sound scaled to its")
        @Comment("rarity (sounds from the lowest rarity to the highest), then hold-seconds before the next crate.")
        Reveal reveal,
        @Comment("Where the prize is tried on once revealed, in the crate's place: the player wearing it, an")
        @Comment("effect played as a miniature (effect-scale) on a mannequin, or an item turning on its own.")
        Preview preview,
        @Comment("The two buttons after the last reveal. OPEN ANOTHER shows only while the player still has a key.")
        Buttons buttons,
        @Comment("Unused since 1.463.0: the hotbar stays empty while it plays; sneaking skips to the end and hands")
        @Comment("every prize over at once.")
        SkipItem skipItem,
        @Comment("The blocks standing between the viewer and the board (walls, trunks, leaves) are shown to them as")
        @Comment("air while it plays, the ground they stand on kept; off brings the board nearer to a wall instead.")
        boolean clearView,
        @Comment("One soft dark sheet behind the whole scene, its edge fading out over 'feather' blocks, so every word")
        @Comment("reads on sky and ground alike (as the spatial menus' centre stage). With it the reel has no glass box;")
        @Comment("alpha 0 takes it away and brings the glass back. Since 1.461.0.")
        SpatialLayout.Backdrop backdrop) {

    /** The backdrop a scene gets when its file does not say: behind the header, the reel and the crate. */
    public static final SpatialLayout.Backdrop BACKDROP =
            new SpatialLayout.Backdrop(List.of(0.0, -0.1, -0.3), List.of(4.4, 3.3), "{primary}", 150, 0.9, 10);

    public CrateSceneLayout() {
        this(3.2, true, true, new SpatialTheme(), new Header(), new Reel(), new Crate(), new Reveal(), new Preview(),
                new Buttons(), new SkipItem(), true, BACKDROP);
    }

    /** Every key but the backdrop: the shape layouts had before 1.461.0. */
    public CrateSceneLayout(double distance, boolean hideOthers, boolean freeze, SpatialTheme theme, Header header,
                            Reel reel, Crate crate, Reveal reveal, Preview preview, Buttons buttons, SkipItem skipItem,
                            boolean clearView) {
        this(distance, hideOthers, freeze, theme, header, reel, crate, reveal, preview, buttons, skipItem, clearView,
                BACKDROP);
    }

    /** @deprecated since 1.442.0; use the constructor with {@code clearView}. */
    @Deprecated
    public CrateSceneLayout(double distance, boolean hideOthers, boolean freeze, SpatialTheme theme, Header header,
                            Reel reel, Crate crate, Reveal reveal, Preview preview, Buttons buttons, SkipItem skipItem) {
        this(distance, hideOthers, freeze, theme, header, reel, crate, reveal, preview, buttons, skipItem, true);
    }

    public CrateSceneLayout {
        distance = distance <= 0 ? 3.2 : distance;
        theme = theme == null ? new SpatialTheme() : theme;
        header = header == null ? new Header() : header;
        reel = reel == null ? new Reel() : reel;
        crate = crate == null ? new Crate() : crate;
        reveal = reveal == null ? new Reveal() : reveal;
        preview = preview == null ? new Preview() : preview;
        buttons = buttons == null ? new Buttons() : buttons;
        skipItem = skipItem == null ? new SkipItem() : skipItem;
        backdrop = backdrop == null ? BACKDROP : backdrop;
    }

    public record SkipItem(String material, String name, List<String> lore) {
        public SkipItem() {
            this("CLOCK", "{warning}&lSKIP", List.of("", "{secondary}Information:",
                    " {letters_black}▎ {letters}Hands every prize over now.", "",
                    "{warning}➥ Right-click or sneak to skip", ""));
        }

        public SkipItem {
            material = material == null || material.isBlank() ? "CLOCK" : material;
            name = name == null ? "" : name;
            lore = lore == null ? List.of() : List.copyOf(lore);
        }
    }

    public record Header(double up, String title, String counter, String hint) {
        public Header() {
            // The centre stage's words: light on the backdrop, the quiet ones in letters_black (muted reads under 3:1).
            this(1.45, "{secondary_light}&lCRATE OPENING",
                    "{letters_black}Crate {letters}%current% {letters_black}/ {letters}%total%",
                    "{letters_black}Sneak to skip");
        }

        public Header {
            title = title == null ? "" : title;
            counter = counter == null ? "" : counter;
            hint = hint == null ? "" : hint;
        }
    }

    public record Reel(double up, int slots, double spacing, double icon, double seconds, int faces,
                       double pauseSeconds, boolean nearMiss, String marker, String tickSound) {
        public Reel() {
            this(0.72, 7, 0.46, 0.34, 4.6, 32, 0.6, true, "{highlight}", "minecraft:block.note_block.hat|0.4|1.6");
        }

        public Reel {
            slots = Math.clamp(slots, 3, 11) | 1;
            spacing = spacing <= 0 ? 0.46 : spacing;
            icon = icon <= 0 ? 0.34 : icon;
            seconds = Math.clamp(seconds, 1.0, 12.0);
            faces = Math.clamp(faces, 8, 80);
            pauseSeconds = Math.max(0, pauseSeconds);
            marker = marker == null ? "{highlight}" : marker;
            tickSound = tickSound == null ? "" : tickSound;
        }
    }

    public record Crate(double up, double width, double height, String body, String lid, String trim,
                        String openSound) {
        public Crate() {
            this(-1.5, 0.9, 0.5, "POLISHED_BLACKSTONE", "POLISHED_BLACKSTONE_BRICKS", "GILDED_BLACKSTONE",
                    "minecraft:block.ender_chest.open|0.5|1.2");
        }

        public Crate {
            width = width <= 0 ? 0.9 : width;
            height = height <= 0 ? 0.5 : height;
            body = body == null ? "POLISHED_BLACKSTONE" : body;
            lid = lid == null ? "POLISHED_BLACKSTONE_BRICKS" : lid;
            trim = trim == null ? "GILDED_BLACKSTONE" : trim;
            openSound = openSound == null ? "" : openSound;
        }
    }

    public record Reveal(double up, String name, String rarity, String won, String duplicate, double holdSeconds,
                         boolean burst, List<String> sounds) {
        public Reveal() {
            this(0.2, "%name%", "%rarity%", "{success}✔ New to your collection",
                    "{warning}Already owned {letters_black}» {highlight}+%refund% {letters}%refund:key|keys% back", 3.5, true,
                    List.of("minecraft:entity.experience_orb.pickup|0.6|1.2",
                            "minecraft:block.note_block.chime|0.6|1.2",
                            "minecraft:block.note_block.bell|0.6|1.4",
                            "minecraft:block.amethyst_block.chime|0.7|1.6"));
        }

        public Reveal {
            name = name == null ? "%name%" : name;
            rarity = rarity == null ? "%rarity%" : rarity;
            won = won == null ? "" : won;
            duplicate = duplicate == null ? "" : duplicate;
            holdSeconds = Math.clamp(holdSeconds, 0.5, 30);
            sounds = sounds == null ? List.of() : List.copyOf(sounds);
        }
    }

    public record Preview(double scale, double turnSpeed, double effectScale) {
        public Preview() {
            this(0.7, 14, 0.35);
        }

        public Preview {
            scale = scale <= 0 ? 0.7 : scale;
            effectScale = effectScale <= 0 ? 0.35 : effectScale;
        }
    }

    public record Buttons(double up, double spread, double width, String again, String done) {
        public Buttons() {
            this(-0.75, 1.35, 1.05, "{success}&lOPEN ANOTHER", "{highlight}&lDONE");
        }

        public Buttons {
            width = width <= 0 ? 1.05 : width;
            again = again == null ? "" : again;
            done = done == null ? "" : done;
        }
    }
}
