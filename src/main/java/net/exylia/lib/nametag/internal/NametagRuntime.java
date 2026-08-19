package net.exylia.lib.nametag.internal;

import net.exylia.lib.nametag.NametagStyle;
import net.exylia.lib.nametag.PluginNametags;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The nametag module's working parts.
 *
 * <p>Everything that decides what to send lives here; everything that knows how
 * to send it lives behind {@link NametagSink}. That split is what lets the
 * decisions be tested without PacketEvents.
 */
public final class NametagRuntime {

    private static final State STATE = new State();
    private static final Map<String, Nametags> BY_PLUGIN = new ConcurrentHashMap<>();

    private static volatile NametagSink sink;
    private static volatile Logger logger = Logger.getLogger("ExyliaLib");

    private NametagRuntime() {
    }

    /** Starts listening for entity metadata, if PacketEvents is installed. */
    public static void init(Plugin plugin) {
        logger = plugin.getLogger();
        sink = NametagPackets.install(STATE);
    }

    public static boolean isSupported() {
        return sink != null;
    }

    public static PluginNametags of(Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), Nametags::new);
    }

    /**
     * Tells the module a player is on screen.
     *
     * <p>Their entity id is what an outgoing metadata packet carries, and it is
     * the only way back to the player it belongs to.
     */
    public static void register(Player player) {
        STATE.register(player.getEntityId(), player.getUniqueId());
    }

    /** Forgets a player who left, as a viewer and as somebody being watched. */
    public static void forget(Player player) {
        STATE.unregister(player.getEntityId());
        STATE.forget(player.getUniqueId());
    }

    /** Undoes what a plugin painted, when it is disabled. */
    public static void release(String pluginName) {
        Nametags nametags = BY_PLUGIN.remove(pluginName);
        if (nametags != null) {
            nametags.clear();
        }
    }

    /** Drops the listener and everything remembered. */
    public static void shutdown() {
        NametagSink current = sink;
        if (current != null) {
            current.close();
        }
        sink = null;
        BY_PLUGIN.clear();
        STATE.clear();
    }

    /** Installs a sink directly. For tests. */
    static void install(NametagSink value) {
        sink = value;
        BY_PLUGIN.clear();
        STATE.clear();
    }

    /** The state, for tests. */
    static State state() {
        return STATE;
    }

    // ------------------------------------------------------------------

    /** One plugin's nametags. */
    private static final class Nametags implements PluginNametags {

        private final String plugin;

        private Nametags(String plugin) {
            this.plugin = plugin;
        }

        @Override
        public void paint(@NotNull Player viewer, @NotNull Player target,
                          @NotNull NametagStyle style) {
            paint(viewer, List.of(target), style);
        }

        @Override
        public void paint(@NotNull Player viewer, @NotNull Collection<? extends Player> targets,
                          @NotNull NametagStyle style) {
            NametagSink out = sink;
            if (out == null) {
                return;
            }
            UUID viewerId = viewer.getUniqueId();
            String team = style.teamName();

            List<String> joining = new ArrayList<>(targets.size());
            List<Player> changed = new ArrayList<>(targets.size());
            for (Player target : targets) {
                if (target == null || !target.isOnline()) {
                    continue;
                }
                UUID targetId = target.getUniqueId();
                State.Painted previous = STATE.paintedOf(viewerId, targetId);
                if (previous != null && previous.style().equals(style)) {
                    // The client already draws them this way; a second team
                    // packet would say the same thing.
                    continue;
                }
                if (previous != null) {
                    leaveTeam(out, viewer, viewerId, previous);
                }
                STATE.register(target.getEntityId(), targetId);
                STATE.paint(viewerId, targetId,
                        new State.Painted(plugin, style, target.getName()));
                if (style.glowing()) {
                    STATE.glow(viewerId, targetId);
                } else {
                    STATE.unglow(viewerId, targetId);
                }
                joining.add(target.getName());
                changed.add(target);
            }
            if (joining.isEmpty()) {
                return;
            }

            safely(() -> {
                if (STATE.knows(viewerId, team)) {
                    out.addToTeam(viewer, team, joining);
                } else {
                    out.createTeam(viewer, team, style, joining);
                    STATE.learn(viewerId, team);
                }
                // The glow rides on entity flags, not on the team, so it only
                // appears once the player's flags are sent again.
                for (Player target : changed) {
                    out.refreshFlags(viewer, target);
                }
            });
        }

        @Override
        public void paintEachOther(@NotNull Collection<? extends Player> group,
                                   @NotNull NametagStyle style) {
            for (Player viewer : group) {
                if (viewer == null || !viewer.isOnline()) {
                    continue;
                }
                List<Player> others = new ArrayList<>(group.size());
                for (Player member : group) {
                    if (member != null && !member.equals(viewer)) {
                        others.add(member);
                    }
                }
                paint(viewer, others, style);
            }
        }

        @Override
        public void reset(@NotNull Player viewer, @NotNull Player target) {
            NametagSink out = sink;
            if (out == null) {
                return;
            }
            UUID viewerId = viewer.getUniqueId();
            State.Painted painted = STATE.paintedOf(viewerId, target.getUniqueId());
            // A plugin resets what it painted. Undoing another plugin's colour
            // would be a game silently overriding a clan.
            if (painted == null || !painted.plugin().equals(plugin)) {
                return;
            }
            STATE.unpaint(viewerId, target.getUniqueId());
            STATE.unglow(viewerId, target.getUniqueId());
            safely(() -> {
                leaveTeam(out, viewer, viewerId, painted);
                out.refreshFlags(viewer, target);
            });
        }

        @Override
        public void resetAll(@NotNull Player viewer) {
            for (Map.Entry<UUID, State.Painted> entry
                    : STATE.paintedBy(viewer.getUniqueId()).entrySet()) {
                if (!entry.getValue().plugin().equals(plugin)) {
                    continue;
                }
                Player target = Bukkit.getPlayer(entry.getKey());
                if (target != null) {
                    reset(viewer, target);
                } else {
                    forgetOffline(viewer.getUniqueId(), entry.getKey(), entry.getValue());
                }
            }
        }

        @Override
        public void resetEverywhere(@NotNull Player target) {
            for (UUID viewerId : STATE.viewers()) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null) {
                    continue;
                }
                State.Painted painted = STATE.paintedOf(viewerId, target.getUniqueId());
                if (painted != null && painted.plugin().equals(plugin)) {
                    reset(viewer, target);
                }
            }
        }

        @Override
        public NametagStyle styleOf(@NotNull Player viewer, @NotNull Player target) {
            State.Painted painted = STATE.paintedOf(viewer.getUniqueId(), target.getUniqueId());
            return painted == null || !painted.plugin().equals(plugin) ? null : painted.style();
        }

        @Override
        public void clear() {
            for (UUID viewerId : STATE.viewers()) {
                Player viewer = Bukkit.getPlayer(viewerId);
                for (Map.Entry<UUID, State.Painted> entry
                        : STATE.paintedBy(viewerId).entrySet()) {
                    if (!entry.getValue().plugin().equals(plugin)) {
                        continue;
                    }
                    Player target = viewer == null ? null : Bukkit.getPlayer(entry.getKey());
                    if (viewer != null && target != null) {
                        reset(viewer, target);
                    } else {
                        forgetOffline(viewerId, entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        /**
         * Drops a painting whose viewer or target is gone.
         *
         * <p>Nothing is sent: there is nobody to send it to. Only the state has
         * to stop believing it, or the next player to reuse that id inherits a
         * colour.
         */
        private void forgetOffline(UUID viewerId, UUID targetId, State.Painted painted) {
            STATE.unpaint(viewerId, targetId);
            STATE.unglow(viewerId, targetId);
        }
    }

    /**
     * Takes a player out of the team that was painting them.
     *
     * <p>The team itself is left on the client rather than removed: it is
     * shared by everyone painted the same way, and it will be reused the moment
     * anybody else is painted like that. An empty team on a client costs
     * nothing; deleting and recreating it costs two packets each time.
     */
    private static void leaveTeam(NametagSink out, Player viewer, UUID viewerId,
                                  State.Painted painted) {
        String team = painted.style().teamName();
        if (STATE.knows(viewerId, team)) {
            out.removeFromTeam(viewer, team, List.of(painted.targetName()));
        }
    }

    /**
     * Runs a send without letting it escape.
     *
     * <p>These calls end up inside PacketEvents. A client that disconnected
     * mid-send is their problem to report, not a reason for the game that asked
     * for a colour to fail.
     */
    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            logger.warning("A nametag could not be sent: " + t);
        }
    }
}
