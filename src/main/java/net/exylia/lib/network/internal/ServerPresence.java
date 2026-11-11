package net.exylia.lib.network.internal;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.Storage;
import net.exylia.lib.network.NetworkGroups;
import net.exylia.lib.redis.Redis;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Says, on the shared database, which servers are running and under what name.
 *
 * <p>Ownership on a network is by {@code server-id}: the server holding a
 * player's balance, the server a mine belongs to. Two running servers under
 * one name both believe they own the same rows and write over each other, and
 * nothing in either log says why. Each server writes its row once a minute;
 * one that finds a live row of another process under its own name says so at
 * startup, and the setup refuses a name a live server already uses.
 *
 * <p>Off on H2: a server alone on its file has nobody to share a name with.
 *
 * @since 1.581.0
 */
public final class ServerPresence {

    /** How long a row counts as a running server after its last write. */
    public static final long ALIVE_MILLIS = 150_000L;
    private static final long PERIOD_TICKS = 20L * 60L;
    private static final String INSTANCE = UUID.randomUUID().toString();

    private ServerPresence() {
        throw new AssertionError("No instances.");
    }

    /** From the library's enable. */
    public static void start(@NotNull Plugin library) {
        if ("h2".equalsIgnoreCase(Databases.of(library).engine())) return;
        Repository<ServerRow> rows = Databases.of(library).repository(ServerRow.class);
        String id = Redis.serverId(library);
        rows.save(row(id));
        // Checked a period later rather than at startup: a server restarted a
        // minute ago left a fresh row of its last run, which is not another server.
        // Another process under this name has overwritten ours by then.
        Tasks.of(library).runAsyncLater(PERIOD_TICKS + 200L, () -> rows.find(id).thenAccept(found -> found
                .filter(other -> !INSTANCE.equals(other.instance()))
                .ifPresent(other -> library.getLogger().severe("Another running server uses the server-id '" + id
                        + "' too. Two servers under one name write over each other's balances and places. Give"
                        + " each server its own server-id in database.yml, or run /exylialib setup."))));
        Tasks.of(library).runAsyncTimer(PERIOD_TICKS, PERIOD_TICKS, () -> rows.save(row(id)));
    }

    /**
     * Whether a live server other than this one uses a name.
     *
     * @param storage whichever database is being asked
     * @param id      the name
     * @return the other server's row, when there is one
     */
    public static @NotNull Optional<ServerRow> takenBy(@NotNull Storage storage, @NotNull String id) {
        try {
            return Optional.ofNullable(storage.find(EntityModel.of(ServerRow.class), id).join())
                    .filter(ServerPresence::otherAlive);
        } catch (RuntimeException missing) {
            // A database that never had a server on it has no table yet.
            return Optional.empty();
        }
    }

    private static boolean otherAlive(@Nullable ServerRow row) {
        return row != null && !INSTANCE.equals(row.instance())
                && System.currentTimeMillis() - row.lastSeen() < ALIVE_MILLIS;
    }

    private static ServerRow row(String id) {
        return new ServerRow(id, INSTANCE, NetworkGroups.current(), System.currentTimeMillis());
    }
}
