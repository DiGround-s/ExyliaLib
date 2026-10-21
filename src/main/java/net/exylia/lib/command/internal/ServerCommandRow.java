package net.exylia.lib.command.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Table;

import java.util.UUID;

/**
 * Commands waiting for a player to be on a server, in the table
 * {@code ServerCommands} keeps.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * @param id      a random id, the key a claim deletes by
 * @param plugin  which plugin queued them
 * @param player  who they are run for
 * @param server  the {@code server-id} they run on, blank for whichever the player is on
 * @param lines   the command lines, as a JSON array of strings
 * @param data    the values substituted into them, as a JSON object of strings
 * @param queuedAt when they were queued, in epoch milliseconds
 * @since 1.337.0
 */
@Table("exylia_server_commands")
@Index(columns = {"plugin", "player"})
public record ServerCommandRow(
        @Id(length = 64) String id,
        @Column(length = 64) String plugin,
        @Column UUID player,
        @Column(length = 64) String server,
        @Column(length = Column.UNBOUNDED) String lines,
        @Column(length = Column.UNBOUNDED) String data,
        @Column("queued_at") long queuedAt) {
}
