package net.exylia.lib.hud;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A short message that slides onto the HUD, stays a few seconds and slides away: "+120 coins", "You killed Steve".
 *
 * <pre>{@code
 * huds.notify(player, HudNotice.of(Text.of("{success}+120 {letters}coins").build()).group("coins"));
 * }</pre>
 *
 * <p>A notice with a {@code group} that arrives while another of the same group is still up replaces it and counts:
 * three kills in a row read {@code ×3} on one line, and its time starts again. Past {@link #MAX_SHOWN} at one anchor the
 * oldest leaves early.
 *
 * @param text    what it says
 * @param anchor  where its stack sits
 * @param seconds how long it stays
 * @param group   what repeats merge on; {@code null} never merges
 * @param scale   its size against the HUD's default text size
 * @param big     a centre-screen moment ("DOUBLE KILL"): drawn large, popping in and out, one at a time
 * @since 1.566.0
 */
public record HudNotice(@NotNull Component text, @NotNull HudAnchor anchor, double seconds, @Nullable String group,
                        double scale, boolean big) {

    /** The most notices one anchor shows at once. */
    public static final int MAX_SHOWN = 5;

    public HudNotice {
        seconds = Math.clamp(seconds, 0.5, 60);
        scale = Math.clamp(scale, 0.25, 4);
    }

    /** A notice at the top right for three seconds. */
    public static @NotNull HudNotice of(@NotNull Component text) {
        return new HudNotice(text, HudAnchor.TOP_RIGHT, 3, null, 1, false);
    }

    /** A big moment over the crosshair for two seconds: drawn at twice the size, one at a time. */
    public static @NotNull HudNotice moment(@NotNull Component text) {
        return new HudNotice(text, HudAnchor.CENTER, 2, "moment", 2, true);
    }

    public @NotNull HudNotice anchor(@NotNull HudAnchor where) {
        return new HudNotice(text, where, seconds, group, scale, big);
    }

    public @NotNull HudNotice seconds(double time) {
        return new HudNotice(text, anchor, time, group, scale, big);
    }

    public @NotNull HudNotice group(@Nullable String key) {
        return new HudNotice(text, anchor, seconds, key, scale, big);
    }

    public @NotNull HudNotice scale(double size) {
        return new HudNotice(text, anchor, seconds, group, size, big);
    }
}
