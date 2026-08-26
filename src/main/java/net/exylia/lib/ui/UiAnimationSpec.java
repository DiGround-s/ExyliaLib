package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;

/**
 * How a menu appears when it opens.
 *
 * <p>The whole point is that the short form is enough, and it is what every
 * deployed file writes:
 *
 * <pre>
 * animation: center_out
 * </pre>
 *
 * <p>The long form only adds the one thing worth changing:
 *
 * <pre>
 * animation:
 *   type: rows_alternate
 *   speed: 3
 * </pre>
 *
 * <p>No frame lists, no timelines and no easing curves in configuration. An
 * admin picks a name; the library knows what that looks like.
 *
 * <p>There is deliberately no {@code direction}, {@code loop} or {@code stagger}.
 * They were here, no file has ever written one, and nothing could have read
 * them: an option that is parsed and ignored is worse than one that does not
 * exist, because it reads as supported.
 *
 * @param type  which animation, by name
 * @param speed how many ticks between frames, at least one
 * @since 1.22.0
 */
public record UiAnimationSpec(@NotNull String type, int speed) {

    public UiAnimationSpec {
        type = type.trim().toLowerCase(Locale.ROOT);
        // An empty name is how a file says "no animation", not a typo.
        if (type.isEmpty()) {
            type = "none";
        }
        speed = Math.max(1, speed);
    }

    /** The short form: just a name. */
    public static @NotNull UiAnimationSpec of(@NotNull String type) {
        return new UiAnimationSpec(type, 2);
    }

    /**
     * The long form, from a configuration section read as a map.
     *
     * @param values the section's values
     * @return the specification
     */
    public static @NotNull UiAnimationSpec of(@NotNull Map<String, Object> values) {
        return new UiAnimationSpec(
                String.valueOf(values.getOrDefault("type", "none")),
                asInt(values.get("speed"), 2));
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
