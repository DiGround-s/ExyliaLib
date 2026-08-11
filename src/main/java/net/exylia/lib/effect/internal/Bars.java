package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Timer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws the effects that show text.
 *
 * <p>Each method sends packets when PacketEvents is available and falls back to
 * the Bukkit API when it is not, so the caller never has to know which path is
 * in use.
 *
 * <p>The packet path is preferred because the server keeps nothing: a boss bar
 * sent as a packet exists only on the client, identified by a UUID this class
 * made up. The Bukkit path has to keep the {@link BossBar} object alive to be
 * able to remove it later, which is what the fallback map below is for.
 */
final class Bars {

    /**
     * Boss bars created through the Bukkit API, so they can be removed.
     *
     * <p>Only used on the fallback path. On the packet path this stays empty,
     * which is the whole point.
     */
    private static final Map<UUID, BossBar> FALLBACK = new ConcurrentHashMap<>();

    private Bars() {
    }

    // ------------------------------------------------------------------
    // Titles
    // ------------------------------------------------------------------

    static void title(Player viewer, Component title, Component subtitle,
                      int fadeIn, int stay, int fadeOut) {
        if (Packets.available()) {
            PacketSender.title(viewer, title, subtitle, fadeIn, stay, fadeOut);
            return;
        }
        viewer.showTitle(net.kyori.adventure.title.Title.title(title, subtitle,
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(fadeIn * 50L),
                        java.time.Duration.ofMillis(stay * 50L),
                        java.time.Duration.ofMillis(fadeOut * 50L))));
    }

    /**
     * Replaces the text of a showing title without restarting its fade.
     *
     * <p>The Bukkit API has no way to express this, so the fallback re-sends the
     * title with no fade at all: a countdown that pulsed once a second would be
     * worse than one that simply does not fade.
     */
    static void titleText(Player viewer, Component title, Component subtitle,
                          int stay) {
        if (Packets.available()) {
            PacketSender.titleText(viewer, title, subtitle);
            return;
        }
        title(viewer, title, subtitle, 0, stay, 0);
    }

    static void clearTitle(Player viewer) {
        if (Packets.available()) {
            // An empty title with zero timings is how a title is cleared on the
            // wire; there is no dedicated packet before it expires.
            PacketSender.title(viewer, Component.empty(), Component.empty(), 0, 1, 0);
            return;
        }
        viewer.clearTitle();
    }

    // ------------------------------------------------------------------
    // Action bars
    // ------------------------------------------------------------------

    static void actionBar(Player viewer, Component text) {
        if (Packets.available()) {
            PacketSender.actionBar(viewer, text);
            return;
        }
        viewer.sendActionBar(text);
    }

    static void clearActionBar(Player viewer) {
        actionBar(viewer, Component.empty());
    }

    // ------------------------------------------------------------------
    // Boss bars
    // ------------------------------------------------------------------

    static void bossBarAdd(Player viewer, UUID id, Component title, float progress,
                           String colour, String overlay) {
        if (Packets.available()) {
            PacketSender.bossBarAdd(viewer, id, title, progress, colour, overlay);
            return;
        }
        BossBar bar = BossBar.bossBar(title, clamp(progress), colour(colour), overlay(overlay));
        FALLBACK.put(id, bar);
        viewer.showBossBar(bar);
    }

    static void bossBarUpdate(Player viewer, UUID id, Component title, float progress,
                              boolean titleChanged) {
        if (Packets.available()) {
            PacketSender.bossBarProgress(viewer, id, clamp(progress));
            if (titleChanged) {
                PacketSender.bossBarTitle(viewer, id, title);
            }
            return;
        }
        BossBar bar = FALLBACK.get(id);
        if (bar != null) {
            bar.progress(clamp(progress));
            if (titleChanged) {
                bar.name(title);
            }
        }
    }

    static void bossBarRemove(Player viewer, UUID id) {
        if (Packets.available()) {
            PacketSender.bossBarRemove(viewer, id);
            return;
        }
        BossBar bar = FALLBACK.remove(id);
        if (bar != null) {
            viewer.hideBossBar(bar);
        }
    }

    /**
     * Keeps progress inside the range the protocol allows.
     *
     * <p>Adventure throws on an out-of-range value, so a rounding error in a
     * timer would otherwise take down the effect rather than showing a full bar.
     */
    private static float clamp(float progress) {
        return Math.clamp(progress, 0f, 1f);
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

    /** Forgets a fallback bar, so a stopped effect leaves nothing behind. */
    static void forget(UUID id) {
        FALLBACK.remove(id);
    }

    /** Returns how many fallback bars are held, so a test can prove there is no leak. */
    static int fallbackCount() {
        return FALLBACK.size();
    }
}
