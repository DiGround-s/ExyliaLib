package net.exylia.lib.guide;

import net.exylia.lib.guide.internal.GuideRuntime;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.DoubleFunction;

/**
 * One plugin's guidance, obtained from {@link Guides#of(Plugin)}.
 *
 * <p>Every method returns a handle, never {@code null}: on a server that
 * cannot draw guidance the handle works and draws nothing, so a caller never
 * branches on support. Everything a plugin shows is taken down when it is
 * disabled.
 *
 * @since 1.391.0
 */
public final class PluginGuides {

    private final Plugin plugin;

    PluginGuides(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * A panel floating ahead of the player's eyes, in the companion style.
     *
     * @param player who sees it
     * @param text   what it says; update it with {@link GuideText#text(Component)}
     * @return the handle
     */
    public @NotNull GuideText companion(@NotNull Player player, @NotNull Component text) {
        return companion(player, text, GuideTextStyle.companion());
    }

    /**
     * A panel floating ahead of the player's eyes.
     *
     * <p>It glides after the view rather than sticking to it: it is moved only
     * when the view turns or the player walks far enough to matter, and the
     * client takes a few ticks to get there. It steps aside while they glide,
     * move faster than the style allows or are in combat, and comes back after
     * a second of calm.
     *
     * @param player who sees it
     * @param text   what it says
     * @param style  its look, distance and when it steps aside
     * @return the handle
     */
    public @NotNull GuideText companion(@NotNull Player player, @NotNull Component text,
                                        @NotNull GuideTextStyle style) {
        return GuideRuntime.text(plugin, player, text, style, null);
    }

    /**
     * A text riding the player's own view, in the subtitle style: small, on a
     * translucent panel, centred, a quarter of the way up the screen.
     *
     * @param player who sees it
     * @param text   what it says; update it with {@link GuideText#text(Component)}
     * @return the handle
     * @since 1.395.0
     */
    public @NotNull GuideText view(@NotNull Player player, @NotNull Component text) {
        return view(player, text, ViewTextStyle.subtitle());
    }

    /**
     * A text riding the player's own view.
     *
     * <p>It is seated on the player, visible to them alone, and the client
     * keeps it at the same spot on the screen every frame, however fast they
     * turn: a cinematic subtitle in normal play. It survives death, a change
     * of world, a long teleport and mounting or leaving a vehicle (it is
     * seated again each time), keeps whatever really rides the player, and
     * steps aside while a camera shot plays to them, while dead and in
     * spectator mode.
     *
     * <p>It is drawn for a first-person view. In third person the client still
     * puts it a block ahead of the player's eyes, which is in the shot,
     * smaller and lower.
     *
     * @param player who sees it
     * @param text   what it says
     * @param style  its look and where on the screen it sits
     * @return the handle
     * @since 1.395.0
     */
    public @NotNull GuideText view(@NotNull Player player, @NotNull Component text, @NotNull ViewTextStyle style) {
        return GuideRuntime.view(plugin, player, text, style);
    }

    /**
     * A sign at a place, in the anchored style.
     *
     * @param player who sees it
     * @param at     where its bottom edge stands; it faces the player
     * @param text   what it says
     * @return the handle
     */
    public @NotNull GuideText anchored(@NotNull Player player, @NotNull Location at, @NotNull Component text) {
        return anchored(player, at, text, GuideTextStyle.anchored());
    }

    /**
     * A sign at a place.
     *
     * <p>It always faces the player, and with
     * {@link GuideTextStyle#scaleWithDistance()} grows as they walk away so it
     * stays the same size on screen up to the style's limit. Nothing is drawn
     * while the player is in another world.
     *
     * @param player who sees it
     * @param at     where its bottom edge stands
     * @param text   what it says
     * @param style  its look
     * @return the handle
     */
    public @NotNull GuideText anchored(@NotNull Player player, @NotNull Location at, @NotNull Component text,
                                       @NotNull GuideTextStyle style) {
        return GuideRuntime.text(plugin, player, text, style, at);
    }

    /**
     * A beam of light over a target, with a label.
     *
     * <p>Past the style's cut-over (automatically, a little short of the
     * player's view distance) the beam is replaced by a shorter one
     * {@link BeaconStyle#markerDistance()} blocks away in the target's
     * direction, at eye height, which glides along with the player. In another
     * world than the target nothing is drawn and the state says so.
     *
     * @param player who sees it
     * @param target where the beam stands; pass a block's top centre,
     *               {@code block.getLocation().add(0.5, 1, 0.5)}
     * @param style  how it looks
     * @param label  the label for a distance in blocks, asked again each time
     *               the rounded distance changes, on the player's thread;
     *               {@code null} for no label
     * @return the handle
     */
    public @NotNull GuideBeacon beacon(@NotNull Player player, @NotNull Location target, @NotNull BeaconStyle style,
                                       @Nullable DoubleFunction<Component> label) {
        return GuideRuntime.beacon(plugin, player, target, style, label);
    }

    /**
     * Markers on the ground along a route, for the stretch just ahead of the
     * player.
     *
     * <p>The window starts at the point of the route nearest the player and
     * reaches {@link TrailStyle#ahead()} blocks along it. Markers sit at fixed
     * distances along the route, so walking costs one marker spawned ahead and
     * one destroyed behind per spacing, not a redraw. Off the route by more
     * than the style allows, a short line back to it is drawn too.
     *
     * @param player who sees it
     * @param world  the world the route is in; nothing is drawn elsewhere
     * @param route  the route
     * @param style  how it looks and how much is drawn
     * @return the handle
     */
    public @NotNull GuideTrail trail(@NotNull Player player, @NotNull World world, @NotNull Route route,
                                     @NotNull TrailStyle style) {
        return GuideRuntime.trail(plugin, player, world, route, style);
    }

    /**
     * A waypoint on the player's locator bar and modded minimap.
     *
     * <p>The locator bar is used when both the server and the client are on
     * 1.21.6 or newer and PacketEvents knows the packet; Lunar and Feather
     * through the client module when the player runs one. Either, both or
     * neither may draw it: {@link GuideWaypoint#isDrawn()} says which.
     *
     * @param player who sees it
     * @param name   its name on a modded minimap; unique per plugin and player
     * @param target where it points
     * @param rgb    its colour, {@code 0xRRGGBB}
     * @return the handle
     */
    public @NotNull GuideWaypoint waypoint(@NotNull Player player, @NotNull String name, @NotNull Location target,
                                           int rgb) {
        return GuideRuntime.waypoint(plugin, player, name, target, rgb);
    }

    /**
     * Outlines an entity for one player.
     *
     * <p>A player target goes through the nametag module (and, with a colour,
     * its teams); removing the glow resets what this plugin painted on that
     * target for that viewer. Any other entity keeps its glow through every
     * flag change the server sends, and a colour puts it in a team on the
     * viewer's client only.
     *
     * @param viewer who sees it
     * @param target what glows
     * @param colour the outline colour, {@code null} for white
     * @return the handle; remove it to put the glow out
     */
    public @NotNull GuideHandle glow(@NotNull Player viewer, @NotNull Entity target, @Nullable NamedTextColor colour) {
        return GuideRuntime.glow(plugin, viewer, target, colour);
    }

    /**
     * Outlines an entity that exists only as packets &mdash; an NPC body, a
     * display another plugin drew &mdash; by its entity id.
     *
     * <p>The outline is white, and putting it out sends the entity's flags as
     * zero, which is right for a packet body whose flags are otherwise unused;
     * for one that is invisible or on fire, ask its owner to glow it instead.
     *
     * @param viewer   who sees it
     * @param entityId the id the body was spawned with
     * @return the handle
     */
    public @NotNull GuideHandle glow(@NotNull Player viewer, int entityId) {
        return GuideRuntime.glow(plugin, viewer, entityId);
    }

    /**
     * Outlines a block for one player, through terrain.
     *
     * @param viewer who sees it
     * @param block  what glows; its shape is read on the block's own thread
     * @param colour the outline colour
     * @return the handle
     */
    public @NotNull GuideHandle glow(@NotNull Player viewer, @NotNull Block block, @NotNull TextColor colour) {
        return GuideRuntime.glow(plugin, viewer, block, colour);
    }

    /**
     * The way the player can walk from where they stand to a place, for an
     * automatic trail.
     *
     * <p>A search over the blocks a player can stand in: through doors and
     * gates, up one block at a time (slabs and stairs included), down drops of
     * up to three, swimming at a cost, never into lava, fire, cactus, magma or
     * powder snow, never through a wall's corner. It reads copies of the
     * already loaded chunks along the way, made on the threads that own them,
     * and searches on an async thread, so it is safe on Folia and never loads a
     * chunk. It is bounded: targets past 128 blocks are not searched, detours
     * past 20 blocks either side of the straight line are not found, and an
     * answer is kept ten seconds per start and target block.
     *
     * <p>Read the result honestly: {@link GuidePath#reached()} is a path to
     * the place; otherwise {@link GuidePath#route()} only goes as near as the
     * search could walk, and {@link GuidePath#unreachable()} says the place
     * cannot be walked to from here at all.
     *
     * @param player who walks; their position is read now, so call on their thread
     * @param target where to
     * @return the path; completes on an async thread, so take it back to the player's thread to draw
     * @since 1.396.0
     */
    public @NotNull java.util.concurrent.CompletableFuture<GuidePath> path(@NotNull Player player,
                                                                         @NotNull Location target) {
        return net.exylia.lib.guide.internal.GuidePaths.find(plugin, player.getLocation(), target);
    }

    /**
     * The way a player could walk from one place to another, the same search as
     * {@link #path(Player, Location)} from a place nobody needs to stand at:
     * for building a route ahead of time (a tutorial path from spawn to a
     * shop), not for drawing one to a player now.
     *
     * @param from   where the walk starts, at foot height
     * @param target where to
     * @return the path; completes on an async thread
     * @since 1.398.0
     */
    public @NotNull java.util.concurrent.CompletableFuture<GuidePath> path(@NotNull Location from,
                                                                         @NotNull Location target) {
        return net.exylia.lib.guide.internal.GuidePaths.find(plugin, from.clone(), target);
    }

    /**
     * Takes down everything this plugin shows one player.
     *
     * @param player the player
     */
    public void clear(@NotNull Player player) {
        GuideRuntime.clear(plugin, player);
    }

    /** Whether this server can draw guidance at all. */
    public boolean isSupported() {
        return GuideRuntime.isSupported();
    }
}
