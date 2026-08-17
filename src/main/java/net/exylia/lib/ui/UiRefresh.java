package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;

/**
 * When a menu redraws itself.
 *
 * <pre>
 * refresh:
 *   mode: SMART
 *   interval: 20
 *   click_delay: 2
 * </pre>
 *
 * <p>A hundred and sixty-one deployed menus declare this, so the names are the
 * ones already written.
 *
 * @param mode       when to redraw
 * @param interval   ticks between timed redraws, at least one
 * @param clickDelay ticks to wait after a click before redrawing what it touched
 * @since 1.22.0
 */
public record UiRefresh(@NotNull Mode mode, int interval, int clickDelay) {

    /** Menus that say nothing redraw only when told. */
    public static final UiRefresh NEVER = new UiRefresh(Mode.DISABLED, 20, 0);

    public UiRefresh {
        interval = Math.max(1, interval);
        clickDelay = Math.max(0, clickDelay);
    }

    /** When a menu redraws. */
    public enum Mode {
        /** Only when a plugin asks. */
        DISABLED,
        /** Everything, on a timer. */
        FULL,
        /** On a timer, but only the slots that can actually change. */
        SMART,
        /** Only the slot that was clicked, after the click. */
        ON_CLICK;

        /** Reads a mode as written, falling back to doing nothing. */
        public static @NotNull Mode byName(String name) {
            if (name == null) {
                return DISABLED;
            }
            return switch (name.trim().toUpperCase(Locale.ROOT)) {
                case "FULL" -> FULL;
                case "SMART" -> SMART;
                case "ON_CLICK", "ON-CLICK", "CLICK" -> ON_CLICK;
                // SLOT_ONLY existed and behaved as SMART does here.
                case "SLOT_ONLY", "SLOT-ONLY" -> SMART;
                default -> DISABLED;
            };
        }
    }

    /**
     * Reads a refresh block.
     *
     * @param values the section's values
     * @return what it asked for
     */
    public static @NotNull UiRefresh of(@NotNull Map<String, Object> values) {
        return new UiRefresh(
                Mode.byName(asString(values.get("mode"))),
                asInt(values.get("interval"), 20),
                asInt(values.get("click_delay"), asInt(values.get("click-delay"), 0)));
    }

    /** Returns whether this menu redraws on a timer. */
    public boolean isTimed() {
        return mode == Mode.FULL || mode == Mode.SMART;
    }

    /** Returns whether a click should redraw the slot it touched. */
    public boolean isOnClick() {
        return mode == Mode.ON_CLICK || mode == Mode.SMART;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException notANumber) {
                return fallback;
            }
        }
        return fallback;
    }
}
