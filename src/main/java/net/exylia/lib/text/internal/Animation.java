package net.exylia.lib.text.internal;

import net.exylia.lib.text.Gradients;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Colour animations written into text: {@code <animation:scroll:{primary}:{secondary_light}:2s>EXYLIA</animation>}.
 *
 * <p>Kinds: {@code scroll} moves a looping gradient across the letters,
 * {@code pulse} fades the whole text through the colours. The duration (one
 * full loop, {@code 2s} by default) is optional and may sit anywhere after the
 * kind.
 *
 * <p>The frame comes from the clock, not from how often the text is drawn, so
 * every redraw shows where the loop is now: a board refreshing every tick moves
 * smoothly, one refreshing every second moves in steps, and a hiccup in the
 * scheduler never makes it jump back. Before a parse, {@link #frame} stamps each
 * tag with the current frame, so the cache holds one entry per frame (a tick's
 * worth of time) rather than freezing the first one.
 */
public final class Animation {

    /** The label a run is tagged with until {@link Paint#animate} paints it. */
    static final String PREFIX = "anim/";

    private static final String NAME = "animation";
    private static final String OPEN = "<animation:";
    private static final long STEP_MILLIS = 50;
    private static final long DEFAULT_PERIOD = 2000;
    private static final int RESOLUTION = 10_000;

    static final TagResolver RESOLVER = new TagResolver() {
        @Override
        public @Nullable Tag resolve(String name, ArgumentQueue arguments, Context context) throws ParsingException {
            if (!NAME.equals(name) || !arguments.hasNext()) {
                return null;
            }
            String kind = arguments.pop().value().toLowerCase(Locale.ROOT);
            if (!kind.equals("scroll") && !kind.equals("pulse")) {
                return null;
            }
            List<String> hexes = new ArrayList<>();
            long period = DEFAULT_PERIOD;
            long frame = 0;
            while (arguments.hasNext()) {
                String argument = arguments.pop().value();
                long millis = millis(argument);
                if (argument.startsWith("@")) {
                    frame = parseLong(argument.substring(1));
                } else if (millis > 0) {
                    period = millis;
                } else {
                    TextColor colour = Paint.colour(argument);
                    if (colour != null) {
                        hexes.add(String.format(Locale.ROOT, "%06x", colour.value()));
                    }
                }
            }
            if (hexes.isEmpty()) {
                return null;
            }
            long offset = (frame * STEP_MILLIS % period) * RESOLUTION / period;
            Key key = Key.key("exylia", PREFIX + kind + "/" + String.join("-", hexes) + "/" + offset);
            return Tag.styling(builder -> builder.font(key));
        }

        @Override
        public boolean has(String name) {
            return NAME.equals(name);
        }
    };

    private Animation() {
    }

    /** Whether the text holds an animation. */
    public static boolean has(String text) {
        return text.contains(OPEN);
    }

    /**
     * Stamps every animation in the text with the frame showing now.
     *
     * <p>Two texts that come out equal show the same frame, which is how a
     * board tells whether a line needs sending again.
     *
     * @param text the text
     * @return the text with each tag stamped, or the same text when it holds none
     */
    public static String frame(String text) {
        return frame(text, System.currentTimeMillis());
    }

    static String frame(String text, long now) {
        int open = text.indexOf(OPEN);
        if (open < 0) {
            return text;
        }
        StringBuilder result = new StringBuilder(text.length() + 8);
        int copied = 0;
        while (open >= 0) {
            int end = text.indexOf('>', open);
            if (end < 0) {
                break;
            }
            String arguments = text.substring(open + OPEN.length(), end);
            if (arguments.contains(":@")) {
                // Already stamped: a parse that calls another must not move the frame.
                open = text.indexOf(OPEN, end);
                continue;
            }
            long period = DEFAULT_PERIOD;
            for (String argument : arguments.split(":")) {
                long millis = millis(argument);
                if (millis > 0) {
                    period = millis;
                }
            }
            long frames = Math.max(1, period / STEP_MILLIS);
            result.append(text, copied, end).append(":@").append(now / STEP_MILLIS % frames);
            copied = end;
            open = text.indexOf(OPEN, end);
        }
        return result.append(text, copied, text.length()).toString();
    }

    /** Paints an unlabelled run at the frame its label names. */
    static Component paint(Component run, String key) {
        String[] parts = key.substring(PREFIX.length()).split("/");
        List<TextColor> stops = new ArrayList<>();
        for (String hex : parts[1].split("-")) {
            TextColor colour = TextColor.fromHexString('#' + hex);
            if (colour != null) {
                stops.add(colour);
            }
        }
        if (stops.isEmpty()) {
            return run;
        }
        double offset = parseLong(parts[2]) / (double) RESOLUTION;
        if (parts[0].equals("pulse")) {
            TextColor colour = Gradients.wrap(stops, offset);
            return Gradients.apply(run, index -> colour);
        }
        int length = Math.max(1, Gradients.length(run));
        return Gradients.apply(run, index -> Gradients.wrap(stops, (double) index / length - offset));
    }

    /** A duration such as {@code 2s} or {@code 1500ms}, or {@code 0} when it is not one. */
    static long millis(String argument) {
        String value = argument.toLowerCase(Locale.ROOT);
        if (value.endsWith("ms")) {
            return parseLong(value.substring(0, value.length() - 2));
        }
        if (value.endsWith("s")) {
            try {
                return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) * 1000);
            } catch (NumberFormatException exception) {
                return 0;
            }
        }
        return 0;
    }

    private static long parseLong(String value) {
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
