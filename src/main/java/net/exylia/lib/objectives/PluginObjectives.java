package net.exylia.lib.objectives;

import net.exylia.lib.objectives.internal.ObjectiveRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One plugin's objective subscriptions. Get it from {@link Objectives#of}.
 *
 * @since 1.620.0
 */
public final class PluginObjectives {

    private final Plugin plugin;

    PluginObjectives(Plugin plugin) {
        this.plugin = plugin;
    }

    /** The plugin these belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * Calls {@code handler} whenever a player does something of this type and
     * {@code wants} says this plugin cares about that player now.
     *
     * <pre>{@code
     * Objectives.of(this).listen(ObjectiveType.BREAK_BLOCK,
     *         player -> missions.has(ObjectiveType.BREAK_BLOCK, player.getUniqueId()),
     *         progress -> { if (!progress.placedByPlayer()) missions.count(progress); });
     * }</pre>
     *
     * <p>{@code wants} is asked before anything about the event is read, so a
     * server where nobody wants a type pays one check per event. It may run on
     * any thread (chat is asynchronous), so it reads memory only, such as an
     * {@link ObjectiveIndex}. {@code handler} runs on the player's thread.
     *
     * @return the registration, to cancel it before the plugin is disabled
     */
    public @NotNull Subscription listen(@NotNull ObjectiveType type, @NotNull Predicate<Player> wants,
                                        @NotNull Consumer<ObjectiveProgress> handler) {
        ObjectiveRuntime.Sub sub = ObjectiveRuntime.add(plugin, Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(wants, "wants"), Objects.requireNonNull(handler, "handler"));
        return new Subscription(() -> ObjectiveRuntime.remove(sub));
    }
}
