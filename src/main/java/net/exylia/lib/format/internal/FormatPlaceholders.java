package net.exylia.lib.format.internal;

import net.exylia.lib.format.Formats;
import net.exylia.lib.format.Numbers;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.Request;
import org.bukkit.plugin.Plugin;

/**
 * The format module, reachable from a config file without writing Java.
 *
 * <pre>
 *   %exylia_money_1250%             $1,250.00
 *   %exylia_compact_1500%           1.5K
 *   %exylia_percent_75%             75%
 *   %exylia_ordinal_3%              3rd
 *   %exylia_relative_1755400000000% 3d ago
 *   %exylia_date_1755400000000%     17/08/2026
 * </pre>
 *
 * <p>The argument is almost never a literal in practice — it is another
 * placeholder that has already been substituted, which is how a menu written by
 * a server owner formats a number a plugin produced:
 *
 * <pre>{@code
 * lore:
 *   - '{letters}Balance: {highlight}%exylia_money_%vault_eco_balance%%'
 * }</pre>
 *
 * <p>Registered once by ExyliaLib, next to the other built-ins, so every
 * plugin's configuration can reach them.
 *
 * <h2>Why these and not a formatting suffix</h2>
 * The placeholder module already has {@code %name:compact%}, and it stays: it
 * formats a value a resolver produced. These are for the other direction — a
 * number that is already text, from another plugin's placeholder or typed into a
 * config, that needs to come out looking like this server's money. A suffix
 * cannot do that, because by the time the text exists there is no resolver left
 * to attach one to.
 *
 * <h2>Async</h2>
 * Marked async safe, unlike the rest of the built-ins. These read a number out
 * of their own argument and an immutable settings object; they touch no Bukkit
 * state at all, so a scoreboard rendering off the main thread can use them
 * without the claim ever coming back as a crash.
 */
public final class FormatPlaceholders {

    private FormatPlaceholders() {
    }

    /**
     * Registers the format placeholders.
     *
     * @param plugin ExyliaLib itself, which owns them
     */
    public static void register(Plugin plugin) {
        Placeholders.group(plugin, "exylia")
                .describe("Numbers and dates in this server's configured formats")
                .async()
                .add("money", request -> whenNumeric(request, Formats::money))
                .add("compact", request -> whenNumeric(request, Formats::compact))
                .add("percent", request -> whenNumeric(request, Formats::percent))
                .add("ordinal", request -> whenWhole(request, Numbers::ordinal))
                .add("relative", request -> whenWhole(request, Formats::relative))
                .add("date", request -> whenWhole(request, Formats::date))
                .register();
    }

    /**
     * Applies a formatter to the argument, when the argument is a number.
     *
     * <p>Returns {@code null} rather than a zero when it is not. That is what
     * the placeholder module treats as "no value", so the config's own fallback
     * applies — {@code %exylia_money_%eco_balance%|0%} — and if there is none,
     * the placeholder stays visible on screen. Rendering an unparsed
     * {@code %eco_balance%} as {@code "$0.00"} would look exactly like a player
     * with no money, and the missing plugin behind it would go unnoticed for
     * as long as nobody happened to be rich.
     */
    private static String whenNumeric(Request request, java.util.function.DoubleFunction<String> format) {
        String raw = request.arg(0, "");
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return format.apply(Double.parseDouble(raw));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Applies a formatter to the argument, when the argument is a whole number.
     *
     * <p>Separate from {@link #whenNumeric} because a timestamp does not survive
     * a {@code double}: a millisecond stamp is sixteen digits and a
     * {@code double} stops counting in ones past about nine quadrillion, so
     * parsing one that way can move a date by a second or two for no reason a
     * reader could ever work out.
     */
    private static String whenWhole(Request request, java.util.function.LongFunction<String> format) {
        String raw = request.arg(0, "");
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return format.apply(Long.parseLong(raw));
        } catch (NumberFormatException notAWholeNumber) {
            return null;
        }
    }
}
