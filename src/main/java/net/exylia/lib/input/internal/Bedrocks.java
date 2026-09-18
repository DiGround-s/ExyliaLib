package net.exylia.lib.input.internal;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
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
 * <p>Without the API the UUID answers, the way Geyser asks it be done:
 * Floodgate gives an unlinked Bedrock player the UUID
 * {@code 00000000-0000-0000-xxxx-xxxxxxxxxxxx}, its XUID with zeroed high bits.
 * The name never does. Its prefix is a server setting that can be changed or
 * removed, and a Java player may pick a name that starts with it.
 */
public final class Bedrocks {

    private static final Access ACCESS = Access.detect();

    private Bedrocks() {
    }

    /**
     * Returns whether the UUID belongs to a Bedrock player.
     *
     * <p>Floodgate wins when its API is available, because only it knows a
     * linked player: one who joins from Bedrock under their Java account's UUID.
     * Otherwise, on a backend behind a proxy running Floodgate for instance, the
     * UUID is read the way Floodgate's own {@code isFloodgateId} reads it.
     */
    public static boolean isBedrock(@NotNull UUID playerId) {
        java.util.Objects.requireNonNull(playerId, "playerId");
        Boolean floodgate = ACCESS.isBedrock(playerId);
        if (floodgate != null) {
            return floodgate;
        }
        return playerId.getMostSignificantBits() == 0;
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
