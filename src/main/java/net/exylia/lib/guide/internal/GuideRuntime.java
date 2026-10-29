package net.exylia.lib.guide.internal;

import net.exylia.lib.guide.BeaconStyle;
import net.exylia.lib.guide.GuideBeacon;
import net.exylia.lib.guide.GuideHandle;
import net.exylia.lib.guide.GuideText;
import net.exylia.lib.guide.GuideTextStyle;
import net.exylia.lib.guide.GuideTrail;
import net.exylia.lib.guide.GuideWaypoint;
import net.exylia.lib.guide.Route;
import net.exylia.lib.guide.TrailStyle;
import net.exylia.lib.guide.ViewTextStyle;
import net.exylia.lib.task.Tasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleFunction;
import java.util.logging.Logger;

/**
 * Owns every player's guidance on the server.
 *
 * <h2>Nothing may be left behind</h2>
 * Guidance is packets, so the server has no record of it and nothing else
 * will take it off a client. The life is held here: a piece goes when its
 * caller removes it, when its player leaves (their client is gone with it),
 * when its plugin is disabled, and when the server stops. A world change or a
 * respawn is not an end: the client threw the entities away by itself, and
 * the next tick draws them again where the player now is.
 */
@ApiStatus.Internal
public final class GuideRuntime {

    /** plugin name to player to that plugin's session for them. */
    private static final Map<String, Map<UUID, ViewerSession>> SESSIONS = new ConcurrentHashMap<>();

    /** Long enough for the move, and on Folia the region hand-over, to land. */
    private static final long TELEPORT_REDRAW_TICKS = 5L;

    private static GuidePackets listener;
    private static volatile boolean available;
    private static Plugin library;
    private static Logger logger = Logger.getLogger("ExyliaLib");
    private static boolean warned;

    private GuideRuntime() {
    }

    /**
     * Wires the module. Called by ExyliaLib at startup.
     *
     * @param plugin the library
     */
    public static void init(Plugin plugin) {
        library = plugin;
        logger = plugin.getLogger();
        available = GuidePackets.ready();
        if (available) {
            listener = GuidePackets.install();
        }
        plugin.getServer().getPluginManager().registerEvents(new GuideListener(), plugin);
    }

    /** Whether guidance can be drawn at all: PacketEvents is here. */
    public static boolean isSupported() {
        return available;
    }

    /**
     * The session one plugin keeps for one player, made on first use.
     *
     * @return the session, or {@code null} when nothing can be drawn
     */
    static ViewerSession session(Plugin plugin, Player player) {
        return session(plugin, player, true);
    }

    /**
     * As {@link #session(Plugin, Player)}, for a piece that can draw something
     * without PacketEvents too (a waypoint on a modded client).
     */
    static ViewerSession session(Plugin plugin, Player player, boolean needsPackets) {
        if (needsPackets && !available) {
            warnOnce(plugin.getName());
            return null;
        }
        if (!player.isOnline()) {
            return null;
        }
        return SESSIONS.computeIfAbsent(plugin.getName(), name -> new ConcurrentHashMap<>())
                .computeIfAbsent(player.getUniqueId(), id -> new ViewerSession(plugin, player));
    }

    // ------------------------------------------------------------- pieces
    // Each one is handed to its session, which starts drawing it on the
    // player's next tick. Without a session (no PacketEvents, an offline
    // player) the piece is inert: a handle that works and draws nothing.

    public static GuideText text(Plugin plugin, Player player, Component text, GuideTextStyle style,
                                 Location anchor) {
        ViewerSession session = session(plugin, player);
        return attach(session, new LiveText(session, text, style, anchor));
    }

    public static GuideText view(Plugin plugin, Player player, Component text, ViewTextStyle style) {
        ViewerSession session = session(plugin, player);
        return attach(session, new LiveViewText(session, text, style));
    }

