package net.exylia.lib.settings.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Table;

import java.util.UUID;

/**
 * One value a player changed away from its default.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * @param id        {@code <namespace>:<player>:<key>:<server>}
 * @param player    whose value
 * @param namespace the plugin's name, lower case
 * @param name      the setting's key
 * @param server    the server's network id for a per-server setting, empty for the network
 * @param value     the value, as text
 * @param updatedAt when it was written, in epoch milliseconds
 * @since 1.261.0
 */
@Table("exylia_player_settings")
@Index(columns = {"player", "namespace"})
public record StoredSetting(
        @Id(length = 255) String id,
        @Column UUID player,
        @Column(length = 64) String namespace,
        @Column(length = 64) String name,
        @Column(length = 64) String server,
        @Column(length = 255) String value,
        @Column("updated_at") long updatedAt) {

    /** The row id for a value. */
    public static String id(String namespace, UUID player, String key, String server) {
        return namespace + ':' + player + ':' + key + ':' + server;
    }

    /** The player a row id names, or {@code null} when it is not one of these. */
    public static UUID playerOf(Object id, String namespace) {
        if (id == null) return null;
        String[] parts = String.valueOf(id).split(":", 4);
        if (parts.length < 4 || !parts[0].equals(namespace)) return null;
        try {
            return UUID.fromString(parts[1]);
        } catch (IllegalArgumentException notOne) {
            return null;
        }
    }
}
