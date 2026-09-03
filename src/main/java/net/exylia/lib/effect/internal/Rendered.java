package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Timer;
import net.exylia.lib.util.TimeFormats;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.text.Text;
import net.exylia.lib.text.internal.TextEngine;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Turns the text of an effect into a component, once per redraw.
 *
 * <p>This is the hot path: a countdown boss bar runs through here every tick,
 * for every player watching. Three things keep it cheap.
 *
 * <p>First, text with nothing dynamic in it is rendered once and kept. A bar
 * reading "Waiting for players" is a constant, and re-rendering it sixty times a
 * second would be pure waste.
 *
 * <p>{@code %time_formatted%} is the name ExyliaCommons wrote the same value
 * under, and it still means the same thing: a server carrying a config from
 * before the move does not have to edit it to get its countdowns back.
 *
 * <p>Second, {@code %time%} is handled here rather than being registered as a
 * global placeholder. A timer belongs to one effect, so a global registration
 * would have no way of knowing which one is being asked about, and two
 * countdowns on screen would report the same value.
 *
 * <p>Third, the timer's own text is substituted on the parsed component rather
 * than in the string, so the component is parsed once and only the number
 * changes. That is the same reason the text module substitutes after parsing.
 *
 * <p>Fourth, a plugin that already substituted its own values hands over a
 * {@link Text} rather than a string. The values ride on the component tree, so
 * the template stays the same string every redraw and its parse stays cached.
 * A bar built from {@code "Vida: 14.3"} parsed a new string every tick; one
 * built from {@code Text.of("Vida: %hp%").with("%hp%", "14.3")} parses once.
 */
final class Rendered {

    private final String raw;
    /** The text as handed over with its values, or {@code null} for a bare string. */
    private final Text base;
    private final String timeStyle;
    private final boolean dynamic;

    /**
     * Which time placeholders this text actually uses.
     *
     * <p>Worked out once, because every substitution walks the whole component
     * tree: doing all four when the text only says {@code %time%} costs three
     * walks per player per tick for nothing.
     */
    private final boolean hasTime;
    /** ExyliaCommons' name for the same value, so an old config keeps working. */
    private final boolean hasFormatted;
    private final boolean hasTotal;
    private final boolean hasElapsed;
    private final boolean hasRemaining;

    /** Held when nothing in the text can change, so it is built once. */
    private Component constant;

    /**
     * The last build, when only the timer can change the text.
     *
     * <p>A countdown redraws every tick so a decimal moves smoothly, but a
     * bar written {@code %time%s} in whole seconds reads the same for twenty
     * ticks in a row. The time is rendered first, and if it reads as it did
     * last tick the component from last tick is handed back: same instance,
     * which is what lets the display skip the packet as well.
     */
    private final boolean timerOnly;
    private String lastTime;
    private int lastGeneration;
    private Component lastBuilt;

    Rendered(String raw, String timeStyle) {
        this(raw, null, timeStyle);
    }

    Rendered(Text text, String timeStyle) {
        this(text.raw(), text, timeStyle);
    }

    private Rendered(String raw, Text base, String timeStyle) {
        this.raw = raw;
        this.base = base;
        this.timeStyle = timeStyle;
        boolean anyTime = raw.indexOf("%time") >= 0;
        this.hasTime = anyTime && raw.contains("%time%");
        this.hasFormatted = anyTime && raw.contains("%time_formatted%");
        this.hasTotal = anyTime && raw.contains("%time_total%");
        this.hasElapsed = anyTime && raw.contains("%time_elapsed%");
        this.hasRemaining = anyTime && raw.contains("%time_remaining%");
        // Asked without the timer's own tokens in the text: to the registry
        // %time% looks like any other placeholder, and the point is to know
        // whether anything *else* can change.
        boolean placeholders = Placeholders.isDynamic(anyTime ? withoutTimeTokens(raw) : raw);
        this.dynamic = anyTime || placeholders;
        this.timerOnly = anyTime && !placeholders;
    }

    private static String withoutTimeTokens(String raw) {
        return raw.replace("%time_formatted%", "")
                .replace("%time_total%", "")
                .replace("%time_elapsed%", "")
                .replace("%time_remaining%", "")
                .replace("%time%", "");
    }

    /** Returns whether this text needs rebuilding between redraws. */
    boolean isDynamic() {
        return dynamic;
    }

    /**
     * Builds the component to show.
     *
     * @param viewer who is watching, may be {@code null}
     * @param timer  the effect's timer, may be {@code null}
     * @return the component
     */
    Component build(Player viewer, Timer timer) {
        if (!dynamic) {
            Component known = constant;
            if (known == null) {
                known = base == null ? Text.component(raw) : base.build();
                constant = known;
            }
            return known;
        }

        // The time as it will read, rendered before anything else: when only
        // the timer can change this text and it reads as it did last tick,
        // last tick's component is the answer.
        String time = null;
        String total = null;
        String elapsed = null;
        String remaining = null;
        String timeKey = null;
        if (timer != null) {
            if (hasTime || hasFormatted) {
                time = TimeFormats.render(timer.displayed(), timeStyle);
            }
            if (hasTotal) {
                total = TimeFormats.render(timer.total(), timeStyle);
            }
            if (hasElapsed) {
                elapsed = TimeFormats.render(timer.elapsed(), timeStyle);
            }
            if (hasRemaining) {
                remaining = TimeFormats.render(timer.remaining(), timeStyle);
            }
            if (timerOnly) {
                timeKey = time + '|' + total + '|' + elapsed + '|' + remaining;
                // And the palette is the one it was built with: a reload
                // recolours a live countdown on its next tick, no invalidation.
                if (timeKey.equals(lastTime) && lastBuilt != null
                        && lastGeneration == TextEngine.generation()) {
                    return lastBuilt;
                }
            }
        }

        Text text = base == null ? Text.of(raw) : base;
        if (viewer != null) {
            text = text.forPlayer(viewer);
        }
        if (hasTime && time != null) {
            text = text.with("%time%", time);
        }
        if (hasFormatted && time != null) {
            text = text.with("%time_formatted%", time);
        }
        if (total != null) {
            text = text.with("%time_total%", total);
        }
        if (elapsed != null) {
            text = text.with("%time_elapsed%", elapsed);
        }
        if (remaining != null) {
            text = text.with("%time_remaining%", remaining);
        }
        Component built = text.build();
        if (timeKey != null) {
            lastTime = timeKey;
            lastGeneration = TextEngine.generation();
            lastBuilt = built;
        }
        return built;
    }

    /** Returns the text this was built from with its values, or {@code null} for a bare string. */
    Text base() {
        return base;
    }

    /** Returns the text this was built from. */
    String raw() {
        return raw;
    }

    /** Returns whether this was built from a bare string carrying no values. */
    boolean isBare() {
        return base == null;
    }

    /** Returns how this effect renders its time, so replacements keep the style. */
    String timeStyle() {
        return timeStyle;
    }
}
