package net.exylia.lib.client.internal;

import net.digitalingot.feather.serverapi.api.FeatherAPI;
import net.digitalingot.feather.serverapi.api.model.FeatherMod;
import net.digitalingot.feather.serverapi.api.player.FeatherPlayer;
import net.digitalingot.feather.serverapi.api.waypoint.WaypointBuilder;
import net.digitalingot.feather.serverapi.api.waypoint.WaypointColor;
import net.digitalingot.feather.serverapi.api.waypoint.WaypointDuration;
import net.digitalingot.feather.serverapi.api.waypoint.WaypointService;
import net.exylia.lib.client.ClientBrand;
import net.exylia.lib.client.Waypoint;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Feather Client.
 *
 * <p>The only class in the module that names Feather types, so a server
 * without it never loads this one.
 *
 * <p>Feather draws waypoints and nothing else this library offers, which is
 * why every other {@code supports} method is left at its default {@code false}:
 * a player on Feather silently gets the waypoints and not the cooldowns,
 * instead of the caller having to know.
 */
final class FeatherLink implements ClientLink {

    /** Feather hides waypoints unless the mod is on, so it is switched on. */
    private static final List<FeatherMod> WAYPOINT_MOD = List.of(new FeatherMod("waypoints"));

    private final WaypointService waypoints;

    private FeatherLink(WaypointService waypoints) {
        this.waypoints = waypoints;
    }

    /** Builds the link, or returns {@code null} when Feather is not installed. */
    static ClientLink create() {
        if (!Bukkit.getPluginManager().isPluginEnabled("FeatherServerAPI")
                && !Bukkit.getPluginManager().isPluginEnabled("feather-server-api")) {
            return null;
        }
        try {
            WaypointService service = FeatherAPI.getWaypointService();
            return service == null ? null : new FeatherLink(service);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public ClientBrand brand() {
        return ClientBrand.FEATHER;
    }

    @Override
    public boolean available() {
        return waypoints != null;
    }

    @Override
    public boolean recognises(Player player) {
        return feather(player) != null;
    }

    private static FeatherPlayer feather(Player player) {
        try {
            return FeatherAPI.getPlayerService().getPlayer(player.getUniqueId());
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public boolean supportsWaypoints() {
        return waypoints != null;
    }

    /**
     * Feather keys waypoints by world and drops them when the player leaves it,
     * so they have to be sent again.
     */
    @Override
    public boolean resendsOnWorldChange() {
        return true;
    }

    @Override
    public Object showWaypoint(Player player, Waypoint waypoint) {
        FeatherPlayer target = feather(player);
        if (target == null) {
            return null;
        }
        target.enableMods(WAYPOINT_MOD);

        WaypointBuilder builder = waypoints.createWaypointBuilder(
                        waypoint.x(), waypoint.y(), waypoint.z())
                .withName(waypoint.name())
                .withColor(colour(waypoint.colour()))
                .withDuration(duration(waypoint));

        UUID worldId = waypoint.worldId();
        if (worldId != null) {
            builder.withWorldId(worldId);
        }
        return waypoints.createWaypoint(target, builder);
    }

    @Override
    public void removeWaypoint(Player player, Waypoint waypoint, Object handle) {
        FeatherPlayer target = feather(player);
        if (target != null && handle instanceof UUID id) {
            waypoints.destroyWaypoint(target, id);
        }
    }

    @Override
    public void clearWaypoints(Player player) {
        FeatherPlayer target = feather(player);
        if (target != null) {
            waypoints.destroyAllWaypoints(target);
        }
    }

    @Override
    public void forget(UUID playerId) {
    }

    private static WaypointColor colour(Waypoint.Colour colour) {
        if (colour.chroma()) {
            return WaypointColor.chroma();
        }
        return WaypointColor.fromRgba(colour.red(), colour.green(), colour.blue(), colour.alpha());
    }

    private static WaypointDuration duration(Waypoint waypoint) {
        java.time.Duration duration = waypoint.duration();
        if (duration == null || duration.isZero()) {
            return WaypointDuration.none();
        }
        // Feather counts in seconds; anything under one still has to last a
        // tick rather than expire instantly.
        return WaypointDuration.of(Math.max(1, (int) duration.toSeconds()));
    }
}
