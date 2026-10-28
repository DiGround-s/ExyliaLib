package net.exylia.lib.npc;

import net.exylia.lib.interaction.Click;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A player clicked a placed NPC, whichever plugin placed it.
 *
 * <pre>{@code
 * @EventHandler
 * void on(PlacedNpcClickEvent event) {
 *     if (event.owner().getName().equals("ExyliaDisplays")) {
 *         quests.progress(event.getPlayer(), "INTERACT_NPC", event.npcId());
 *     }
 * }
 * }</pre>
 *
 * <p>For observing another plugin's NPCs: the owner keeps its own
 * {@link PlacedNpc#onClick} handler, which runs right after this event. Fired on the
 * clicking player's thread, once per counted click (a double press the client sends
 * twice is one click), and only for a player who has the NPC on screen.
 *
 * @since 1.392.0
 */
public final class PlacedNpcClickEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final PlacedNpc npc;
    private final Plugin owner;
    private final Click click;

    public PlacedNpcClickEvent(@NotNull Player player, @NotNull PlacedNpc npc,
                               @NotNull Plugin owner, @NotNull Click click) {
        this.player = Objects.requireNonNull(player, "player");
        this.npc = Objects.requireNonNull(npc, "npc");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.click = Objects.requireNonNull(click, "click");
    }

    /** Who clicked. */
    public @NotNull Player getPlayer() {
        return player;
    }

    /** The NPC that was clicked. */
    public @NotNull PlacedNpc npc() {
        return npc;
    }

    /** The id it was placed under, unique within {@link #owner()}. */
    public @NotNull String npcId() {
        return npc.id();
    }

    /** The plugin that placed it. */
    public @NotNull Plugin owner() {
        return owner;
    }

    /** Which button, and whether the player was sneaking. */
    public @NotNull Click click() {
        return click;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
