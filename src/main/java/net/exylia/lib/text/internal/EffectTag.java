package net.exylia.lib.text.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the effect tag a message can begin with.
 *
 * <p>The notation is ExyliaCommons', unchanged, so existing message files
 * keep working:
 *
 * <pre>
 * [sound:ENTITY_PLAYER_LEVELUP|1.0|1.2;particle:FLAME|20;center]{success}Well done
 * </pre>
 *
 * <p>One bracketed block, only at the very start of the line. Inside it,
 * {@code ;} separates the kinds, {@code ,} separates several of one kind,
 * and {@code |} separates a kind's own arguments. Anything after the closing
 * bracket is the message.
 *
 * <p>A line that does not begin with {@code [} is returned untouched without
 * being examined, which is what keeps this free for the messages that carry
 * no effects at all.
 */
public final class EffectTag {

    private EffectTag() {
        throw new AssertionError("No instances.");
    }

    /**
     * What a message asked for.
     *
     * @param message   the text with the tag removed
     * @param sounds    sound entries, each as written
     * @param particles particle entries, each as written
     * @param fireworks firework entries, each as written
     * @param centered  whether the line asked to be centred
     */
    public record Parsed(String message,
                         List<String> sounds,
                         List<String> particles,
                         List<String> fireworks,
                         boolean centered) {

        /** Returns whether anything at all was asked for. */
        public boolean hasEffects() {
            return !sounds.isEmpty() || !particles.isEmpty() || !fireworks.isEmpty();
        }
    }

    private static final Parsed NOTHING_PREFIX = null;

    /**
     * Parses the tag at the start of a message.
     *
     * @param raw the message as written
     * @return what it asked for, and the message without the tag
     */
    public static Parsed parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) != '[') {
            return plain(raw);
        }
        int end = raw.indexOf(']');
        if (end == -1) {
            // An unclosed bracket is text, not a broken tag: "[WARN] hello"
            // must keep its bracket rather than vanish.
            return plain(raw);
        }

        String body = raw.substring(1, end);
        String message = raw.substring(end + 1);

        List<String> sounds = new ArrayList<>(2);
        List<String> particles = new ArrayList<>(2);
        List<String> fireworks = new ArrayList<>(2);
        boolean centered = false;
        boolean recognised = false;

        for (String piece : body.split(";")) {
            String trimmed = piece.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equalsIgnoreCase("center") || trimmed.equalsIgnoreCase("centered")) {
                centered = true;
                recognised = true;
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon == -1) {
                continue;
            }
            String kind = trimmed.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            String value = trimmed.substring(colon + 1).trim();

            switch (kind) {
                case "sound", "sounds" -> recognised |= addAll(value, sounds);
                case "particle", "particles" -> recognised |= addAll(value, particles);
                case "firework", "fireworks" -> recognised |= addAll(value, fireworks);
                default -> {
                    // Unknown kind: left alone, so a future tag added to a
                    // config does not silently eat the message.
                }
            }
        }

        if (!recognised) {
            // Nothing in the brackets meant anything to us, so it was never a
            // tag: "[Server] Restarting" keeps its prefix.
            return plain(raw);
        }
        return new Parsed(message, List.copyOf(sounds), List.copyOf(particles),
                List.copyOf(fireworks), centered);
    }

    private static boolean addAll(String value, List<String> into) {
        boolean any = false;
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                into.add(trimmed);
                any = true;
            }
        }
        return any;
    }

    private static Parsed plain(String raw) {
        return new Parsed(raw == null ? "" : raw,
                List.of(), List.of(), List.of(), false);
    }

    /**
     * Splits an entry into its arguments.
     *
     * @param entry an entry such as {@code ENTITY_PLAYER_LEVELUP|1.0|1.2}
     * @return the arguments, trimmed
     */
    public static String[] arguments(String entry) {
        String[] parts = entry.split("\\|");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    /**
     * Reads an argument as a number.
     *
     * @param parts    the arguments
     * @param index    which one
     * @param fallback what to use when it is missing or not a number
     * @return the value
     */
    public static double number(String[] parts, int index, double fallback) {
        if (index >= parts.length || parts[index].isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(parts[index]);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
