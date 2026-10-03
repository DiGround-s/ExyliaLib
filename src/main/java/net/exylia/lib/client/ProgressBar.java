package net.exylia.lib.client;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.NotNull;

/**
 * A labelled progress bar at the top of the client's HUD.
 *
 * <p>Show it again with the same name to move it; the client animates the
 * change. Only clients that have HUD bars draw one, so a plugin keeps its boss
 * bar for everyone else.
 *
 * @param name     the handle it is removed by
 * @param label    what is written on it
 * @param progress how full it is, from 0 to 1
 * @param colour   its colour
 * @since 1.233.0
 */
public record ProgressBar(@NotNull String name, @NotNull Component label, float progress,
                          @NotNull TextColor colour) implements ClientElement {

    public ProgressBar {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a progress bar needs a name");
        }
        progress = Float.isFinite(progress) ? Math.max(0f, Math.min(1f, progress)) : 0f;
    }
}
