package net.exylia.lib.client;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A key a plugin adds to the client's controls screen.
 *
 * @param name       the handle a press arrives under
 * @param label      what the controls screen calls it
 * @param defaultKey a Minecraft key name such as {@code key.keyboard.k}, or
 *                   {@code null} to leave it unbound until the player picks one
 * @since 1.233.0
 */
public record Keybind(@NotNull String name, @NotNull Component label, @Nullable String defaultKey) {

    public Keybind {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a keybind needs a name");
        }
        if (defaultKey != null && !defaultKey.startsWith("key.keyboard.") && !defaultKey.startsWith("key.mouse.")) {
            throw new IllegalArgumentException("defaultKey must be a Minecraft key name such as key.keyboard.k");
        }
    }
}