    public static GuideBeacon beacon(Plugin plugin, Player player, Location target, BeaconStyle style,
                                     DoubleFunction<Component> label) {
        ViewerSession session = session(plugin, player);
        return attach(session, new LiveBeacon(session, target, style, label));
    }

    public static GuideTrail trail(Plugin plugin, Player player, World world, Route route, TrailStyle style) {
        ViewerSession session = session(plugin, player);
        return attach(session, new LiveTrail(session, world, route, style));
    }

    public static GuideWaypoint waypoint(Plugin plugin, Player player, String name, Location target, int rgb) {
        ViewerSession session = session(plugin, player, false);
        return attach(session, new LiveWaypoint(session, plugin, name, target, rgb));
    }

    public static GuideHandle glow(Plugin plugin, Player viewer, Entity target, NamedTextColor colour) {
        ViewerSession session = session(plugin, viewer);
        return attach(session, LiveGlow.entity(session, plugin, target, colour));
    }

    public static GuideHandle glow(Plugin plugin, Player viewer, int entityId) {
        ViewerSession session = session(plugin, viewer);
        return attach(session, LiveGlow.packetEntity(session, plugin, entityId));
    }

    public static GuideHandle glow(Plugin plugin, Player viewer, Block block, TextColor colour) {
        ViewerSession session = session(plugin, viewer);
        return attach(session, LiveGlow.block(session, plugin, block, colour));
    }

    private static <T extends Piece> T attach(ViewerSession session, T piece) {
        if (session != null) {
            session.add(piece);
        }
        return piece;
    }

    /** Takes down everything one plugin shows one player. */
    public static void clear(Plugin plugin, Player player) {
        Map<UUID, ViewerSession> sessions = SESSIONS.get(plugin.getName());
        ViewerSession session = sessions == null ? null : sessions.remove(player.getUniqueId());
        if (session != null) {
            session.close(true);
        }
    }

    /** Takes down everything one plugin shows. Called when it is disabled. */
    public static void release(String pluginName) {
        Map<UUID, ViewerSession> sessions = SESSIONS.remove(pluginName);
        if (sessions != null) {
            sessions.values().forEach(session -> session.close(true));
        }
    }

    /** Takes down everything, on shutdown. */
    public static void releaseAll() {
        for (String name : SESSIONS.keySet()) {
            release(name);
        }
        GuidePaths.clear();
        if (listener != null) {
            listener.close();
            listener = null;
        }
        available = false;
    }

    /** A player left: their client is gone, so this is bookkeeping, not packets. */
    static void quit(Player player) {
        for (Map<UUID, ViewerSession> sessions : SESSIONS.values()) {
            ViewerSession session = sessions.remove(player.getUniqueId());
            if (session != null) {
                session.close(false);
            }
        }
        if (available) {
            GuidePackets.forget(player.getUniqueId());
        }
    }

    /** A player's client dropped its entities: everything is drawn again next tick. */
    static void clientReset(Player player) {
        for (Map<UUID, ViewerSession> sessions : SESSIONS.values()) {
            ViewerSession session = sessions.get(player.getUniqueId());
            if (session != null) {
                session.forgetClient();
            }
        }
    }

    /**
     * A long teleport in one world. The event fires before the move lands, so
     * the redraw waits a few ticks on the player's own thread, where they now
     * are; drawing now would spawn everything back at the old place.
     */
    static void teleported(Player player) {
        if (library == null) {
            return;
        }
        Tasks.of(library).runAtEntityLater(player, TELEPORT_REDRAW_TICKS, () -> clientReset(player));
    }

    /** How many players have guidance from anybody, for diagnostics. */
    public static int active() {
        return SESSIONS.values().stream().mapToInt(Map::size).sum();
    }

    private static void warnOnce(String owner) {
        if (warned) {
            return;
        }
        warned = true;
        logger.warning(owner + " asked for player guidance, but this server has no PacketEvents."
                + " Guidance is packets and nothing else, so nothing was shown.");
    }
}
