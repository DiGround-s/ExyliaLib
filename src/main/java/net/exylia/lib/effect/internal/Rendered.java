package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Timer;
import net.exylia.lib.util.TimeFormats;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.text.Text;
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
 * <p>Second, {@code %time%} is handled here rather than being registered as a
 * global placeholder. A timer belongs to one effect, so a global registration
 * would have no way of knowing which one is being asked about, and two
 * countdowns on screen would report the same value.
 *
 * <p>Third, the timer's own text is substituted on the parsed component rather
 * than in the string, so the component is parsed once and only the number
 * changes. That is the same reason the text module substitutes after parsing.
 */
final class Rendered {

    private final String raw;
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
    private final boolean hasTotal;
    private final boolean hasElapsed;
    private final boolean hasRemaining;

    /** Held when nothing in the text can change, so it is built once. */
    private Component constant;

    Rendered(String raw, String timeStyle) {
        this.raw = raw;
        this.timeStyle = timeStyle;
        boolean anyTime = raw.indexOf("%time") >= 0;
        this.hasTime = anyTime && raw.contains("%time%");
        this.hasTotal = anyTime && raw.contains("%time_total%");
        this.hasElapsed = anyTime && raw.contains("%time_elapsed%");
        this.hasRemaining = anyTime && raw.contains("%time_remaining%");
        this.dynamic = anyTime || Placeholders.isDynamic(raw);
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
                known = Text.component(raw);
                constant = known;
            }
            return known;
        }

        Text text = Text.of(raw);
        if (viewer != null) {
            text = text.forPlayer(viewer);
        }
        if (timer != null) {
            if (hasTime) {
                text = text.with("%time%", TimeFormats.render(timer.displayed(), timeStyle));
            }
            if (hasTotal) {
                text = text.with("%time_total%", TimeFormats.render(timer.total(), timeStyle));
            }
            if (hasElapsed) {
                text = text.with("%time_elapsed%", TimeFormats.render(timer.elapsed(), timeStyle));
            }
            if (hasRemaining) {
                text = text.with("%time_remaining%", TimeFormats.render(timer.remaining(), timeStyle));
            }
        }
        return text.build();
    }

    /** Returns the text this was built from. */
    String raw() {
        return raw;
    }

    /** Returns how this effect renders its time, so replacements keep the style. */
    String timeStyle() {
        return timeStyle;
    }
}
