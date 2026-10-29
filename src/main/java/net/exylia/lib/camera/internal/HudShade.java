package net.exylia.lib.camera.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBossBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearTitles;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisguisedChat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import net.exylia.lib.camera.Hud;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clears a viewer's HUD while they watch, and gives back exactly what was there.
 *
 * <p>Boss bars and the sidebar are known only from the packets that built them,
 * so those two are followed for every player all the time (a map entry per bar,
 * a string per player). While a player is shaded, the packets for what is hidden
 * are cancelled but still followed, so what comes back at the end is the state
 * as it is then, not as it was. What the shade itself sends goes out silently,
 * past every listener, this one included.
 *
 * <p>Runs on the network threads: every state is a concurrent map or guarded by
 * its own monitor. The one class besides {@link CameraPackets} that names
 * PacketEvents.
 */
final class HudShade extends PacketListenerAbstract {

    private static final int SIDEBAR = 1;
    private static final int HELD_CHAT = 100;

    /** Every bar a client has, by player then bar: the last full state, as an ADD. */
    private static final Map<UUID, Map<UUID, Bar>> BARS = new ConcurrentHashMap<>();
    /** The objective each client shows in its sidebar, "" for none. */
    private static final Map<UUID, String> SIDEBARS = new ConcurrentHashMap<>();
    private static final Map<UUID, Shade> SHADED = new ConcurrentHashMap<>();

    /** One bar as the client has it. */
    static final class Bar {
        Component title = Component.empty();
        float health;
        BossBar.Color color = BossBar.Color.WHITE;
        BossBar.Overlay overlay = BossBar.Overlay.PROGRESS;
        Set<BossBar.Flag> flags = EnumSet.noneOf(BossBar.Flag.class);
    }

    private static final class Shade {
        final Set<Hud> hidden;
        final List<PacketWrapper<?>> held = new ArrayList<>();

        Shade(Set<Hud> hidden) {
            this.hidden = hidden;
        }
    }

    private static HudShade installed;

    private HudShade() {
        super(PacketListenerPriority.MONITOR);
    }

    /** Starts following bars and sidebars; nothing without PacketEvents. */
    static synchronized void install() {
        if (installed != null || !CameraPackets.ready()) {
            return;
        }
        installed = new HudShade();
        PacketEvents.getAPI().getEventManager().registerListener(installed);
    }

    static boolean active() {
        return installed != null;
    }

    /** Hides parts of one player's HUD; a second call adds to what is hidden. */
    static void hide(Player player, Set<Hud> parts) {
        if (installed == null || parts.isEmpty()) {
            return;
        }
        UUID id = player.getUniqueId();
        Shade shade = SHADED.computeIfAbsent(id, key -> new Shade(EnumSet.noneOf(Hud.class)));
        Set<Hud> fresh = EnumSet.copyOf(parts);
        synchronized (shade) {
            fresh.removeAll(shade.hidden);
            shade.hidden.addAll(fresh);
        }
        if (fresh.contains(Hud.BOSSBAR)) {
            for (UUID bar : bars(id).keySet()) {
                quiet(player, new WrapperPlayServerBossBar(bar, WrapperPlayServerBossBar.Action.REMOVE));
            }
        }
        if (fresh.contains(Hud.SCOREBOARD) && !SIDEBARS.getOrDefault(id, "").isEmpty()) {
            quiet(player, new WrapperPlayServerDisplayScoreboard(SIDEBAR, ""));
        }
        if (fresh.contains(Hud.TITLES)) {
            quiet(player, new WrapperPlayServerClearTitles(true));
        }
    }

    /** Gives a player's HUD back: bars and sidebar as they are now, then the chat that waited. */
    static void show(Player player) {
        Shade shade = SHADED.remove(player.getUniqueId());
        if (shade == null) {
            return;
        }
        List<PacketWrapper<?>> held;
        synchronized (shade) {
            held = List.copyOf(shade.held);
        }
        if (shade.hidden.contains(Hud.BOSSBAR)) {
            for (Map.Entry<UUID, Bar> bar : List.copyOf(bars(player.getUniqueId()).entrySet())) {
                quiet(player, add(bar.getKey(), bar.getValue()));
            }
        }
        String sidebar = SIDEBARS.getOrDefault(player.getUniqueId(), "");
        if (shade.hidden.contains(Hud.SCOREBOARD) && !sidebar.isEmpty()) {
            quiet(player, new WrapperPlayServerDisplayScoreboard(SIDEBAR, sidebar));
        }
        for (PacketWrapper<?> line : held) {
            quiet(player, line);
        }
    }

    static boolean hidden(UUID player) {
        return SHADED.containsKey(player);
    }

    /** A chat line that belongs to what the player is watching: past the shade. */
    static void say(Player player, Component line) {
        quiet(player, new WrapperPlayServerSystemChatMessage(false, line));
    }

