package net.exylia.lib.effect.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBossBar;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;

/**
 * Sends effects as packets.
 *
 * <p>Every PacketEvents type in the module is referenced here and nowhere else,
 * so a server without the plugin never loads this class and never sees a missing
 * one. {@link Packets} decides whether it is safe to call.
 *
 * <p>Only the effect that would otherwise leave server-side state lives here: a
 * boss bar sent as a packet is nothing the server has to track. Titles and
 * action bars went back to the server's own Adventure API, which serialises off
 * the server thread; see {@link Bars}. Sounds and particles go through Bukkit
 * too: a wrapper PacketEvents cannot map to the client's version is written as
 * a sound holder of zero with nothing behind it, and the client disconnects on
 * it. The server's own encoder never writes a sound it does not know.
 */
final class PacketSender {

    private PacketSender() {
    }

    /** Returns whether PacketEvents is loaded and ready to accept packets. */
    static boolean ready() {
        try {
            var api = PacketEvents.getAPI();
            return api != null && api.isLoaded() && api.isInitialized();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void send(Player player, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    // ------------------------------------------------------------------
    // Boss bar
    // ------------------------------------------------------------------

    static void bossBarAdd(Player player, UUID id, Component title, float progress,
                           String colour, String overlay) {
        WrapperPlayServerBossBar packet = new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.ADD);
        packet.setTitle(title);
        packet.setHealth(progress);
        packet.setColor(colour(colour));
        packet.setOverlay(overlay(overlay));
        packet.setFlags(EnumSet.noneOf(BossBar.Flag.class));
        send(player, packet);
    }

    /** Updates only the fill, which is what a timer changes every tick. */
    static void bossBarProgress(Player player, UUID id, float progress) {
        WrapperPlayServerBossBar packet =
                new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.UPDATE_HEALTH);
        packet.setHealth(progress);
        send(player, packet);
    }

    static void bossBarTitle(Player player, UUID id, Component title) {
        WrapperPlayServerBossBar packet =
                new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.UPDATE_TITLE);
        packet.setTitle(title);
        send(player, packet);
    }

    static void bossBarStyle(Player player, UUID id, String colour, String overlay) {
        WrapperPlayServerBossBar packet =
                new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.UPDATE_STYLE);
        packet.setColor(colour(colour));
        packet.setOverlay(overlay(overlay));
        send(player, packet);
    }

    static void bossBarRemove(Player player, UUID id) {
        send(player, new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.REMOVE));
    }

    private static BossBar.Color colour(String name) {
        try {
            return BossBar.Color.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BossBar.Color.PURPLE;
        }
    }

    private static BossBar.Overlay overlay(String name) {
        try {
            return BossBar.Overlay.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BossBar.Overlay.PROGRESS;
        }
    }
}
