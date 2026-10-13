package net.exylia.lib.interaction;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * What a plugin's own {@code [TOKEN]} does when an interaction line reaches it.
 *
 * <pre>{@code
 * Interactions.token(this, "SAY", (player, click, argument, data) ->
 *         bubbles.say(npcs.get((String) data.get("npc_id")), argument));
 * }</pre>
 *
 * <p>Called on the clicking player's thread, after the lines before it and
 * before the lines after it.
 *
 * @since 1.291.0
 */
@FunctionalInterface
public interface InteractionToken {

    /**
     * Runs one line.
     *
     * @param player   who clicked
     * @param click    how they clicked
     * @param argument everything after {@code [TOKEN]}, trimmed, with
     *                 placeholders and the {@code data} values filled in
     * @param data     the values the interaction was run with
     */
    void run(@NotNull Player player, @NotNull Click click, @NotNull String argument,
             @NotNull Map<String, Object> data);
}
