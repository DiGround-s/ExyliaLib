package net.exylia.lib.input.internal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Shared detection of players connected through a Bedrock bridge.
 *
 * <p>This utility deliberately lives independently of input transports because
 * menus, text, and other modules need the same answer. Floodgate is reached by
 * reflection and its methods are resolved once, preventing a hard linkage error
 * on ordinary Java-only servers and avoiding repeated reflective lookup on each
 * capability check.
 *
 * <p>The username-prefix fallback is a library-wide compatibility setting, not
 * an input option. It defaults to {@code *}, matching common Geyser setups, and
 * can be replaced when the library configuration is wired.
 */
public final class Bedrocks {

    private static final Access ACCESS = Access.detect();
    private static volatile String prefix = "*";

    private Bedrocks() {
    }

    /**
     * Returns whether the UUID belongs to a Bedrock player.
     *
     * <p>Floodgate identity wins when its API is available. The prefix is only a
     * fallback for installations that expose Bedrock users through Geyser but do
     * not install Floodgate; this avoids classifying a Java player by name when
     * Floodgate can answer authoritatively.
     */
    public static boolean isBedrock(@NotNull UUID playerId) {
        java.util.Objects.requireNonNull(playerId, "playerId");
        Boolean floodgate = ACCESS.isBedrock(playerId);
        if (floodgate != null) {
            return floodgate;
        }
        Player player = Bukkit.getPlayer(playerId);
        String configured = prefix;
        return player != null && !configured.isEmpty() && player.getName().startsWith(configured);
    }

    /**
     * Changes the shared fallback username prefix.
     *
     * <p>Internal until the library configuration owns this value. An empty
     * prefix disables fallback detection; accepting it as "every name" would
     * route all Java players into forms that their clients cannot display.
     */
    @ApiStatus.Internal
    public static void prefix(@NotNull String newPrefix) {
        prefix = java.util.Objects.requireNonNull(newPrefix, "newPrefix");
    }

    /** Whether Floodgate and the form adapter can currently serve requests. */
    static boolean formsAvailable() {
        if (!ACCESS.available()) {
            return false;
        }
        try {
            Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
            if (floodgate == null) {
                floodgate = Bukkit.getPluginManager().getPlugin("Floodgate");
            }
            return floodgate != null && floodgate.isEnabled() && BedrockForms.available();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private record Access(Object api, Method isFloodgatePlayer) {

        private static Access detect() {
            try {
                Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Object api = type.getMethod("getInstance").invoke(null);
                return new Access(api, type.getMethod("isFloodgatePlayer", UUID.class));
            } catch (Throwable absent) {
                return new Access(null, null);
            }
        }

        private boolean available() {
            return api != null && isFloodgatePlayer != null;
        }

        private Boolean isBedrock(UUID uuid) {
            if (!available()) {
                return null;
            }
            try {
                return (Boolean) isFloodgatePlayer.invoke(api, uuid);
            } catch (ReflectiveOperationException | RuntimeException broken) {
                return null;
            }
        }
    }
}
