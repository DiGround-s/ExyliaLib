package net.exylia.lib.skull.internal;

import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Reads the skin of somebody who is already connected.
 *
 * <p>A player on the server carries their texture in their profile: it
 * arrived when they logged in. Asking Mojang for it would be a network round
 * trip for a string already in memory — and this is the common case, because
 * most heads shown in a menu belong to players in that menu.
 *
 * <p>Confined to its own class because it names Paper profile types, so a
 * Spigot server never loads it.
 */
final class OnlineSkins {

    /** Whether Paper's profile API is present, decided once. */
    private static final boolean AVAILABLE = detect();

    private OnlineSkins() {
    }

    /**
     * Returns the texture of a connected player.
     *
     * @param name the player name, may be {@code null} when the id is given
     * @param id   the player id, may be {@code null} when the name is given
     * @return the base64 texture, or {@code null} when they are not connected
     *         or the server cannot answer
     */
    static String textureOf(String name, UUID id) {
        if (!AVAILABLE) {
            return null;
        }
        try {
            Player player = id != null ? Bukkit.getPlayer(id) : Bukkit.getPlayerExact(name);
            if (player == null) {
                return null;
            }
            for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
                if ("textures".equals(property.getName())) {
                    String value = property.getValue();
                    return Textures.isValid(value) ? value : null;
                }
            }
            return null;
        } catch (Throwable unavailable) {
            // No server, or a platform whose profile does not carry textures.
            return null;
        }
    }

    private static boolean detect() {
        try {
            Class.forName("com.destroystokyo.paper.profile.ProfileProperty");
            return true;
        } catch (ClassNotFoundException spigot) {
            return false;
        }
    }
}
