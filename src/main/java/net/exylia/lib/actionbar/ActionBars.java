package net.exylia.lib.actionbar;

import net.exylia.lib.actionbar.internal.ActionBarRuntime;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * One action bar per player, shared by every plugin that has something to say
 * there.
 *
 * <pre>{@code
 * ActionBars.register(this, "cooldowns", 100, player -> {
 *     Duration left = ItemCooldowns.remaining(player, "pearl");
 *     return left.isZero() ? null : Text.component("{info}Pearl " + TimeFormats.render(left) + " ⌚");
 * });
 *
 * // something changed and should not wait for the next refresh
 * ActionBars.refresh(player);
 * }</pre>
 *
 * <h2>What this replaces</h2>
 * The action bar holds one line, and the last plugin to write it wins. Two
 * plugins each running their own timer — a combat tag in one, item cooldowns in
 * another — take turns overwriting each other, and the player sees the line
 * flicker between them. Here every plugin hands over a <em>segment</em>, and the
 * library draws them on one line, in order, with a separator between them.
 *
 * <h2>Segments</h2>
 * A segment is asked for its text every refresh (every two ticks) on the
 * player's own thread, so it reads memory and nothing slower. Returning
 * {@code null} or an empty component means "nothing to show": a line with no
 * segments left is cleared once and then left alone, so a plugin outside this
 * module can still use the action bar while nobody here has anything to say.
 * A segment that throws is skipped for that refresh and logged once.
 *
 * <p>Lower {@code order} values are drawn first. Registering the same id again
 * replaces the segment in place.
 *
 * <h2>Lifecycle</h2>
 * Segments are dropped when the plugin that registered them is disabled. Nothing
 * runs while no segment is registered.
 *
 * <h2>Threading</h2>
 * Every method is safe from any thread.
 *
 * @since 1.323.0
 */
public final class ActionBars {

    private ActionBars() {
        throw new AssertionError("No instances.");
    }

    /**
     * Adds a segment to every player's action bar.
     *
     * @param plugin  the plugin it belongs to
     * @param id      its id within that plugin
     * @param order   where it sits on the line, lower first
     * @param segment what it shows a player, {@code null} for nothing
     */
    public static void register(@NotNull Plugin plugin, @NotNull String id, int order,
                                @NotNull Function<Player, @Nullable Component> segment) {
        ActionBarRuntime.register(plugin.getName(), id, order, segment);
    }

    /**
     * Removes a segment.
     *
     * @param plugin the plugin it belongs to
     * @param id     its id within that plugin
     */
    public static void unregister(@NotNull Plugin plugin, @NotNull String id) {
        ActionBarRuntime.unregister(plugin.getName(), id);
    }

    /**
     * Redraws a player's action bar now instead of on the next refresh.
     *
     * @param player the player
     */
    public static void refresh(@NotNull Player player) {
        ActionBarRuntime.refresh(player);
    }
}
