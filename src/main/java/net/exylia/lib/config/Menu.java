package net.exylia.lib.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * How a config record looks in the in-game settings screens ({@link ConfigMenus}). Every one is
 * optional and none changes the YAML: a record without them is drawn from its keys, its
 * {@link Comment}s and its types, and these only make the screens read the way a hand-made one
 * would.
 *
 * <pre>{@code
 * @Menu.Pages({
 *         @Menu.Card(id = "fees", label = "Fees & tax", icon = "GOLD_NUGGET",
 *                 about = {"What a listing costs to put up", "and what a sale pays the server."}),
 *         @Menu.Card(id = "limits", label = "Limits", icon = "CHEST")})
 * public record Settings(
 *         @Menu.Page("fees") @Menu.Label("Listing fee") @Menu.Icon("PAPER")
 *         @Menu.About({"Charged when a listing goes up,", "as a share of its price."})
 *         @Menu.Format(Menu.Style.PERCENT) @Menu.Range(min = 0, max = 100, step = 0.5)
 *         double listingFeePercent,
 *
 *         @Menu.Page("limits") @Menu.Range(min = 0, max = 1e12, none = -1) @Menu.Format(Menu.Style.MONEY)
 *         double maxPrice) { ... }
 * }</pre>
 *
 * <p>Without {@link Page}, a file's settings are grouped the way it is written: the keys at its
 * root on a <i>General</i> card, each section on a card of its own, each map or list of entries
 * on a card that opens the list.
 *
 * @since 1.449.0
 */
public final class Menu {

    private Menu() {
        throw new AssertionError("No instances.");
    }

    /**
     * What a key, a section or a file is called on screen, drawn in capitals. Default: its YAML
     * key ({@code min-distance} reads {@code MIN DISTANCE}). On a record type, the file's own name.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.TYPE})
    public @interface Label {
        String value();
    }

    /**
     * The item a key's or a section's button is drawn with, anything a menu's {@code material:}
     * accepts. Default: chosen from the key's name and type. A switch keeps its lime or gray dye.
     * On a record type, the icon of the file's header.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.TYPE})
    public @interface Icon {
        String value();
    }

    /**
     * The button's <i>Information</i>, one line each, palette tokens allowed. Keep it to what the
     * value does; the {@link Comment} above the key stays the owner's manual in the file.
     * Default: the comment's first sentence, wrapped.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.TYPE})
    public @interface About {
        String[] value();
    }

    /**
     * Puts a key on a themed page of its file, whatever section it is written in. The page is
     * described by a {@link Card} of the same id in a {@link Pages} of the file, or named after
     * its id when none describes it. Pages are listed in the order their keys are declared.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Page {
        String value();
    }

    /** The cards of a file's pages. On any record of the file, usually its root. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Pages {
        Card[] value();
    }

    /** One page: the card that opens it, and the header on top of it. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    public @interface Card {
        /** The id {@link Page} names. */
        String id();

        /** Default: the id, in words. */
        String label() default "";

        /** Default: chosen from the id. */
        String icon() default "";

        /** One line each, palette tokens allowed. */
        String[] about() default {};
    }

    /** How a number reads on screen. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Format {
        Style value();
    }

    /** What a number is, for {@link Format}. */
    public enum Style {
        /** A plain number. */
        NUMBER,
        /** An amount of the server's default currency. */
        MONEY,
        /** A share, written {@code 5%}. */
        PERCENT
    }

    /**
     * The bounds and the step of a number. A click never takes it out of {@code [min, max]} and a
     * typed value outside is refused. {@code none} is one more value accepted outside the range,
     * meaning off or unlimited ({@code -1} for most keys), and reads <i>None</i>. {@code step}
     * {@code 0} keeps the default: {@code 1} for a whole number, {@code 0.1} for a decimal.
     * Shift-click moves ten steps.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Range {
        double min() default -Double.MAX_VALUE;

        double max() default Double.MAX_VALUE;

        double step() default 0;

        double none() default Double.NaN;
    }

    /** Left out of the in-game screens. Still in the file and in the web editor. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Hidden {
    }
}
