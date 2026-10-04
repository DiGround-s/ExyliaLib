package net.exylia.lib.replay.internal;

import org.bukkit.GameMode;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Which entities a recording that watches a place picks up by itself.
 *
 * <p>Everything somebody standing there would have seen move. Not the things
 * plugins use as furniture &mdash; displays, interactions, markers, invisible
 * armour stands holding up a hologram &mdash; which are not part of what
 * happened and would draw a floating name over every replay. Not paintings
 * and item frames either, which do not move and are not part of the terrain
 * the recording keeps.
 */
@ApiStatus.Internal
final class Tracking {

    private Tracking() {
    }

    /** By what it is, whether or not it is in the world yet. */
    static boolean worthRecording(Entity entity) {
        if (entity instanceof Player player) {
            return player.getGameMode() != GameMode.SPECTATOR;
        }
        if (entity instanceof Display || entity instanceof Interaction
                || entity instanceof Marker || entity instanceof Hanging) {
            return false;
        }
        if (entity instanceof ArmorStand stand) {
            return !stand.isMarker() && stand.isVisible();
        }
        return entity.getType() != org.bukkit.entity.EntityType.UNKNOWN;
    }
}
