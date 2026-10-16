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
 *
 * <p>Geyser without Floodgate signs Bedrock players in with their Java
 * account, so neither Floodgate nor the UUID can tell; when Geyser runs on
 * this server its own API is asked too.
 *
 * <p>The APIs are looked up on first use, never at class load, and looked up
 * again until their plugin is enabled: ExyliaLib loads at {@code STARTUP},
 * before either bridge, and a lookup cached from then would read every
 * player as Java.
 */
public final class Bedrocks {

    private static volatile Access floodgate;
    private static volatile Access geyser;

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
        if (playerId.getMostSignificantBits() == 0) {
            return true;
        }
        Boolean linked = floodgate().isBedrock(playerId);
        if (Boolean.TRUE.equals(linked)) {
            return true;
        }
        return Boolean.TRUE.equals(geyser().isBedrock(playerId));
    }

    /** Whether Floodgate and the form adapter can currently serve requests. */
    static boolean formsAvailable() {
        if (!floodgate().available()) {
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

    private static Access floodgate() {
        Access access = floodgate;
        if (access == null) {
            access = Access.detect("floodgate", "org.geysermc.floodgate.api.FloodgateApi",
                    "getInstance", "isFloodgatePlayer");
            if (access == null) {
                return Access.ABSENT;
            }
            floodgate = access;
        }
        return access;
    }

    private static Access geyser() {
        Access access = geyser;
        if (access == null) {
            access = Access.detect("Geyser-Spigot", "org.geysermc.geyser.api.GeyserApi",
                    "api", "isBedrockPlayer");
            if (access == null) {
                return Access.ABSENT;
            }
            geyser = access;
        }
        return access;
    }

    private record Access(Object api, Method check) {

        private static final Access ABSENT = new Access(null, null);

        /**
         * Reaches the API through the plugin's own loader, since ExyliaLib
         * softdepends on neither. {@code null} while the plugin is missing or
         * not yet enabled, which costs a map lookup, so it is asked again on
         * the next call; once it is enabled the answer is kept, broken or not.
         */
        private static Access detect(String plugin, String type, String instance, String check) {
            try {
                Plugin owner = Bukkit.getPluginManager().getPlugin(plugin);
                if (owner == null || !owner.isEnabled()) {
                    return null;
                }
                Class<?> api = Class.forName(type, true, owner.getClass().getClassLoader());
                Object value = api.getMethod(instance).invoke(null);
                return value == null ? ABSENT : new Access(value, api.getMethod(check, UUID.class));
            } catch (Throwable broken) {
                return ABSENT;
            }
        }

        private boolean available() {
            return api != null && check != null;
        }

        private Boolean isBedrock(UUID uuid) {
            if (!available()) {
                return null;
            }
            try {
                return (Boolean) check.invoke(api, uuid);
            } catch (ReflectiveOperationException | RuntimeException broken) {
                return null;
            }
        }
    }
}
