package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.text.Colors;
import org.bukkit.Color;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The arguments of one token, read once when the sequence is compiled.
 *
 * <p>A token is {@code NAME;key:value;key:value}. The first segment is
 * positional &mdash; a particle, a sound, a material &mdash; and the rest are
 * named.
 *
 * <h2>Why a map rather than a chain of startsWith</h2>
 * ExyliaCommons matched each parameter with a chain of {@code startsWith} on
 * every execution, so a twenty-token kill effect re-split and re-scanned its
 * strings every time somebody died. Splitting once at compile time turns that
 * into a hash lookup that happens before the server is even running.
 *
 * <p>It also makes an unknown parameter visible. A chain of {@code else if}
 * silently ignores {@code raduis:2}, and a typo that does nothing is the
 * hardest kind to find in a file nobody reads.
 */
public final class Args {

    private final String head;
    private final Map<String, String> values;

    private Args(String head, Map<String, String> values) {
        this.head = head;
        this.values = values;
    }

    /**
     * Reads {@code NAME;key:value;...} into its parts.
     *
     * @param raw       the argument text, already stripped of its {@code [TOKEN]}
     * @param problems  where unknown-looking segments are reported
     * @return the parsed arguments
     */
    public static @NotNull Args parse(@NotNull String raw, @NotNull Problems problems) {
        String[] parts = raw.split(";");
        String head = parts.length > 0 ? parts[0].trim() : "";
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                continue;
            }
            int colon = part.indexOf(':');
            if (colon <= 0) {
                // A positional extra, which only [SOUND] and [POTION] have. Kept
                // under its index so those tokens can read it without a name.
                values.put("#" + i, part);
                continue;
            }
            values.put(part.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    part.substring(colon + 1).trim());
        }
        return new Args(head, values);
    }

    /** The first segment: the particle, sound or material name. */
    public @NotNull String head() {
        return head;
    }

    /**
     * The same arguments, read as a token that has no head.
     *
     * <p>{@code [FIREWORK] color:red;fade:orange} is how every file in the
     * ecosystem writes it, and the first segment of a line is positional, so
     * {@code color:red} was being read as the head and thrown away: every
     * firework in every effect came out the default colour. The three headless
     * tokens read their arguments through this instead, which folds that first
     * segment back in where it belongs.
     *
     * <p>Only they do, because a head that contains a colon is meaningful
     * elsewhere &mdash; {@code [SOUND] minecraft:block.note_block.pling} is a
     * sound, not a parameter called {@code minecraft}.
     *
     * @return the arguments with the head read as a named value
     */
    public @NotNull Args asHeadless() {
        int colon = head.indexOf(':');
        if (colon <= 0) {
            return this;
        }
        Map<String, String> folded = new LinkedHashMap<>();
        folded.put(head.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                head.substring(colon + 1).trim());
        folded.putAll(values);
        return new Args("", folded);
    }

    /** Whether the head is missing, which makes the whole token meaningless. */
    public boolean headless() {
        return head.isEmpty();
    }

    /** A positional argument by its index, or {@code null}. */
    public @Nullable String positional(int index) {
        return values.get("#" + index);
    }

    /** Whether a named parameter was given. */
    public boolean has(@NotNull String key) {
        return values.containsKey(key);
    }

    /** A named parameter as text, or {@code fallback}. */
    public @NotNull String text(@NotNull String key, @NotNull String fallback) {
        String value = values.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /**
     * A named parameter as a number.
     *
     * <p>An unreadable number is reported rather than silently replaced:
     * {@code radius:tow} meant something, and falling back without a word makes
     * the file look like it worked.
     */
    public double number(@NotNull String key, double fallback, @NotNull Problems problems) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException malformed) {
            problems.found(key, "\"" + value + "\" is not a number, using " + fallback);
            return fallback;
        }
    }

    /** A named parameter as a whole number. */
    public int count(@NotNull String key, int fallback, @NotNull Problems problems) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException malformed) {
            problems.found(key, "\"" + value + "\" is not a whole number, using " + fallback);
            return fallback;
        }
    }

    /**
     * A named parameter as a count that must be at least one.
     *
     * <p>Zero points draws nothing and a negative count throws deep inside an
     * array allocation, so both are caught here where the file can be named.
     */
    public int atLeastOne(@NotNull String key, int fallback, @NotNull Problems problems) {
        int value = count(key, fallback, problems);
        if (value < 1) {
            problems.found(key, value + " is not enough, using 1");
            return 1;
        }
        return value;
    }

    /** A named parameter as a flag. */
    public boolean flag(@NotNull String key, boolean fallback) {
        String value = values.get(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    /**
     * A colour, as {@code R,G,B}, as {@code #rrggbb}, or as a palette token.
     *
     * <p>The palette forms are new. ExyliaCommons only understood decimal
     * triples, so every coloured effect in the ecosystem hardcodes a colour the
     * server owner cannot recolour &mdash; the exact problem {@code colors.yml}
     * exists to solve. {@code color:{primary}} now follows the palette like
     * everything else a player sees.
     *
     * @return the colour, or {@code fallback} when absent or unreadable
     */
    public @Nullable Color colour(@NotNull String key, @Nullable Color fallback,
                                  @NotNull Problems problems) {
        String value = values.get(key);
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        if (value.startsWith("{") && value.endsWith("}")) {
            String token = value.substring(1, value.length() - 1);
            net.kyori.adventure.text.format.TextColor palette = Colors.get(token, null);
            if (palette != null) {
                return Color.fromRGB(palette.value());
            }
            problems.found(key, "there is no palette colour called \"" + token + "\"");
            return fallback;
        }
        if (value.startsWith("#")) {
            try {
                return Color.fromRGB(Integer.parseInt(value.substring(1), 16));
            } catch (IllegalArgumentException malformed) {
                problems.found(key, "\"" + value + "\" is not a hex colour");
                return fallback;
            }
        }
        String[] rgb = value.split(",");
        if (rgb.length < 3) {
            problems.found(key, "\"" + value + "\" is not R,G,B, #rrggbb or a {palette} token");
            return fallback;
        }
        try {
            return Color.fromRGB(
                    Integer.parseInt(rgb[0].trim()),
                    Integer.parseInt(rgb[1].trim()),
                    Integer.parseInt(rgb[2].trim()));
        } catch (IllegalArgumentException malformed) {
            problems.found(key, "\"" + value + "\" is not a colour between 0 and 255");
            return fallback;
        }
    }

    /**
     * Reports the named parameters nothing asked for.
     *
     * <p>Called by each step once it has read everything it understands, so a
     * misspelled parameter is named at load instead of doing nothing forever.
     *
     * @param problems where to report
     * @param known    every parameter this token understands
     */
    public void reportUnknown(@NotNull Problems problems, @NotNull String... known) {
        for (String key : values.keySet()) {
            if (key.startsWith("#")) {
                continue;
            }
            boolean recognised = false;
            for (String candidate : known) {
                if (candidate.equals(key)) {
                    recognised = true;
                    break;
                }
            }
            if (!recognised) {
                problems.found(key, "is not a parameter this effect understands");
            }
        }
    }

    /** Where a malformed argument is reported. */
    @FunctionalInterface
    public interface Problems {
        /**
         * One bad argument.
         *
         * @param where   the parameter it came from
         * @param problem what is wrong with it
         */
        void found(@NotNull String where, @NotNull String problem);
    }
}
