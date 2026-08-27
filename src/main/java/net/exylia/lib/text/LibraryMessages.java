package net.exylia.lib.text;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.config.Key;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Everything the library itself says to a player.
 *
 * <p>Generated as {@code plugins/ExyliaLib/messages.yml} on first start, next
 * to {@code colors.yml}, and reloaded by {@code /exylialib reload}.
 *
 * <h2>Why it is one file for the whole server</h2>
 * Some of what a player reads does not belong to any plugin. Being told to
 * left-click one corner and right-click the other describes <em>the library's
 * selector</em>, handed out by the library and answered by the library's
 * listener; the plugin that started the selection contributes nothing to that
 * sentence but a copy of it. Six plugins each carrying their own copy is six
 * chances to word it differently and six copies to fix when the gesture
 * changes &mdash; which is how prompts telling admins to hold a wooden axe
 * outlived the golden one the selector actually hands out.
 *
 * <p>A plugin's own {@code messages.yml} keeps everything a plugin has an
 * opinion about. What lands here is only the text the library would have to
 * invent for itself.
 *
 * @param wizard    what a guided flow tells a player to do
 * @param selection what the block selector tells them while they pick
 * @since 1.67.0
 */
@Comment("What ExyliaLib itself says to your players.")
@Comment("")
@Comment("Only the text the library owns: the gestures its own selector and")
@Comment("its own guided flows ask for. Everything a plugin has an opinion")
@Comment("about stays in that plugin's messages file.")
@Comment("")
@Comment("Colours use palette tokens such as {primary} or {highlight}, so")
@Comment("recolouring the server never means editing this file.")
@Comment("")
@Comment("Run /exylialib reload after editing. No restart is needed.")
public record LibraryMessages(

        @Comment("What a guided flow tells a player to do.")
        @NotNull Wizard wizard,

        @Comment("What the block selector tells a player while they pick.")
        @NotNull Selection selection
) {

    /** The Exylia defaults. */
    public LibraryMessages() {
        this(new Wizard(), new Selection());
    }

    public LibraryMessages {
        if (wizard == null) {
            wizard = new Wizard();
        }
        if (selection == null) {
            selection = new Selection();
        }
    }

    /**
     * What a guided flow tells a player to do.
     *
     * <p>One line per gesture, because the gesture is the same wherever it is
     * asked for: a flow setting a lobby and a flow setting a mine both want the
     * player to stand somewhere and sneak-click.
     *
     * @param stand  asked to stand where they mean
     * @param point  asked to click a block
     * @param region asked to select a box
     * @param item   asked to hold an item
     */
    public record Wizard(

            @Comment("Asked to stand where they want something placed.")
            @NotNull String stand,

            @Comment("Asked to click the block they mean.")
            @NotNull String point,

            @Comment("Asked to select an area with the selector.")
            @NotNull String region,

            @Comment("Asked to hold the item they mean.")
            @NotNull String item
    ) {

        /** Asked to stand where they mean. */
        public static final String DEFAULT_STAND =
                "{warning}➥ {letters}Stand where you want it and {highlight}sneak + click{letters}.";

        /** Asked to click a block. */
        public static final String DEFAULT_POINT =
                "{warning}➥ {letters}Left-click the block you want.";

        /** Asked to select a box. */
        public static final String DEFAULT_REGION =
                "{warning}➥ {letters}Left-click one corner and right-click the other, then "
                        + "{highlight}shift + left-click {letters}to confirm.";

        /** Asked to hold an item. */
        public static final String DEFAULT_ITEM =
                "{warning}➥ {letters}Hold the item you want, then confirm.";

        /** The Exylia defaults. */
        public Wizard() {
            this(DEFAULT_STAND, DEFAULT_POINT, DEFAULT_REGION, DEFAULT_ITEM);
        }

        public Wizard {
            // A blank prompt is not an opinion, it is a deleted line, and a
            // gesture step with nothing on screen is a player staring at a
            // server that is waiting for them.
            stand = orDefault(stand, DEFAULT_STAND);
            point = orDefault(point, DEFAULT_POINT);
            region = orDefault(region, DEFAULT_REGION);
            item = orDefault(item, DEFAULT_ITEM);
        }
    }

    /**
     * What the block selector tells a player while they pick.
     *
     * <p>{@code %x%}, {@code %y%} and {@code %z%} are the block just clicked,
     * {@code %blocks%} how many the two corners enclose, and {@code %selector%}
     * the tool they are selecting with, named as a player would name it.
     *
     * @param firstCorner  chat line for the first corner
     * @param secondCorner chat line for the second corner
     * @param volume       chat line naming how big the box is
     * @param confirmed    chat line for an accepted selection
     * @param guideCorners the standing prompt while no corner is set
     * @param guideFirst   the standing prompt while only the first is set
     * @param guideSecond  the standing prompt while only the second is set
     * @param guideConfirm the standing prompt once the box is complete
     */
    public record Selection(

            @Key("first-corner")
            @Comment("Sent when the first corner is set. %x% %y% %z%.")
            @NotNull String firstCorner,

            @Key("second-corner")
            @Comment("Sent when the second corner is set. %x% %y% %z%.")
            @NotNull String secondCorner,

            @Comment("Sent when both corners are set. %blocks% is the volume.")
            @NotNull String volume,

            @Comment("Sent when the player confirms what they picked.")
            @NotNull String confirmed,

            @Key("guide-corners")
            @Comment("The action bar shown while the selection is open and")
            @Comment("neither corner is set. %selector% is the tool they were")
            @Comment("handed, named the way a player would name it.")
            @NotNull String guideCorners,

            @Key("guide-first")
            @Comment("The action bar shown while only the first corner is set.")
            @NotNull String guideFirst,

            @Key("guide-second")
            @Comment("The action bar shown while only the second corner is set.")
            @NotNull String guideSecond,

            @Key("guide-confirm")
            @Comment("The action bar shown once the box is complete and is")
            @Comment("waiting to be confirmed. %blocks% is the volume.")
            @NotNull String guideConfirm
    ) {

        /** The first corner, as a chat line. */
        public static final String DEFAULT_FIRST_CORNER =
                "{success}● {letters}First corner {letters_black}» {info}%x%, %y%, %z%";

        /** The second corner, as a chat line. */
        public static final String DEFAULT_SECOND_CORNER =
                "{error}● {letters}Second corner {letters_black}» {info}%x%, %y%, %z%";

        /** How big the box is. */
        public static final String DEFAULT_VOLUME =
                "{secondary}Selection: {info}%blocks% {letters}blocks";

        /** What an accepted selection says. */
        public static final String DEFAULT_CONFIRMED = "{success}● {letters}Selection confirmed";

        /** The standing prompt while no corner is set. */
        public static final String DEFAULT_GUIDE_CORNERS =
                "{letters}Left-click and right-click two corners with the {highlight}%selector%";

        /** The standing prompt while only the first corner is set. */
        public static final String DEFAULT_GUIDE_FIRST = "{letters}Right-click the {error}second corner";

        /** The standing prompt while only the second corner is set. */
        public static final String DEFAULT_GUIDE_SECOND = "{letters}Left-click the {success}first corner";

        /** The standing prompt while the box waits to be confirmed. */
        public static final String DEFAULT_GUIDE_CONFIRM =
                "{warning}➥ {letters}Shift + left-click to confirm {letters_black}» "
                        + "{info}%blocks% {letters}blocks";

        /** The Exylia defaults. */
        public Selection() {
            this(DEFAULT_FIRST_CORNER, DEFAULT_SECOND_CORNER, DEFAULT_VOLUME, DEFAULT_CONFIRMED,
                    DEFAULT_GUIDE_CORNERS, DEFAULT_GUIDE_FIRST, DEFAULT_GUIDE_SECOND,
                    DEFAULT_GUIDE_CONFIRM);
        }

        public Selection {
            firstCorner = orDefault(firstCorner, DEFAULT_FIRST_CORNER);
            secondCorner = orDefault(secondCorner, DEFAULT_SECOND_CORNER);
            volume = orDefault(volume, DEFAULT_VOLUME);
            confirmed = orDefault(confirmed, DEFAULT_CONFIRMED);
            guideCorners = orDefault(guideCorners, DEFAULT_GUIDE_CORNERS);
            guideFirst = orDefault(guideFirst, DEFAULT_GUIDE_FIRST);
            guideSecond = orDefault(guideSecond, DEFAULT_GUIDE_SECOND);
            guideConfirm = orDefault(guideConfirm, DEFAULT_GUIDE_CONFIRM);
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static volatile LibraryMessages instance;
    private static volatile ConfigFile<LibraryMessages> file;

    /**
     * Reads {@code messages.yml}, creating it if absent.
     *
     * <p>Called once from ExyliaLib's own startup.
     *
     * @param plugin the library
     * @return the messages now in force
     */
    public static @NotNull LibraryMessages load(@NotNull Plugin plugin) {
        ConfigFile<LibraryMessages> loaded =
                Configs.define(plugin, "messages", LibraryMessages.class).load();
        file = loaded;
        instance = loaded.get();
        loaded.onReload(values -> instance = values);
        return instance;
    }

    /**
     * Re-reads the file.
     *
     * @return the messages now in force
     */
    public static @NotNull LibraryMessages reload() {
        ConfigFile<LibraryMessages> current = file;
        if (current != null) {
            current.reload();
            instance = current.get();
        }
        return get();
    }

    /**
     * What the library says right now.
     *
     * <p>The defaults before the file has been read, so anything asking early
     * &mdash; or a test with no server at all &mdash; still has a sentence to
     * show rather than a null.
     *
     * @return the messages
     */
    public static @NotNull LibraryMessages get() {
        LibraryMessages current = instance;
        return current != null ? current : new LibraryMessages();
    }
}
