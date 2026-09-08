package net.exylia.lib.effect.internal;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws the effects that show text.
 *
 * <p>Boss bars go out as packets when PacketEvents is available, and fall back
 * to the Bukkit API when it is not. Titles and action bars always take the
 * server's own Adventure API.
 *
 * <h2>Why boss bars are the only packet path</h2>
 * A boss bar sent as a packet exists only on the client, identified by a UUID
 * this class made up. The Bukkit path has to keep the {@link BossBar} object
 * alive to be able to remove it later, which is what the fallback map below is
 * for. That saving is real, and it is why the packet path stays.
 *
 * <h2>Why titles and action bars do not</h2>
 * Neither holds server state on either path, so the packet path bought nothing
 * — and it cost a great deal. PacketEvents serialises the component to NBT
 * eagerly, on the calling thread, on every send: an action bar has to be re-sent
 * about three times a second per viewer or the client fades it out, and a
 * countdown title redraws every tick. Paper hands the component to the netty
 * encoder instead and serialises it there, off the server thread entirely.
 *
 * <p>This showed up as the single most expensive thing the library did on a
 * profiled server: {@code AdventureSerializer.asNbtTag} and
 * {@code NBTSerializer.writeTag} under every action bar redraw.
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
        viewer.showTitle(Title.title(title, subtitle, Title.Times.times(
                Duration.ofMillis(fadeIn * 50L),
                Duration.ofMillis(stay * 50L),
                Duration.ofMillis(fadeOut * 50L))));
    }

    /**
     * Replaces the text of a showing title without restarting its fade.
     *
     * <p>The title parts are exactly the two text packets and nothing else, so
     * the timings the title is already running under are left alone. Re-sending
     * them would restart the fade and make a countdown pulse once a tick.
     */
    static void titleText(Player viewer, Component title, Component subtitle) {
        viewer.sendTitlePart(TitlePart.SUBTITLE, subtitle);
        viewer.sendTitlePart(TitlePart.TITLE, title);
    }

    static void clearTitle(Player viewer) {
        viewer.clearTitle();
    }

    // ------------------------------------------------------------------
    // Action bars
    // ------------------------------------------------------------------

    static void actionBar(Player viewer, Component text) {
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
