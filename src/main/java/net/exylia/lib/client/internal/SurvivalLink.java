package net.exylia.lib.client.internal;

import net.exylia.lib.client.ClientBrand;
import net.exylia.lib.client.Cooldown;
import net.exylia.lib.client.Waypoint;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import online.pablorelojero.survivalcore.api.SurvivalCore;
import online.pablorelojero.survivalcore.api.SurvivalCoreApi;
import online.pablorelojero.survivalcore.api.common.Icon;
import online.pablorelojero.survivalcore.api.common.WorldPosition;
import online.pablorelojero.survivalcore.api.event.PlayerPingEvent;
import online.pablorelojero.survivalcore.api.event.SurvivalClientReadyEvent;
import online.pablorelojero.survivalcore.api.ping.PingSettings;
import online.pablorelojero.survivalcore.api.team.MemberBadge;
import online.pablorelojero.survivalcore.api.team.TeamMember;
import online.pablorelojero.survivalcore.api.team.TeamView;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The SurvivalCore client mod.
 *
 * <p>The only class in the module that names SurvivalCore types, so a server
 * without it never loads this one.
 *
 * <p>Waypoints and cooldowns are keyed by {@link Key} on that side. A waypoint
 * gets a fresh key every time it is shown and hands it back as its handle,
 * exactly like Feather's UUID, so two plugins using one name never share a
 * slot. A cooldown has no handle, so its key is derived from its name.
 *
 * <p>Teams carry their {@link TeamLook} over: name, colours, ranks and allies.
 * Being in a team switches pings on, and a ping reaches that team.
 */
final class SurvivalLink implements ClientLink {

    private static final String NAMESPACE = "exylialib";

    /** The one team view this library shows, replaced whole on every update. */
    private static final Key MARKERS = Key.key(NAMESPACE, "markers");

    /** {@link ClientRegistry#load} runs more than once; the listener must not. */
    private static boolean listening;

    private final SurvivalCoreApi api;

    private SurvivalLink(SurvivalCoreApi api) {
        this.api = api;
    }

    /** Builds the link, or returns {@code null} when SurvivalCore is not running. */
    static ClientLink create() {
        SurvivalCoreApi api = SurvivalCore.find().orElse(null);
        if (api == null) {
            return null;
        }
        if (!listening) {
            listening = true;
            JavaPlugin library = JavaPlugin.getProvidingPlugin(SurvivalLink.class);
            Bukkit.getPluginManager().registerEvents(new ReadyListener(), library);
            Bukkit.getPluginManager().registerEvents(new PingListener(), library);
        }
        return new SurvivalLink(api);
    }

