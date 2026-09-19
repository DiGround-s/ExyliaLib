package net.exylia.lib.util.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Table;

import java.util.UUID;

/**
 * One running cooldown of {@code NetworkCooldowns}.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * @param id        the namespace, the player and the key, {@code shop:<uuid>:repair}
 * @param player    whose cooldown
 * @param namespace the plugin's namespace
 * @param name      the key inside it
 * @param expiresAt when it ends, in epoch milliseconds
 * @since 1.184.0
 */
@Table("exylia_network_cooldowns")
@Index(columns = {"player", "namespace"})
public record NetworkCooldownRow(
        @Id(length = 255) String id,
        @Column UUID player,
        @Column(length = 64) String namespace,
        @Column(length = 128) String name,
        @Column("expires_at") long expiresAt) {
}
