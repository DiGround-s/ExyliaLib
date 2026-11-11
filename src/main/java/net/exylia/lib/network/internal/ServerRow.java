package net.exylia.lib.network.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;

/**
 * One server that runs on the shared database, as it last said it was alive.
 *
 * @param id       its {@code server-id}
 * @param instance a random id for this run, so a second process under the same name is told apart
 * @param group    its {@code network-group}, blank for none
 * @param lastSeen when it last wrote the row, epoch millis
 * @since 1.581.0
 */
@Table(value = "exylia_servers", data = DataKind.CACHE, cache = false)
public record ServerRow(
        @Id(length = 64) String id,
        @Column(length = 36) String instance,
        @Column(length = 32) String group,
        @Column("last_seen") long lastSeen) {
}
