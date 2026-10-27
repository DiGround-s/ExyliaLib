package net.exylia.lib.text;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.config.Key;
import net.exylia.lib.region.SelectionMode;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
 * @param players   what a command says about a player it cannot find
 * @param purchase  what buying a cosmetic with currency says
 * @param logs      what the staff audit log says
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
        @NotNull Selection selection,

        @Comment("What a command says about a player it cannot find.")
        @NotNull Players players,

        @Comment("What buying a cosmetic with currency says, in every cosmetic plugin.")
        @NotNull Purchase purchase,

        @Comment("What /exylialib logs, the staff audit log, says.")
        @NotNull Logs logs,

        @Comment("Setting a table down by clicking its chairs, and the line over each seat.")
        @NotNull Tables tables
) {

    /** The Exylia defaults. */
    public LibraryMessages() {
        this(new Wizard(), new Selection(), new Players(), new Purchase(), new Logs(), new Tables());
    }

    /** The messages before tables had any, with their defaults. */
    public LibraryMessages(Wizard wizard, Selection selection, Players players, Purchase purchase, Logs logs) {
        this(wizard, selection, players, purchase, logs, null);
    }

    /** The messages before the staff log had any, with its defaults. */
    public LibraryMessages(Wizard wizard, Selection selection, Players players, Purchase purchase) {
        this(wizard, selection, players, purchase, null, null);
    }

    /** The messages before purchases had any, with the purchase defaults. */
    public LibraryMessages(Wizard wizard, Selection selection, Players players) {
        this(wizard, selection, players, null, null, null);
    }

    public LibraryMessages {
        if (wizard == null) {
            wizard = new Wizard();
        }
        if (selection == null) {
            selection = new Selection();
        }
        if (players == null) {
            players = new Players();
        }
        if (purchase == null) {
            purchase = new Purchase();
        }
        if (logs == null) {
            logs = new Logs();
        }
        if (tables == null) {
            tables = new Tables();
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
     * {@code %blocks%} how many the selection encloses, {@code %selector%}
     * the tool they are selecting with, named as a player would name it,
     * {@code %points%} how many points an outline has, {@code %radius%} a
     * circle's radius in blocks and {@code %mode%} the shape being picked.
     *
     * @param firstCorner  chat line for the first corner
     * @param secondCorner chat line for the second corner
     * @param volume       chat line naming how big the box is
     * @param confirmed    chat line for an accepted selection
     * @param guideCorners the standing prompt while no corner is set
     * @param guideFirst   the standing prompt while only the first is set
     * @param guideSecond  the standing prompt while only the second is set
     * @param guideConfirm the standing prompt once the box is complete
     * @param area         chat line naming how big a full-height selection is
     * @param guideConfirmArea the standing prompt once a full-height selection is complete
     * @param point        chat line for a point added to an outline
     * @param pointRemoved chat line for an outline's last point taken back
     * @param center       chat line for a circle's or sphere's centre
     * @param radius       chat line for a circle's or sphere's radius
     * @param mode         chat line for a change of shape
     * @param guidePoints  the standing prompt while an outline has under three points
     * @param guideFreehand the standing prompt while a freehand pen is up
     * @param guideDrawing the standing prompt while a freehand pen is down
     * @param guideCenter  the standing prompt while a circle has no centre
     * @param guideRadius  the standing prompt while a circle has a centre and no edge
     * @param guideMode    how the standing prompt names the shape, when there is a choice
     * @param modeCuboid   what a box is called
     * @param modePolygon  what a polygon is called
     * @param modeFreehand what a freehand outline is called
     * @param modeCircle   what a circle is called
     * @param modeSphere   what a sphere is called
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
            @NotNull String guideConfirm,

            @Comment("Sent when both corners of a full-height selection are set.")
            @Comment("%blocks% is the number of columns.")
            @NotNull String area,

            @Key("guide-confirm-area")
            @Comment("The action bar shown once a full-height selection is complete")
            @Comment("and waiting to be confirmed. %blocks% is the number of columns.")
            @NotNull String guideConfirmArea,

            @Comment("Sent when a point is added to a polygon. %points% is its number,")
            @Comment("%x% %y% %z% the block.")
            @NotNull String point,

            @Key("point-removed")
            @Comment("Sent when the last point of an outline is taken back.")
            @Comment("%points% is the number it had.")
            @NotNull String pointRemoved,

            @Comment("Sent when the centre of a circle or sphere is set. %x% %y% %z%.")
            @NotNull String center,

            @Comment("Sent when the edge of a circle or sphere is set. %radius% is")
            @Comment("the radius in blocks.")
            @NotNull String radius,

            @Comment("Sent when the player changes shape. %mode% is the new one.")
            @NotNull String mode,

            @Key("guide-points")
            @Comment("The action bar shown while a polygon has fewer than three")
            @Comment("points. %points% is how many it has.")
            @NotNull String guidePoints,

            @Key("guide-freehand")
            @Comment("The action bar shown while a freehand outline's pen is up.")
            @NotNull String guideFreehand,

            @Key("guide-drawing")
            @Comment("The action bar shown while a freehand outline is being drawn.")
            @NotNull String guideDrawing,

            @Key("guide-center")
            @Comment("The action bar shown while a circle or sphere has no centre.")
            @NotNull String guideCenter,

            @Key("guide-radius")
            @Comment("The action bar shown while a circle or sphere has a centre")
            @Comment("but no edge.")
            @NotNull String guideRadius,

            @Key("guide-mode")
            @Comment("How the action bar names the shape when the player can")
            @Comment("change it. %mode% is the shape, %guide% the line it wraps.")
            @NotNull String guideMode,

            @Key("mode-cuboid")
            @Comment("What a box selection is called.")
            @NotNull String modeCuboid,

            @Key("mode-polygon")
            @Comment("What a polygon selection is called.")
            @NotNull String modePolygon,

            @Key("mode-freehand")
            @Comment("What a freehand selection is called.")
            @NotNull String modeFreehand,

            @Key("mode-circle")
            @Comment("What a circle selection is called.")
            @NotNull String modeCircle,

            @Key("mode-sphere")
            @Comment("What a sphere selection is called.")
            @NotNull String modeSphere
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

        /** How big a full-height selection is. */
        public static final String DEFAULT_AREA =
                "{secondary}Selection: {info}%blocks% {letters}columns {letters_black}» {info}full height";

        /** The standing prompt while a full-height selection waits to be confirmed. */
        public static final String DEFAULT_GUIDE_CONFIRM_AREA =
                "{warning}➥ {letters}Shift + left-click to confirm {letters_black}» "
                        + "{info}%blocks% {letters}columns";

        /** A point added to a polygon. */
        public static final String DEFAULT_POINT =
                "{primary}● {letters}Point {info}#%points% {letters_black}» {info}%x%, %y%, %z%";

        /** An outline's last point taken back. */
        public static final String DEFAULT_POINT_REMOVED = "{error}● {letters}Point {info}#%points% {letters}removed";

        /** A circle's or sphere's centre. */
        public static final String DEFAULT_CENTER = "{success}● {letters}Centre {letters_black}» {info}%x%, %y%, %z%";

        /** A circle's or sphere's radius. */
        public static final String DEFAULT_RADIUS = "{info}● {letters}Radius {letters_black}» {info}%radius% {letters}blocks";

        /** A change of shape. */
        public static final String DEFAULT_MODE =
                "{secondary}Shape {letters_black}» {highlight}%mode% {letters_black}(shift + right-click to change)";

        /** The standing prompt while a polygon has under three points. */
        public static final String DEFAULT_GUIDE_POINTS =
                "{letters}Left-click to add points {letters_black}» {info}%points% {letters}placed";

        /** The standing prompt while a freehand pen is up. */
        public static final String DEFAULT_GUIDE_FREEHAND =
                "{letters}Left-click to put the pen down, then look along the edge";

        /** The standing prompt while a freehand pen is down. */
        public static final String DEFAULT_GUIDE_DRAWING =
                "{info}● {letters}Drawing {letters_black}» {letters}left-click to lift the pen";

        /** The standing prompt while a circle has no centre. */
        public static final String DEFAULT_GUIDE_CENTER = "{letters}Left-click the {success}centre";

        /** The standing prompt while a circle has a centre and no edge. */
        public static final String DEFAULT_GUIDE_RADIUS = "{letters}Right-click a block on the {info}edge";

        /** The shape in front of the standing prompt. */
        public static final String DEFAULT_GUIDE_MODE = "{highlight}%mode% {letters_black}» %guide%";

        /** What a box is called. */
        public static final String DEFAULT_MODE_CUBOID = "Box";

        /** What a polygon is called. */
        public static final String DEFAULT_MODE_POLYGON = "Polygon";

        /** What a freehand outline is called. */
        public static final String DEFAULT_MODE_FREEHAND = "Freehand";

        /** What a circle is called. */
        public static final String DEFAULT_MODE_CIRCLE = "Circle";

        /** What a sphere is called. */
        public static final String DEFAULT_MODE_SPHERE = "Sphere";

        /** The Exylia defaults. */
        public Selection() {
            this(DEFAULT_FIRST_CORNER, DEFAULT_SECOND_CORNER, DEFAULT_VOLUME, DEFAULT_CONFIRMED,
                    DEFAULT_GUIDE_CORNERS, DEFAULT_GUIDE_FIRST, DEFAULT_GUIDE_SECOND,
                    DEFAULT_GUIDE_CONFIRM, DEFAULT_AREA, DEFAULT_GUIDE_CONFIRM_AREA);
        }

        /** The lines before selections had shapes, with the defaults for the rest. */
        public Selection(String firstCorner, String secondCorner, String volume, String confirmed,
                         String guideCorners, String guideFirst, String guideSecond, String guideConfirm,
                         String area, String guideConfirmArea) {
            this(firstCorner, secondCorner, volume, confirmed, guideCorners, guideFirst, guideSecond,
                    guideConfirm, area, guideConfirmArea, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);
        }

        /**
         * What a mode is called, in the server's words.
         *
         * @param mode the mode
         * @return its name
         */
        public @NotNull String modeName(@NotNull SelectionMode mode) {
            return switch (mode) {
                case CUBOID -> modeCuboid;
                case POLYGON -> modePolygon;
                case FREEHAND -> modeFreehand;
                case CIRCLE -> modeCircle;
                case SPHERE -> modeSphere;
            };
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
            area = orDefault(area, DEFAULT_AREA);
            guideConfirmArea = orDefault(guideConfirmArea, DEFAULT_GUIDE_CONFIRM_AREA);
            point = orDefault(point, DEFAULT_POINT);
            pointRemoved = orDefault(pointRemoved, DEFAULT_POINT_REMOVED);
            center = orDefault(center, DEFAULT_CENTER);
            radius = orDefault(radius, DEFAULT_RADIUS);
            mode = orDefault(mode, DEFAULT_MODE);
            guidePoints = orDefault(guidePoints, DEFAULT_GUIDE_POINTS);
            guideFreehand = orDefault(guideFreehand, DEFAULT_GUIDE_FREEHAND);
            guideDrawing = orDefault(guideDrawing, DEFAULT_GUIDE_DRAWING);
            guideCenter = orDefault(guideCenter, DEFAULT_GUIDE_CENTER);
            guideRadius = orDefault(guideRadius, DEFAULT_GUIDE_RADIUS);
            guideMode = orDefault(guideMode, DEFAULT_GUIDE_MODE);
            modeCuboid = orDefault(modeCuboid, DEFAULT_MODE_CUBOID);
            modePolygon = orDefault(modePolygon, DEFAULT_MODE_POLYGON);
            modeFreehand = orDefault(modeFreehand, DEFAULT_MODE_FREEHAND);
            modeCircle = orDefault(modeCircle, DEFAULT_MODE_CIRCLE);
            modeSphere = orDefault(modeSphere, DEFAULT_MODE_SPHERE);
        }
    }

    /**
     * What a command says about a player it cannot find.
     *
     * <p>Here rather than in every plugin because it is the same sentence
     * everywhere and it is the library that decides when it is true: the
     * player module owns the lookup, so it owns the line it fails with.
     * Eleven plugins had eleven wordings of it, several of them saying "is
     * not online" about a player who had simply never played there.
     *
     * <p>{@code %player%} is the name as the sender typed it.
     *
     * @param notFound nobody answers to that name, anywhere
     * @param notHere  they exist, but they are not on this server
     */
    public record Players(

            @Key("not-found")
            @Comment("Sent when no player anywhere answers to the name that")
            @Comment("was typed. %player% is that name.")
            @NotNull String notFound,

            @Key("not-here")
            @Comment("Sent when the player is known but is not on this")
            @Comment("server, for the commands that need them present.")
            @NotNull String notHere
    ) {

        /** Nobody answers to that name. */
        public static final String DEFAULT_NOT_FOUND =
                "[sound:ENTITY_VILLAGER_NO|1|1]{error}✖ {letters}No player named "
                        + "{highlight}%player% {letters}was found.";

        /** They are not on this server. */
        public static final String DEFAULT_NOT_HERE =
                "[sound:ENTITY_VILLAGER_NO|1|1]{error}✖ {highlight}%player% "
                        + "{letters}is not on this server.";

        /** The Exylia defaults. */
        public Players() {
            this(DEFAULT_NOT_FOUND, DEFAULT_NOT_HERE);
        }

        public Players {
            notFound = orDefault(notFound, DEFAULT_NOT_FOUND);
            notHere = orDefault(notHere, DEFAULT_NOT_HERE);
        }
    }

    /**
     * What buying a cosmetic with currency says.
     *
     * <p>One set for every cosmetic plugin, because the flow is the library's:
     * {@link net.exylia.lib.cosmetic.CosmeticPurchases} charges, grants and
     * refunds, so it owns the sentences that report it.
     *
     * <p>{@code %item%} is the cosmetic's display name, {@code %price%} the price
     * written the way its currency writes it, {@code %balance%} what the player
     * holds of that currency.
     *
     * @param confirm       the confirmation screen's title
     * @param confirmButton the confirmation screen's buy button
     * @param success       bought and granted
     * @param insufficient  not enough money
     * @param unavailable   no economy serves the price's currency
     * @param busy          a purchase of theirs is still in flight
     * @param alreadyOwned  they own it already
     * @param failed        charged, not granted, refunded
     * @param refundFailed  charged, not granted, and the refund was refused
     * @since 1.239.0
     */
    public record Purchase(

            @Comment("The title of the confirmation screen. %item% %price%.")
            @NotNull String confirm,

            @Key("confirm-button")
            @Comment("The button that confirms the purchase.")
            @NotNull String confirmButton,

            @Comment("Sent once the cosmetic is theirs. %item% %price%.")
            @NotNull String success,

            @Key("insufficient-funds")
            @Comment("Sent when they cannot afford it. %item% %price% %balance%.")
            @NotNull String insufficient,

            @Comment("Sent when no economy can take the price right now. %item%.")
            @NotNull String unavailable,

            @Comment("Sent when a purchase of theirs is still being processed.")
            @NotNull String busy,

            @Key("already-owned")
            @Comment("Sent when they already own it. %item%.")
            @NotNull String alreadyOwned,

            @Comment("Sent when the cosmetic could not be given and the money")
            @Comment("went back to them. %item% %price%.")
            @NotNull String failed,

            @Key("refund-failed")
            @Comment("Sent when the cosmetic could not be given and the refund")
            @Comment("failed too; the console logs what to give back. %item% %price%.")
            @NotNull String refundFailed
    ) {

        /** The confirmation screen's title. */
        public static final String DEFAULT_CONFIRM =
                "{letters}Buy {highlight}%item% {letters}for {info}%price%{letters}?";

        /** The confirmation screen's buy button. */
        public static final String DEFAULT_CONFIRM_BUTTON = "{success}&lBUY";

        /** Bought and granted. */
        public static final String DEFAULT_SUCCESS =
                "[sound:ENTITY_PLAYER_LEVELUP|1|1.4]{success}✔ {letters}You unlocked {highlight}%item% "
                        + "{letters}for {info}%price%{letters}.";

        /** Not enough money. */
        public static final String DEFAULT_INSUFFICIENT =
                "[sound:ENTITY_VILLAGER_NO|1|1]{error}✖ {letters}You need {info}%price% {letters}to buy "
                        + "{highlight}%item% {letters_black}» {letters}you have {info}%balance%{letters}.";

        /** No economy serves the currency. */
        public static final String DEFAULT_UNAVAILABLE =
                "[sound:ENTITY_VILLAGER_NO|1|1]{error}✖ {highlight}%item% {letters}cannot be bought right now.";

        /** A purchase is in flight. */
        public static final String DEFAULT_BUSY =
                "{warning}➥ {letters}Your previous purchase is still being processed.";

        /** Already theirs. */
        public static final String DEFAULT_ALREADY_OWNED =
                "{warning}➥ {letters}You already own {highlight}%item%{letters}.";

        /** Not granted, refunded. */
        public static final String DEFAULT_FAILED =
                "[sound:ENTITY_VILLAGER_NO|1|1]{error}✖ {highlight}%item% {letters}could not be unlocked. "
                        + "{info}%price% {letters}was refunded.";

        /** Not granted, refund refused. */
        public static final String DEFAULT_REFUND_FAILED =
                "[sound:ENTITY_VILLAGER_NO|1|1]{error}✖ {highlight}%item% {letters}could not be unlocked "
                        + "and the refund of {info}%price% {letters}failed. {warning}Please contact staff.";

        /** The Exylia defaults. */
        public Purchase() {
            this(DEFAULT_CONFIRM, DEFAULT_CONFIRM_BUTTON, DEFAULT_SUCCESS, DEFAULT_INSUFFICIENT,
                    DEFAULT_UNAVAILABLE, DEFAULT_BUSY, DEFAULT_ALREADY_OWNED, DEFAULT_FAILED,
                    DEFAULT_REFUND_FAILED);
        }

        public Purchase {
            confirm = orDefault(confirm, DEFAULT_CONFIRM);
            confirmButton = orDefault(confirmButton, DEFAULT_CONFIRM_BUTTON);
            success = orDefault(success, DEFAULT_SUCCESS);
            insufficient = orDefault(insufficient, DEFAULT_INSUFFICIENT);
            unavailable = orDefault(unavailable, DEFAULT_UNAVAILABLE);
            busy = orDefault(busy, DEFAULT_BUSY);
            alreadyOwned = orDefault(alreadyOwned, DEFAULT_ALREADY_OWNED);
            failed = orDefault(failed, DEFAULT_FAILED);
            refundFailed = orDefault(refundFailed, DEFAULT_REFUND_FAILED);
        }
    }

    /**
     * What {@code /exylialib logs}, the staff audit log, says.
     *
     * @param unavailable the logs could not be read
     * @param empty       nothing matched; %subject% %plugin% %action%
     * @param header      the console listing's first line; %subject% %plugin% %action% %count%
     * @param entry       one console line; %when% %plugin% %action% %actor% %target% %detail% %server%
     * @param more        closes a listing cut short; %count%
     * @param everybody   what the feed of every player is called where a name would go
     * @param all         a filter with nothing chosen
     * @param none        no target, no detail, no location
     * @param detailLine  one lore line of a detail; %line%
     * @param viewItems   the click hint of an entry that kept items
     * @param viewEntry   the click hint of an entry that kept none
     * @since 1.296.0
     */
    public record Logs(
            @Comment("The logs could not be read.")
            @NotNull String unavailable,
            @Comment("Nothing logged. %subject% %plugin% %action%.")
            @NotNull String empty,
            @Comment("The console listing's first line. %subject% %plugin% %action% %count%.")
            @NotNull String header,
            @Comment("One console line. %when% %plugin% %action% %actor% %target% %detail% %server%.")
            @NotNull String entry,
            @Comment("Closes a console listing cut short. %count%.")
            @NotNull String more,
            @Comment("What the feed of every player is called where a name would go.")
            @NotNull String everybody,
            @Comment("A filter with nothing chosen.")
            @NotNull String all,
            @Comment("Shown where there is no target, detail or location.")
            @NotNull String none,
            @Key("detail-line")
            @Comment("One lore line of a long detail in the menu. %line%.")
            @NotNull String detailLine,
            @Key("view-items")
            @Comment("The click hint of an entry that kept the items it moved.")
            @NotNull String viewItems,
            @Key("view-entry")
            @Comment("The click hint of an entry with no items kept.")
            @NotNull String viewEntry
    ) {

        public static final String DEFAULT_UNAVAILABLE =
                "[sound:ENTITY_VILLAGER_NO|1|1]{error}✖ {letters}The logs could not be read. Try again in a moment.";
        public static final String DEFAULT_EMPTY =
                "{letters_black}▎ {muted}Nothing logged for {highlight}%subject% {muted}in {info}%plugin% {letters_black}» {info}%action%{muted}.";
        public static final String DEFAULT_HEADER =
                "{letters_black}▎ {letters}Logs of {highlight}%subject% {letters_black}» {info}%plugin% {letters_black}» {info}%action% {letters_black}(%count%)";
        public static final String DEFAULT_ENTRY =
                "{letters_black}▎ {muted}%when% {letters_black}» {secondary}%plugin% {highlight}%action% {letters}%actor% {letters_black}→ {letters}%target% {letters_black}» {letters}%detail%";
        public static final String DEFAULT_MORE =
                "{letters_black}▎ {letters}And {info}%count% {letters}more. Open the menu in game to read them all.";
        public static final String DEFAULT_EVERYBODY = "Everybody";
        public static final String DEFAULT_ALL = "All";
        public static final String DEFAULT_NONE = "—";
        public static final String DEFAULT_DETAIL_LINE = " {letters_black}▎ {letters}%line%";
        public static final String DEFAULT_VIEW_ITEMS = "{warning}➥ Click to view the items";
        public static final String DEFAULT_VIEW_ENTRY = "{warning}➥ Click to open the entry";

        /** The Exylia defaults. */
        public Logs() {
            this(null, null, null, null, null, null, null, null, null, null, null);
        }

        public Logs {
            unavailable = orDefault(unavailable, DEFAULT_UNAVAILABLE);
            empty = orDefault(empty, DEFAULT_EMPTY);
            header = orDefault(header, DEFAULT_HEADER);
            entry = orDefault(entry, DEFAULT_ENTRY);
            more = orDefault(more, DEFAULT_MORE);
            everybody = orDefault(everybody, DEFAULT_EVERYBODY);
            all = orDefault(all, DEFAULT_ALL);
            none = orDefault(none, DEFAULT_NONE);
            detailLine = orDefault(detailLine, DEFAULT_DETAIL_LINE);
            viewItems = orDefault(viewItems, DEFAULT_VIEW_ITEMS);
            viewEntry = orDefault(viewEntry, DEFAULT_VIEW_ENTRY);
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
                Configs.define(plugin, "messages", LibraryMessages.class).translated().load();
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
     * Setting a table down by clicking its chairs ({@code PlacedTables#setup}), and the line floated over
     * each seat of a table standing in the world.
     *
     * @since 1.373.0
     */
    public record Tables(
            @Key("seat-tool") @Comment("The tools drawn in the admin's hotbar while they set a table down.")
            @NotNull String seatTool,
            @Key("seat-tool-lore") @NotNull List<String> seatToolLore,
            @Key("done-tool") @NotNull String doneTool,
            @Key("done-tool-lore") @NotNull List<String> doneToolLore,
            @Key("cancel-tool") @NotNull String cancelTool,
            @Key("cancel-tool-lore") @NotNull List<String> cancelToolLore,
            @Comment("What they are told as they go. %table%; %seat% the seat just marked, %seats% how many.")
            @NotNull String started,
            @Key("seat-added") @NotNull String seatAdded,
            @Key("seat-removed") @NotNull String seatRemoved,
            @Key("board-set") @NotNull String boardSet,
            @Key("no-seats") @NotNull String noSeats,
            @NotNull String cancelled,
            @Key("seat-marker") @Comment("Drawn over each chair marked, and over the board's place, for the admin alone.")
            @NotNull String seatMarker,
            @Key("board-marker") @NotNull String boardMarker,
            @Key("seat-free") @Comment("Floated over a seat of a table: free, or taken (%player%).")
            @NotNull List<String> seatFree,
            @Key("seat-taken") @NotNull List<String> seatTaken
    ) {

        /** The Exylia defaults. */
        public Tables() {
            this(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        public Tables {
            seatTool = orDefault(seatTool, "{primary}&lSEAT");
            seatToolLore = seatToolLore != null ? List.copyOf(seatToolLore) : List.of("",
                    "{warning}➥ Left-click {letters_black}» {letters}a chair becomes a seat, or stops being one",
                    "{warning}➥ Right-click {letters_black}» {letters}the board goes on that block", "");
            doneTool = orDefault(doneTool, "{success}&lDONE");
            doneToolLore = doneToolLore != null ? List.copyOf(doneToolLore) : List.of("",
                    "{letters}Sets the table down.", "", "{warning}➥ Click to finish", "");
            cancelTool = orDefault(cancelTool, "{error}&lCANCEL");
            cancelToolLore = cancelToolLore != null ? List.copyOf(cancelToolLore) : List.of("",
                    "{letters}Leaves without changing anything.", "", "{error}➥ Click to cancel", "");
            started = orDefault(started, "[sound:BLOCK_NOTE_BLOCK_PLING|1|1.4]{secondary}Table {highlight}%table% "
                    + "{letters_black}» {letters}left-click each chair, right-click where the board goes, then {success}DONE{letters}.");
            seatAdded = orDefault(seatAdded, "[sound:BLOCK_NOTE_BLOCK_PLING|1|1.6]{success}● {letters}Seat {info}%seat% {letters}marked.");
            seatRemoved = orDefault(seatRemoved, "[sound:BLOCK_NOTE_BLOCK_BASS|1|0.8]{error}● {letters}Seat taken back "
                    + "{letters_black}» {info}%seats% {letters}left.");
            boardSet = orDefault(boardSet, "[sound:BLOCK_NOTE_BLOCK_PLING|1|1.2]{info}● {letters}The board goes there.");
            noSeats = orDefault(noSeats, "[sound:ENTITY_VILLAGER_NO|1|1]{error}✖ {letters}Left-click at least one chair first.");
            cancelled = orDefault(cancelled, "{muted}Setting the table down was cancelled. Nothing changed.");
            seatMarker = orDefault(seatMarker, "{success}&l%seat%");
            boardMarker = orDefault(boardMarker, "{info}&l✦");
            seatFree = seatFree != null ? List.copyOf(seatFree) : List.of("{success}● {letters}Free");
            seatTaken = seatTaken != null ? List.copyOf(seatTaken) : List.of("{highlight}%player%");
        }

        /** What {@code PlacedTables#setup} names and says. */
        public net.exylia.lib.stage.PlacedTables.SetupText setup() {
            return new net.exylia.lib.stage.PlacedTables.SetupText(seatTool, seatToolLore, doneTool, doneToolLore,
                    cancelTool, cancelToolLore, started, seatAdded, seatRemoved, boardSet, noSeats, cancelled,
                    seatMarker, boardMarker);
        }
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
