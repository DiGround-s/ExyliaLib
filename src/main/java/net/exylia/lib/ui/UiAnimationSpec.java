package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;

/**
 * An animation, as a server owner writes it.
 *
 * <p>The whole point is that the short form is enough:
 *
 * <pre>
 * animation: pulse
 * </pre>
 *
 * <p>and the long form only adds what somebody actually wants to change:
 *
 * <pre>
 * animation:
 *   type: wave
 *   speed: 2
 *   direction: left-to-right
 *   loop: true
 *   stagger: 1
 * </pre>
 *
 * <p>No frame lists, no timelines and no easing curves in configuration. An
 * admin picks a name; the library knows what that looks like.
 *
 * @param type      which animation, by name
 * @param speed     how many ticks between frames, at least one
 * @param direction which way it travels, for the ones that travel
 * @param loop      whether it repeats or plays once
 * @param stagger   ticks of offset between neighbouring slots
 * @since 1.22.0
 */
public record UiAnimationSpec(@NotNull String type, int speed, @NotNull String direction,
                              boolean loop, int stagger) {

    public UiAnimationSpec {
        type = type.trim().toLowerCase(Locale.ROOT);
        speed = Math.max(1, speed);
        direction = direction.trim().toLowerCase(Locale.ROOT);
        stagger = Math.max(0, stagger);
    }

    /** The short form: just a name. */
    public static @NotNull UiAnimationSpec of(@NotNull String type) {
        return new UiAnimationSpec(type, 2, "forward", true, 0);
    }

    /**
     * The long form, from a configuration section read as a map.
     *
     * @param values the section's values
     * @return the specification
     */
    public static @NotNull UiAnimationSpec of(@NotNull Map<String, Object> values) {
        String type = String.valueOf(values.getOrDefault("type", "pulse"));
        return new UiAnimationSpec(
                type,
                asInt(values.get("speed"), 2),
                String.valueOf(values.getOrDefault("direction", "forward")),
                asBoolean(values.get("loop")),
                asInt(values.get("stagger"), 0));
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

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return !text.equalsIgnoreCase("false");
        }
        // Looping is what somebody writing "animation: pulse" expects.
        return true;
    }
}
