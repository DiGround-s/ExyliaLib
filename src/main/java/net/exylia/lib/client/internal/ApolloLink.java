package net.exylia.lib.client.internal;

import com.lunarclient.apollo.Apollo;
import com.lunarclient.apollo.common.icon.Icon;
import com.lunarclient.apollo.common.icon.ItemStackIcon;
import com.lunarclient.apollo.common.icon.SimpleResourceLocationIcon;
import com.lunarclient.apollo.common.location.ApolloBlockLocation;
import com.lunarclient.apollo.common.location.ApolloLocation;
import com.lunarclient.apollo.mods.impl.ModWaypoints;
import com.lunarclient.apollo.module.modsetting.ModSettingModule;
import com.lunarclient.apollo.module.team.TeamMember;
import com.lunarclient.apollo.module.team.TeamModule;
import com.lunarclient.apollo.module.waypoint.Waypoint;
import com.lunarclient.apollo.module.waypoint.WaypointModule;
import com.lunarclient.apollo.module.cooldown.Cooldown;
import com.lunarclient.apollo.module.cooldown.CooldownModule;
import com.lunarclient.apollo.player.ApolloPlayer;
import net.exylia.lib.client.ClientBrand;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lunar Client, through Apollo.
 *
 * <p>The only class in the module that names Apollo types, so a server without
 * it never loads this one.
 */
final class ApolloLink implements ClientLink {

    private final WaypointModule waypoints;
    private final CooldownModule cooldowns;
    private final TeamModule teams;
    private final ModSettingModule modSettings;

    private ApolloLink(WaypointModule waypoints, CooldownModule cooldowns,
                       TeamModule teams, ModSettingModule modSettings) {
        this.waypoints = waypoints;
        this.cooldowns = cooldowns;
        this.teams = teams;
        this.modSettings = modSettings;
    }

