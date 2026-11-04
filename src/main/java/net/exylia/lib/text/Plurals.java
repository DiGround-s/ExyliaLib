package net.exylia.lib.text;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A count and the word that goes with it ({@code 1 layer}, {@code 2 layers}), in whichever forms a language has.
 *
 * <p>A line names the count as {@code %name%} and the word as {@code %name:forms%}, the forms split by bars, so
 * each translation writes its own words and order:
 *
 * <pre>{@code
 * # messages.yml
 * slot-line: '{info}%layers% %layers:layer|layers%'      # en: 1 layer, 2 layers
 * slot-line: '{info}%layers% %layers:слой|слоя|слоёв%'   # ru: 1 слой, 3 слоя, 5 слоёв
 *
 * Plurals.apply(text.slotLine(), "layers", design.layerCount());
 * }</pre>
 *
 * <p>How many forms are written says which rule picks one: one form is the same for every count (Turkish), two
 * are one and other, the first for exactly 1 (English, Spanish, Portuguese, French...), three are one, few and
 * many as Russian and Ukrainian count them (1, 21, 31 / 2-4, 22-24 / 0, 5-20, 25-30...). A line with no
 * {@code %name:...%} only gets its count, so a server's older wording keeps working.
 *
 * @since 1.472.0
 */
public final class Plurals {

    private Plurals() {
    }

    /**
     * The form of {@code forms} ({@code "layer|layers"}) that goes with {@code count}.
     *
     * @param count the count; a negative one picks as its size does
     * @param forms one, two or three forms split by {@code |}; more than three pick from the first three
     * @return the form, stripped of the spaces round it
     */
    public static @NotNull String form(long count, @NotNull String forms) {
        String[] each = forms.split("\\|", -1);
        long n = Math.abs(count);
        int pick;
        if (each.length == 1) {
            pick = 0;
        } else if (each.length == 2) {
            pick = n == 1 ? 0 : 1;
        } else {
            long last = n % 10;
            long lastTwo = n % 100;
            pick = last == 1 && lastTwo != 11 ? 0
                    : last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 1 : 2;
        }
        return each[pick].strip();
    }

    /**
     * {@code line} with {@code %name%} made {@code count} and every {@code %name:forms%} the form that goes with it.
     *
     * @param line  the line, before it is parsed; {@code null} becomes an empty string
     * @param name  the placeholder's name, without percent signs
     * @param count the count
     * @return the line with the count and its words in it
     */
    public static @NotNull String apply(@Nullable String line, @NotNull String name, long count) {
        if (line == null) return "";
        String open = "%" + name + ":";
        StringBuilder out = new StringBuilder(line.length());
        int from = 0;
        for (int at = line.indexOf(open); at >= 0; at = line.indexOf(open, from)) {
            int end = line.indexOf('%', at + open.length());
            if (end < 0) break;
            out.append(line, from, at).append(form(count, line.substring(at + open.length(), end)));
            from = end + 1;
        }
        return out.append(line, from, line.length()).toString().replace("%" + name + "%", String.valueOf(count));
    }
}
