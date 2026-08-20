package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a plugin is still allowed to do while it shuts itself down.
 *
 * <p>The library learns that a plugin is going away from
 * {@code PluginDisableEvent}, and that event fires <em>before</em> the plugin's
 * own {@code onDisable}: both Bukkit's {@code JavaPluginLoader} and Paper's
 * {@code PaperPluginInstanceManager} call {@code setEnabled(false)} only after
 * the event has been delivered. So the release that answers the event is the
 * last thing before the plugin's teardown, not the first thing after it — and
 * anything it drops, it drops out from under a plugin that is still running.
 *
 * <p>The case that made this a real bug is the most ordinary one there is: an
 * {@code onDisable} that saves. Releasing the datasource from the event handler
 * dropped the target's last owner, so the {@code saveAll} that followed came
 * back as "the database target is closing because its last plugin was
 * disabled" and the rows never reached the table — on the way out, where the
 * console is noisy and the server exits seconds later.
 */
class DisableOrderTest {

    private static final long TIMEOUT_SECONDS = 15L;
    private static final AtomicInteger DATABASE = new AtomicInteger();

    @Table("arrows_players")
    record PlayerData(@Id UUID uuid, @Column int arrows) {
    }

    private Plugin plugin;

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("ExyliaArrows");
        Databases.installForTests(plugin,
                SqlSettings.memory("h2", "disable" + DATABASE.incrementAndGet()));
    }

    @AfterEach
    void close() {
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    @Test
    @DisplayName("a save issued from onDisable still lands, because the event fires first")
    void saveFromOnDisableStillLands() {
        Repository<PlayerData> repository = Databases.of(plugin).repository(PlayerData.class);
        UUID uuid = UUID.randomUUID();

        // What ExyliaLib does when it sees the disable event. The plugin has
        // not run its own onDisable yet: on a real server this is the moment
        // just before setEnabled(false).
        eventPhaseRelease();

        // PlayerManager.shutdown(), the line that was failing in production.
        await(repository.saveAll(List.of(new PlayerData(uuid, 64))));

        assertEquals(Optional.of(new PlayerData(uuid, 64)), await(repository.find(uuid)),
                "A saveAll issued from onDisable must reach the table: the disable event"
                        + " fires before onDisable, so releasing the datasource there takes"
                        + " the database away from a plugin that is still shutting down.");
    }

    @Test
    @DisplayName("the deferred release still closes the plugin's view afterwards")
    void deferredReleaseStillHappens() {
        Databases.of(plugin).repository(PlayerData.class);
        eventPhaseRelease();

        // The tick after the event, which is the first moment onDisable is
        // guaranteed to be over. Deferring the release must not mean skipping
        // it: a lease nobody drops is a pool that outlives its plugin.
        FakeServer.tick(1);

        assertEquals(0, Databases.registered(),
                "The deferred release must still run: it is postponed by one tick,"
                        + " not abandoned.");
    }

    /**
     * The half of the teardown that answers {@code PluginDisableEvent}.
     *
     * <p>Mirrors what {@code ExyliaLib.onPluginDisable} does for the database:
     * nothing at the event, and the release scheduled for the tick after. The
     * production method touches every module in the library, which a database
     * test has no business booting; what is under test is the ordering, and
     * this is that ordering.
     */
    private void eventPhaseRelease() {
        String pluginName = plugin.getName();
        Tasks.of(plugin).run(() -> Databases.release(pluginName));
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for a database operation", interrupted);
        } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new AssertionError("A database operation did not complete", failure);
        }
    }
}
