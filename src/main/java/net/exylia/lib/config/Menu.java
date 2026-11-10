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

        /**
         * For {@link Style#MONEY}: the key holding the currency id the amount is in (a
         * sibling, or dotted from the root), so a bazaar priced in gems reads in gems and
         * its amounts are typed in gems. Empty: the server's default currency. Since 1.562.0.
         */
        String currency() default "";
    }

    /** What a number is, for {@link Format}. */
    public enum Style {
        /** A plain number. */
        NUMBER,
        /** An amount of the server's default currency. */
        MONEY,
        /** A share, written {@code 5%}. */
        PERCENT,
        /**
         * A share held as a fraction of one: {@code 0.25} reads {@code 25%}. The file and a
         * typed value stay fractions. Since 1.451.0.
         */
        FRACTION,
        /** A factor: {@code 1.25} reads {@code ×1.25}. Since 1.451.0. */
        MULTIPLIER
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

        /**
         * A common range, so dozens of keys do not each spell it out. {@code min}, {@code max}
         * and {@code step} written beside it win over the preset's. Since 1.451.0.
         */
        Preset preset() default Preset.NONE;
    }

    /** A common {@link Range}. Since 1.451.0. */
    public enum Preset {
        /** No preset: the range is what {@link Range} says. */
        NONE(-Double.MAX_VALUE, Double.MAX_VALUE, 0),
        /** Zero or more. */
        POSITIVE(0, Double.MAX_VALUE, 0),
        /** {@code 0} to {@code 100}, one at a time: points, percentages, levels. */
        HUNDRED(0, 100, 1),
        /** {@code 0} to {@code 1} in steps of {@code 0.05}: a share held as a fraction. */
        FRACTION(0, 1, 0.05),
        /** {@code 0} to {@code 100} in steps of {@code 0.1}: a factor. */
        MULTIPLIER(0, 100, 0.1);

        private final double min;
        private final double max;
        private final double step;

        Preset(double min, double max, double step) {
            this.min = min;
            this.max = max;
            this.step = step;
        }

        /** The lowest value. */
        public double min() {
            return min;
        }

        /** The highest value. */
        public double max() {
            return max;
        }

        /** One click's step; {@code 0} keeps the default. */
        public double step() {
            return step;
        }
    }

    /**
     * Text that holds one of a fixed set of values: cycled like an enum instead of typed. A value
     * the file holds outside the set is kept and shown as written until a click moves it on.
     * Since 1.451.0.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Choices {
        /** The values, as the file writes them. */
        String[] value();

        /** What each is called on screen, in the same order; default: the value in words. */
        String[] labels() default {};
    }

    /**
     * A plugin's {@code language} key: cycles through {@code default} and the languages Exylia
     * plugins ship. Since 1.451.0.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Language {
    }

    /**
     * Text that names a currency by id, picked from the currencies the server has (an empty
     * value is the default currency). Since 1.562.0.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Currency {
    }

    /**
     * Text that names an item: picked with the icon picker and written in the case the file
     * already uses. Since 1.451.0. On a list of text (since 1.562.0) each line is an item:
     * a click adds one with the picker, removes one, or edits the list as text.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Material {
    }

    /**
     * A sound written {@code NAME|volume|pitch}: clicks walk a short list of quiet interface
     * sounds, each played to the admin as it is chosen, keeping the volume and pitch already
     * written; Q types any sound. Since 1.451.0.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Sound {
    }

    /**
     * The unit a plain number is written in, shown beside it; the file is unchanged. A unit of
     * time ({@code ms}, {@code t}, {@code s}, {@code min}, {@code h}, {@code d}, {@code w}) reads
     * as a length of time, {@code 90} minutes as {@code 1h 30m ⌚}. Since 1.451.0.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Unit {
        String value();
    }

    /**
     * A number that may not go below another key of the same file: a change that would is
     * refused and the admin told why. The key is a sibling in the same section, or a dotted key
     * from the file's root. Annotate the other key with {@link AtMost} too, so changing either
     * keeps the order. Since 1.451.0.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface AtLeast {
        String value();
    }

    /** A number that may not go above another key of the same file; see {@link AtLeast}. Since 1.451.0. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface AtMost {
        String value();
    }

    /** Left out of the in-game screens. Still in the file and in the web editor. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Hidden {
    }
}
