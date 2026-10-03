package net.exylia.lib.client;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.NotNull;

/**
 * One channel a plugin offers in a client's chat panel.
 *
 * @param name   the handle messages arrive and are posted under
 * @param label  what the tab is called
 * @param colour the tab's colour
 * @since 1.233.0
 */
public record ChatChannel(@NotNull String name, @NotNull Component label, @NotNull TextColor colour) {

    public ChatChannel {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a channel needs a name");
        }
    }
}
