package net.exylia.lib.item;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What is in a potion bottle.
 *
 * <p>Only meaningful on a potion, splash potion, lingering potion or tipped
 * arrow; applied to anything else it does nothing rather than failing, because
 * a menu icon with a leftover key in it is not worth an exception.
 *
 * @param base    the vanilla potion type, such as {@code STRENGTH}, or {@code null}
 * @param colour  the bottle colour as {@code #rrggbb} or a name, or {@code null}
 * @param effects custom effects layered on top of the base
 * @since 1.22.0
 */
public record Potion(
        @Nullable String base,
        @Nullable String colour,
        @NotNull List<Effect> effects) {

    public Potion {
        effects = List.copyOf(effects);
    }

    /**
     * One custom effect on a potion.
     *
     * <p>Amplifier and duration are text rather than numbers so a placeholder
     * can decide them: a potion whose strength comes from a player's level is
     * written {@code amplifier: "%level%"} and resolved per viewer.
     *
     * @param type      the effect name, such as {@code SPEED}
     * @param amplifier the level, zero-based, as text
     * @param duration  the duration in ticks, as text
     * @param ambient   whether the particles are the faint ambient kind
     * @param particles whether particles are shown at all
     * @param icon      whether the effect icon is shown
     */
    public record Effect(
            @NotNull String type,
            @NotNull String amplifier,
            @NotNull String duration,
            boolean ambient,
            boolean particles,
            boolean icon) {

        /** An effect with vanilla's defaults for the display flags. */
        public Effect(@NotNull String type, @NotNull String amplifier, @NotNull String duration) {
            this(type, amplifier, duration, false, true, true);
        }
    }

    /** Returns whether anything here would change the bottle. */
    public boolean isEmpty() {
        return base == null && colour == null && effects.isEmpty();
    }
}
