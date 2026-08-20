package net.exylia.lib.ui.internal;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Retitling a window somebody already has open.
 *
 * <p>The door in front of {@link TitlePackets}, so nothing else in the module
 * names a PacketEvents type and a server without it loads normally.
 *
 * <p>Retitling is a nicety, not a requirement: a title that stays on page one
 * is what the window would have said anyway before this existed. Everything
 * here therefore answers "no" rather than failing when it cannot be done.
 */
final class Titles {

    private static volatile boolean supported;

    private Titles() {
    }

    /** Starts listening, if PacketEvents is installed. */
    static void init(Plugin plugin) {
        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents");
        } catch (Throwable ignored) {
            supported = false;
            return;
        }
        supported = TitlePackets.install();
    }

    /** Returns whether a window's title can be changed while it is open. */
    static boolean isSupported() {
        return supported;
    }

    /**
     * Changes the title of the window a player has open.
     *
     * @param player who is looking
     * @param size   how many slots the window has
     * @param title  what it should now say
     * @return whether it changed
     */
    static boolean retitle(Player player, int size, Component title) {
        return supported && TitlePackets.retitle(player, size, title);
    }

    /** Forgets a player who left. */
    static void forget(UUID player) {
        if (supported) {
            TitlePackets.forget(player);
        }
    }
}
