package net.exylia.lib.effect.internal;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

/**
 * Boss bars that stay up and change while they do, for the modules that keep
 * one per subject rather than playing a timed effect: a boss's health.
 *
 * <p>The same packets, and the same Bukkit fallback, the boss bar effect uses.
 * One id is one bar on one client; a bar shown to several players needs an id
 * per player on the fallback path.
 */
@ApiStatus.Internal
public final class LiveBars {

    private LiveBars() {
    }

    /** Shows a bar. Colour and overlay are Adventure's names, such as {@code purple} and {@code notched_10}. */
    public static void show(Player viewer, UUID id, Component title, float progress, String colour, String overlay) {
        Bars.bossBarAdd(viewer, id, title, progress, colour, overlay);
    }

    /** Moves a showing bar, its title too when it changed. */
    public static void update(Player viewer, UUID id, Component title, float progress, boolean titleChanged) {
        Bars.bossBarUpdate(viewer, id, title, progress, titleChanged);
    }

    /** Recolours a showing bar. */
    public static void style(Player viewer, UUID id, String colour, String overlay) {
        Bars.bossBarStyle(viewer, id, colour, overlay);
    }

    /** Takes a bar off a client. */
    public static void hide(Player viewer, UUID id) {
        Bars.bossBarRemove(viewer, id);
    }
}