    /** The player left: nothing of theirs is kept. */
    static void forget(UUID player) {
        BARS.remove(player);
        SIDEBARS.remove(player);
        SHADED.remove(player);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        boolean bar = type == PacketType.Play.Server.BOSS_BAR;
        boolean sidebar = type == PacketType.Play.Server.DISPLAY_SCOREBOARD;
        if (!bar && !sidebar && SHADED.isEmpty()) {
            return;
        }
        UUID id = event.getUser() == null ? null : event.getUser().getUUID();
        if (id == null) {
            return;
        }
        Shade shade = SHADED.get(id);
        if (bar) {
            WrapperPlayServerBossBar packet = new WrapperPlayServerBossBar(event);
            follow(id, packet.getUUID(), packet.getAction(), packet.getTitle(), packet.getHealth(), packet.getColor(),
                    packet.getOverlay(), packet.getFlags());
            if (shade != null && shade.hidden.contains(Hud.BOSSBAR)) {
                event.setCancelled(true);
            }
            return;
        }
        if (sidebar) {
            WrapperPlayServerDisplayScoreboard display = new WrapperPlayServerDisplayScoreboard(event);
            if (display.getPosition() == SIDEBAR) {
                SIDEBARS.put(id, display.getScoreName() == null ? "" : display.getScoreName());
                if (shade != null && shade.hidden.contains(Hud.SCOREBOARD)) {
                    event.setCancelled(true);
                }
            }
            return;
        }
        if (shade == null || event.isCancelled()) {
            return;
        }
        if (type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {
            WrapperPlayServerSystemChatMessage line = new WrapperPlayServerSystemChatMessage(event);
            if (line.isOverlay() ? shade.hidden.contains(Hud.ACTIONBAR) : shade.hidden.contains(Hud.CHAT)) {
                event.setCancelled(true);
                if (!line.isOverlay()) {
                    hold(shade, line);
                }
            }
        } else if (type == PacketType.Play.Server.CHAT_MESSAGE && shade.hidden.contains(Hud.CHAT)) {
            event.setCancelled(true);
            hold(shade, new WrapperPlayServerChatMessage(event));
        } else if (type == PacketType.Play.Server.DISGUISED_CHAT && shade.hidden.contains(Hud.CHAT)) {
            event.setCancelled(true);
            hold(shade, new WrapperPlayServerDisguisedChat(event));
        } else if (type == PacketType.Play.Server.ACTION_BAR && shade.hidden.contains(Hud.ACTIONBAR)) {
            event.setCancelled(true);
        } else if (shade.hidden.contains(Hud.TITLES) && (type == PacketType.Play.Server.SET_TITLE_TEXT
                || type == PacketType.Play.Server.SET_TITLE_SUBTITLE || type == PacketType.Play.Server.SET_TITLE_TIMES
                || type == PacketType.Play.Server.TITLE)) {
            event.setCancelled(true);
        }
    }

    private static void hold(Shade shade, PacketWrapper<?> line) {
        synchronized (shade) {
            if (shade.held.size() < HELD_CHAT) {
                shade.held.add(line);
            }
        }
    }

    /** Folds one boss bar packet into what the client has. */
    static void follow(UUID player, UUID id, WrapperPlayServerBossBar.Action action, Component title, float health,
                       BossBar.Color color, BossBar.Overlay overlay, Set<BossBar.Flag> flags) {
        Map<UUID, Bar> bars = bars(player);
        synchronized (bars) {
            if (action == WrapperPlayServerBossBar.Action.REMOVE) {
                bars.remove(id);
                return;
            }
            Bar bar = action == WrapperPlayServerBossBar.Action.ADD ? new Bar() : bars.get(id);
            if (bar == null) {
                return;
            }
            switch (action) {
                case ADD -> {
                    bar.title = title == null ? Component.empty() : title;
                    bar.health = health;
                    bar.color = color == null ? BossBar.Color.WHITE : color;
                    bar.overlay = overlay == null ? BossBar.Overlay.PROGRESS : overlay;
                    bar.flags = flags == null || flags.isEmpty() ? EnumSet.noneOf(BossBar.Flag.class) : EnumSet.copyOf(flags);
                    bars.put(id, bar);
                }
                case UPDATE_HEALTH -> bar.health = health;
                case UPDATE_TITLE -> bar.title = title == null ? Component.empty() : title;
                case UPDATE_STYLE -> {
                    bar.color = color == null ? bar.color : color;
                    bar.overlay = overlay == null ? bar.overlay : overlay;
                }
                case UPDATE_FLAGS -> bar.flags = flags == null || flags.isEmpty()
                        ? EnumSet.noneOf(BossBar.Flag.class) : EnumSet.copyOf(flags);
                default -> {
                }
            }
        }
    }

    static Map<UUID, Bar> bars(UUID player) {
        return BARS.computeIfAbsent(player, key -> java.util.Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    private static WrapperPlayServerBossBar add(UUID id, Bar from) {
        WrapperPlayServerBossBar bar = new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.ADD);
        bar.setTitle(from.title);
        bar.setHealth(from.health);
        bar.setColor(from.color);
        bar.setOverlay(from.overlay);
        bar.setFlags(EnumSet.copyOf(from.flags.isEmpty() ? EnumSet.noneOf(BossBar.Flag.class) : from.flags));
        return bar;
    }

    private static void quiet(Player player, PacketWrapper<?> packet) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, packet);
        } catch (Throwable gone) {
            // A player who left between the check and the send: nothing to give back.
        }
    }
}