    /**
     * Builds the link, or returns {@code null} when Apollo is not installed.
     *
     * <p>Loading the modules here rather than on every call means a missing
     * Apollo is discovered once, at startup, instead of being caught over and
     * over on a hot path.
     */
    static ClientLink create() {
        if (!enabled()) {
            return null;
        }
        try {
            return new ApolloLink(
                    Apollo.getModuleManager().getModule(WaypointModule.class),
                    Apollo.getModuleManager().getModule(CooldownModule.class),
                    Apollo.getModuleManager().getModule(TeamModule.class),
                    Apollo.getModuleManager().getModule(ModSettingModule.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean enabled() {
        return Bukkit.getPluginManager().isPluginEnabled("Apollo")
                || Bukkit.getPluginManager().isPluginEnabled("Apollo-Bukkit")
                || Bukkit.getPluginManager().isPluginEnabled("Apollo-Folia");
    }

    @Override
    public ClientBrand brand() {
        return ClientBrand.LUNAR;
    }

    @Override
    public boolean available() {
        return waypoints != null || cooldowns != null || teams != null;
    }

    @Override
    public boolean recognises(Player player) {
        return apollo(player).isPresent();
    }

    private Optional<ApolloPlayer> apollo(Player player) {
        try {
            return Apollo.getPlayerManager().getPlayer(player.getUniqueId());
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // Waypoints
    // ------------------------------------------------------------------

    @Override
    public boolean supportsWaypoints() {
        return waypoints != null;
    }

    /**
     * Lunar has one waypoint per name per player, and no handle of its own.
     *
     * <p>Which is why {@link #showWaypoint} can only give the name back: there
     * is nothing else to remove it by. {@code ClientRuntime} reads this and
     * arbitrates the slot, so a second plugin using the same name no longer
     * deletes the first one's marker on its way out.
     */
    @Override
    public boolean keysWaypointsByName() {
        return true;
    }

    @Override
    public Object showWaypoint(Player player, net.exylia.lib.client.Waypoint waypoint) {
        ApolloPlayer target = apollo(player).orElse(null);
        if (target == null) {
            return null;
        }
        // Lunar hides waypoints entirely when the player has the mod switched
        // off, so it is switched on for them: a waypoint the server sent and
        // the player cannot see is a support ticket.
        if (modSettings != null) {
            modSettings.getOptions().set(target, ModWaypoints.ENABLED, true);
        }
        waypoints.displayWaypoint(target, Waypoint.builder()
                .name(waypoint.name())
                .location(ApolloBlockLocation.builder()
                        .world(waypoint.worldName())
                        .x(waypoint.x())
                        .y(waypoint.y())
                        .z(waypoint.z())
                        .build())
                .color(colour(waypoint.colour()))
                .preventRemoval(waypoint.preventRemoval())
                .hidden(waypoint.hidden())
                .build());
        return waypoint.name();
    }

    @Override
    public void removeWaypoint(Player player, net.exylia.lib.client.Waypoint waypoint, Object handle) {
        apollo(player).ifPresent(target -> waypoints.removeWaypoint(target, String.valueOf(handle)));
    }

    @Override
    public void clearWaypoints(Player player) {
        apollo(player).ifPresent(waypoints::resetWaypoints);
    }

    // ------------------------------------------------------------------
    // Cooldowns
    // ------------------------------------------------------------------

    @Override
    public boolean supportsCooldowns() {
        return cooldowns != null;
    }

    @Override
    public void showCooldown(Player player, net.exylia.lib.client.Cooldown cooldown) {
        apollo(player).ifPresent(target -> cooldowns.displayCooldown(target, Cooldown.builder()
                .name(cooldown.name())
                .duration(cooldown.duration())
                .icon(icon(cooldown.icon()))
                .build()));
    }

    @Override
    public void removeCooldown(Player player, String name) {
        apollo(player).ifPresent(target -> cooldowns.removeCooldown(target, name));
    }

    @Override
    public void clearCooldowns(Player player) {
        apollo(player).ifPresent(cooldowns::resetCooldowns);
    }

    // ------------------------------------------------------------------
    // Markers
    // ------------------------------------------------------------------

    @Override
    public boolean supportsMarkers() {
        return teams != null;
    }

    @Override
    public void updateMarkers(Player viewer, Collection<Player> teammates) {
        ApolloPlayer target = apollo(viewer).orElse(null);
        if (target == null) {
            return;
        }
        List<TeamMember> members = new ArrayList<>(teammates.size());
        for (Player teammate : teammates) {
            if (teammate.equals(viewer) || !teammate.isOnline()) {
                continue;
            }
            members.add(TeamMember.builder()
                    .playerUuid(teammate.getUniqueId())
                    .displayName(Component.text(teammate.getName()))
                    .markerColor(Color.WHITE)
                    .location(ApolloLocation.builder()
                            .world(teammate.getWorld().getName())
                            .x(teammate.getLocation().getX())
                            .y(teammate.getLocation().getY())
                            .z(teammate.getLocation().getZ())
                            .build())
                    .build());
        }
        if (members.isEmpty()) {
            teams.resetTeamMembers(target);
            return;
        }
        teams.updateTeamMembers(target, members);
    }

    @Override
    public void clearMarkers(Player viewer) {
        apollo(viewer).ifPresent(teams::resetTeamMembers);
    }

    @Override
    public void forget(UUID playerId) {
    }

    private static Color colour(net.exylia.lib.client.Waypoint.Colour colour) {
        return new Color(colour.red(), colour.green(), colour.blue(), colour.alpha());
    }

    private static Icon icon(net.exylia.lib.client.Cooldown.Icon icon) {
        if (icon.isItem()) {
            return ItemStackIcon.builder().itemName(icon.item()).build();
        }
        return SimpleResourceLocationIcon.builder()
                .resourceLocation(icon.resource())
                .size(icon.size())
                .build();
    }
}
