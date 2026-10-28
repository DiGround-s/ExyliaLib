package net.exylia.lib.guide.internal;

import net.exylia.lib.client.Clients;
import net.exylia.lib.client.Waypoint;
import net.exylia.lib.guide.GuideWaypoint;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A waypoint on whatever the player's client can draw one on: the vanilla
 * locator bar from 1.21.6, Lunar's or Feather's minimap, or both.
 *
 * <p>The locator bar knows nothing of worlds, so the waypoint is untracked
 * while the player is in another world than the target and tracked again when
 * they come back. The modded clients keep their own per-world waypoints, so
 * they are told once and left alone.
 */
final class LiveWaypoint extends Piece implements GuideWaypoint {

    private final UUID id = UUID.randomUUID();
    private final Plugin plugin;
    private final String name;
    private final int rgb;

    private volatile Location target;
    private volatile boolean moved = true;
    private volatile boolean drawnSomewhere;
    private boolean tracked;
    private Boolean vanilla;

    LiveWaypoint(ViewerSession session, Plugin plugin, String name, Location target, int rgb) {
        super(session);
        this.plugin = plugin;
        this.name = name;
        this.target = target.clone();
        this.rgb = rgb;
    }

    @Override
    public void move(@NotNull Location where) {
        this.target = where.clone();
        this.moved = true;
    }

    @Override
    public boolean isDrawn() {
        return drawnSomewhere;
    }

    @Override
    void draw(Player player, long tick) {
        Location goal = target;
        if (vanilla == null) {
            vanilla = GuideRuntime.isSupported() && GuidePackets.waypointsSupported(player);
        }
        if (moved) {
            moved = false;
            boolean modded = false;
            try {
                modded = Clients.of(plugin).waypoints().show(player, Waypoint.at(name, goal)
                        .colour(Waypoint.Colour.of((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)));
            } catch (Throwable unavailable) {
                // No client integration on this server; the locator bar may still draw it.
            }
            drawnSomewhere = modded || vanilla;
            if (tracked && vanilla) {
                send(player, "UPDATE", goal);
            }
        }
        if (!vanilla) {
            return;
        }
        boolean here = player.getWorld().equals(goal.getWorld());
        if (here && !tracked) {
            send(player, "TRACK", goal);
            tracked = true;
        } else if (!here && tracked) {
            send(player, "UNTRACK", goal);
            tracked = false;
        }
    }

    private void send(Player player, String operation, Location at) {
        GuidePackets.waypoint(player, id, operation, at.getBlockX(), at.getBlockY(), at.getBlockZ(), rgb);
    }

    @Override
    void clear(Player player) {
        if (tracked) {
            send(player, "UNTRACK", target);
            tracked = false;
        }
        try {
            Clients.of(plugin).waypoints().remove(player, name);
        } catch (Throwable unavailable) {
            // Nothing was drawn there.
        }
        moved = true;
    }

    @Override
    void forget() {
        tracked = false;
        moved = true;
    }
}