    /**
     * Sends what the player should see the moment the mod finishes its
     * handshake, instead of waiting for the fixed join delay to guess.
     */
    private static final class ReadyListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onReady(SurvivalClientReadyEvent event) {
            Player player = event.getPlayer();
            ClientRegistry.forget(player.getUniqueId());
            ClientRuntime.resend(player, false);
        }
    }

    /**
     * Sends a ping to the pinger's team.
     *
     * <p>The mod leaves the recipients at the pinger alone, so without this a
     * ping reaches nobody. A plugin that already picked recipients, or
     * cancelled the ping, runs first and is left alone.
     */
    private static final class PingListener implements Listener {
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onPing(PlayerPingEvent event) {
            Player player = event.getPlayer();
            if (event.recipients().size() == 1 && event.recipients().contains(player)) {
                event.recipients().addAll(TeamRegistry.teammatesOf(player.getUniqueId()));
            }
        }
    }

    @Override
    public ClientBrand brand() {
        return ClientBrand.SURVIVALCORE;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean recognises(Player player) {
        return api.clients().hasMod(player);
    }

    // ------------------------------------------------------------------
    // Waypoints
    // ------------------------------------------------------------------

    @Override
    public boolean supportsWaypoints() {
        return true;
    }

    /** The mod counts a waypoint's {@code ttl} down itself. */
    @Override
    public boolean expiresWaypoints() {
        return true;
    }

    /**
     * Sends a waypoint.
     *
     * <p>{@code preventRemoval} and {@code hidden} have no counterpart in the
     * mod's waypoint, so they are dropped, as on Feather.
     */
    @Override
    public Object showWaypoint(Player player, Waypoint waypoint) {
        World world = waypoint.worldId() != null
                ? Bukkit.getWorld(waypoint.worldId())
                : Bukkit.getWorld(waypoint.worldName());
        if (world == null) {
            return null;
        }
        Key id = Key.key(NAMESPACE, "waypoint/" + UUID.randomUUID());
        Waypoint.Colour colour = waypoint.colour();
        Duration ttl = waypoint.duration();
        api.waypoints().show(player, online.pablorelojero.survivalcore.api.waypoint.Waypoint
                .builder(id, WorldPosition.of(world, waypoint.x() + 0.5, waypoint.y(), waypoint.z() + 0.5))
                .name(Component.text(waypoint.name()))
                .color(TextColor.color(colour.red(), colour.green(), colour.blue()))
                .ttl(ttl == null || ttl.isZero() || ttl.isNegative() ? null : ttl)
                .build());
        return id;
    }

    @Override
    public void removeWaypoint(Player player, Waypoint waypoint, Object handle) {
        if (handle instanceof Key id) {
            api.waypoints().remove(player, id);
        }
    }

    @Override
    public void clearWaypoints(Player player) {
        api.waypoints().clear(player);
    }

    // ------------------------------------------------------------------
    // Cooldowns
    // ------------------------------------------------------------------

    @Override
    public boolean supportsCooldowns() {
        return true;
    }

    @Override
    public void showCooldown(Player player, Cooldown cooldown) {
        api.cooldowns().show(player, online.pablorelojero.survivalcore.api.cooldown.Cooldown
                .of(cooldownKey(cooldown.name()), icon(cooldown.icon()), cooldown.duration()));
    }

    @Override
    public void removeCooldown(Player player, String name) {
        api.cooldowns().remove(player, cooldownKey(name));
    }

    @Override
    public void clearCooldowns(Player player) {
        api.cooldowns().clear(player);
    }

    /** A key can only hold {@code [a-z0-9_.-/]}, and a cooldown name is free text. */
    private static Key cooldownKey(String name) {
        return Key.key(NAMESPACE, "cooldown/"
                + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.\\-/]", "_"));
    }

    /**
     * An item icon maps across; a resource texture is one Lunar has and this mod
     * does not, so it is drawn without an icon rather than not drawn.
     */
    private static Icon icon(Cooldown.Icon icon) {
        Material material = icon.isItem() ? Material.matchMaterial(icon.item()) : null;
        return material != null && material.isItem() ? Icon.item(material) : Icon.none();
    }

    // ------------------------------------------------------------------
    // Markers
    // ------------------------------------------------------------------

    @Override
    public boolean supportsMarkers() {
        return true;
    }

    /**
     * Sends the team, which the mod tracks the positions of itself.
     *
     * <p>A bare markers push is markers only. A styled {@link
     * net.exylia.lib.client.ClientTeam} also gets the HUD panel, with each
     * member in the viewer's own colour and rank or as an ally.
     *
     * <p>Being in a team also switches pings on: a ping is only worth making
     * when somebody is there to see it, and {@link PingListener} sends it to
     * exactly this team.
     */
    @Override
    public void updateMarkers(Player viewer, Collection<Player> teammates, TeamLook look) {
        UUID viewerId = viewer.getUniqueId();
        List<TeamMember> members = new ArrayList<>(teammates.size());
        for (Player teammate : teammates) {
            if (teammate.equals(viewer) || !teammate.isOnline()) {
                continue;
            }
            if (look == null) {
                members.add(TeamMember.of(teammate, NamedTextColor.WHITE, MemberBadge.MEMBER));
                continue;
            }
            UUID id = teammate.getUniqueId();
            members.add(TeamMember.of(teammate, look.colourOf(viewerId, id),
                    look.ally(viewerId, id) ? MemberBadge.ALLY : badge(look.rankOf(id))));
        }
        if (members.isEmpty()) {
            clearMarkers(viewer);
            return;
        }
        TeamView.Builder view = TeamView.builder(look == null
                        ? MARKERS
                        : Key.key(NAMESPACE, "team/" + look.id()))
                .members(members)
                .hudList(look != null && look.styled());
        if (look != null && look.styled()) {
            view.name(look.name()).color(look.colour());
        }
        api.teams().show(viewer, view.build());
        // A bare markers push has no team for a ping to reach.
        if (look != null && api.pings().settings(viewer).isEmpty()) {
            api.pings().enable(viewer, PingSettings.defaults());
        }
    }

    @Override
    public void clearMarkers(Player viewer) {
        api.teams().hide(viewer);
        api.pings().disable(viewer);
    }

    private static MemberBadge badge(net.exylia.lib.client.ClientTeam.Rank rank) {
        return switch (rank) {
            case LEADER -> MemberBadge.LEADER;
            case OFFICER -> MemberBadge.OFFICER;
            case MEMBER -> MemberBadge.MEMBER;
            case RECRUIT -> MemberBadge.RECRUIT;
        };
    }
}
