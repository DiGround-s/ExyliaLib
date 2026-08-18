package net.exylia.lib.input.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.input.InputResult;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a pending question begins and ends.
 *
 * <p>This is the part ExyliaCommons got wrong. There, a request replaced by a
 * newer one ran no callback at all, so a menu waiting to reopen never did; a
 * request had no timeout, so a player who walked away left it pending forever;
 * and nothing guaranteed a single delivery, so a dialog packet and a close
 * event could both answer the same question.
 *
 * <p>Every one of those is a test here, against a fake transport, so they can
 * be proven without a server.
 */
class InputSessionTest {

    private Plugin plugin;
    private Player player;
    private FakeTransport transport;

    /** A transport that shows nothing and records what it was asked to do. */
    static final class FakeTransport implements Transport {

        final List<UUID> shown = new CopyOnWriteArrayList<>();
        final List<UUID> closed = new CopyOnWriteArrayList<>();
        volatile boolean canShow = true;
        private final TransportKind kind;

        FakeTransport() {
            this(TransportKind.CHAT);
        }

        FakeTransport(TransportKind kind) {
            this.kind = kind;
        }

        @Override
        public boolean show(@NotNull InputSession session) {
            if (!canShow) {
                return false;
            }
            shown.add(session.id());
            return true;
        }

        @Override
        public void close(@NotNull InputSession session) {
            closed.add(session.id());
        }

        @Override
        public @NotNull TransportKind kind() {
            return kind;
        }
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("InputTestPlugin", null);
        player = new FakePlayer("Steve").player();
        FakeServer.online(player);
        transport = new FakeTransport();
        InputRuntime.clearForTests();
        InputRuntime.installTransports(List.of(transport));
        InputRuntime.init(plugin);
    }

    @AfterEach
    void tearDown() {
        InputRuntime.clearForTests();
        FakeServer.reset();
    }

    private InputSession session() {
        return new InputSession("InputTestPlugin", player.getUniqueId(),
                new Object(), Duration.ofSeconds(30));
    }

    /**
     * Runs whatever the runtime scheduled, then reads the result.
     *
     * <p>Delivery is deliberately not inline: transports finish on a packet
     * thread or the async chat thread, and a consumer callback touching Bukkit
     * from there would crash. The runtime hops to the player's thread first, so
     * a test has to let that hop happen.
     */
    private static InputResult<Object> resultOf(CompletableFuture<InputResult<Object>> future) {
        FakeServer.tick(1);
        assertTrue(future.isDone(), "the request should have finished");
        return future.join();
    }

    /** Lets the runtime's scheduled work run. */
    private static void settle() {
        FakeServer.tick(1);
    }

    // ------------------------------------------------------------- answering

    @Test
    @DisplayName("an answer reaches the caller once")
    void completes() {
        InputSession session = session();
        CompletableFuture<InputResult<Object>> future = InputRuntime.submit(session, List.of());
        settle();

        session.complete("Boxing");

        InputResult<Object> result = resultOf(future);
        assertEquals(InputOutcome.COMPLETED, result.outcome());
        assertEquals("Boxing", result.value());
        assertTrue(transport.closed.contains(session.id()), "the window must be taken down");
    }

    @Test
    @DisplayName("only the first ending counts, whichever arrives first")
    void exactlyOnce() {
        // The real race: a dialog's submit packet and its close event both
        // arrive. Whichever loses must be a no-op, or a caller is answered
        // twice and the second answer overwrites the first.
        InputSession session = session();
        CompletableFuture<InputResult<Object>> future = InputRuntime.submit(session, List.of());
        settle();

        assertTrue(session.complete("first"));
        assertFalse(session.complete("second"), "a second answer must be ignored");
        assertFalse(session.end(InputOutcome.CANCELLED), "a late cancel must be ignored");

        assertEquals("first", resultOf(future).value());
    }

    @Test
    @DisplayName("a cancel is delivered, not silence")
    void cancels() {
        InputSession session = session();
        CompletableFuture<InputResult<Object>> future = InputRuntime.submit(session, List.of());

        InputRuntime.cancel(player.getUniqueId());

        assertEquals(InputOutcome.CANCELLED, resultOf(future).outcome());
        assertFalse(InputRuntime.hasActive(player.getUniqueId()));
    }

    // ------------------------------------------------------------ the bugs

    @Test
    @DisplayName("a replaced request tells its caller, instead of vanishing")
    void replacedIsDelivered() {
        // ExyliaCommons dropped the old session silently, so a caller waiting
        // to reopen its menu waited forever. And REPLACED is its own outcome
        // rather than a cancel, because reopening a menu on a replace is how
        // two menus end up fighting over the screen.
        InputSession first = session();
        CompletableFuture<InputResult<Object>> firstFuture = InputRuntime.submit(first, List.of());
        settle();

        InputSession second = session();
        CompletableFuture<InputResult<Object>> secondFuture = InputRuntime.submit(second, List.of());
        settle();

        assertEquals(InputOutcome.REPLACED, resultOf(firstFuture).outcome());
        assertFalse(secondFuture.isDone(), "the new request is still waiting");
        assertSame(second, InputRuntime.active(player.getUniqueId()));
        assertTrue(transport.closed.contains(first.id()), "the old window must be taken down");
    }

    @Test
    @DisplayName("a player who leaves ends their question")
    void disconnectEnds() {
        InputSession session = session();
        CompletableFuture<InputResult<Object>> future = InputRuntime.submit(session, List.of());

        InputRuntime.forget(player.getUniqueId());

        assertEquals(InputOutcome.DISCONNECTED, resultOf(future).outcome());
        assertNull(InputRuntime.active(player.getUniqueId()));
    }

    @Test
    @DisplayName("disabling the owning plugin ends its questions, and only its own")
    void releasePlugin() {
        // A pending question holds a future the dying plugin is waiting on.
        // Leaving it would hand a player a form whose answer has nowhere to go.
        InputSession mine = session();
        CompletableFuture<InputResult<Object>> future = InputRuntime.submit(mine, List.of());

        Player other = new FakePlayer("Alex").player();
        FakeServer.online(player, other);
        InputSession theirs = new InputSession("OtherPlugin", other.getUniqueId(),
                new Object(), Duration.ofSeconds(30));
        CompletableFuture<InputResult<Object>> otherFuture = InputRuntime.submit(theirs, List.of());

        InputRuntime.releasePlugin("InputTestPlugin");

        assertEquals(InputOutcome.SHUT_DOWN, resultOf(future).outcome());
        assertFalse(otherFuture.isDone(), "another plugin's question must be untouched");
    }

    @Test
    @DisplayName("shutting down ends everything still pending")
    void shutdownEnds() {
        InputSession session = session();
        CompletableFuture<InputResult<Object>> future = InputRuntime.submit(session, List.of());

        InputRuntime.shutdown();

        assertEquals(InputOutcome.SHUT_DOWN, resultOf(future).outcome());
    }

    @Test
    @DisplayName("one player has one question at a time")
    void oneAtATime() {
        InputRuntime.submit(session(), List.of());
        settle();
        assertTrue(InputRuntime.hasActive(player.getUniqueId()));

        InputSession second = session();
        InputRuntime.submit(second, List.of());
        settle();

        assertSame(second, InputRuntime.active(player.getUniqueId()));
    }

    // ---------------------------------------------------------- fallbacks

    @Test
    @DisplayName("a transport that cannot show hands over to the next one")
    void fallsBack() {
        // This is what makes a server without PacketEvents work: the dialog
        // transport declines and chat answers instead. Declining must be a
        // false, never an exception, or one missing plugin takes the module out.
        FakeTransport refusing = new FakeTransport(TransportKind.DIALOG);
        refusing.canShow = false;
        InputRuntime.clearForTests();
        InputRuntime.installTransports(List.of(refusing, transport));
        InputRuntime.init(plugin);

        InputSession session = session();
        CompletableFuture<InputResult<Object>> future = InputRuntime.submit(session, List.of());

        settle();
        assertTrue(refusing.shown.isEmpty(), "the first transport declined");
        assertTrue(transport.shown.contains(session.id()), "the second one showed it");
        assertFalse(future.isDone(), "the question is waiting for an answer");
    }

    @Test
    @DisplayName("when nothing can ask, the caller is told rather than left waiting")
    void unavailableWhenNothingCanShow() {
        // A command that asks a question and never hears back is a command
        // that looks like it hung.
        transport.canShow = false;
        InputSession session = session();

        CompletableFuture<InputResult<Object>> future = InputRuntime.submit(session, List.of());
        settle();

        assertEquals(InputOutcome.UNAVAILABLE, resultOf(future).outcome());
    }

    @Test
    @DisplayName("a question for an offline player ends at once")
    void offlinePlayer() {
        InputSession session = new InputSession("InputTestPlugin", UUID.randomUUID(),
                new Object(), Duration.ofSeconds(30));

        CompletableFuture<InputResult<Object>> future = InputRuntime.submit(session, List.of());

        assertEquals(InputOutcome.UNAVAILABLE, resultOf(future).outcome());
    }

    @Test
    @DisplayName("a caller that throws does not corrupt the runtime")
    void callbackExceptionsAreIsolated() {
        // The callback runs inside our delivery. A consumer bug must not take
        // out the session map, the packet thread, or the next question.
        InputSession session = session();
        CompletableFuture<InputResult<Object>> future = InputRuntime.submit(session, List.of());
        settle();
        AtomicInteger ran = new AtomicInteger();
        future.thenAccept(result -> {
            ran.incrementAndGet();
            throw new IllegalStateException("consumer bug");
        });

        session.complete("value");
        settle();

        assertEquals(1, ran.get());
        // The runtime is still usable afterwards.
        InputSession next = session();
        InputRuntime.submit(next, List.of());
        settle();
        assertSame(next, InputRuntime.active(player.getUniqueId()));
    }

    @Test
    @DisplayName("the window is taken down however the question ends")
    void closeAlwaysRuns() {
        for (Runnable ending : List.<Runnable>of(
                () -> InputRuntime.cancel(player.getUniqueId()),
                () -> InputRuntime.forget(player.getUniqueId()),
                () -> InputRuntime.releasePlugin("InputTestPlugin"))) {

            transport.closed.clear();
            InputSession session = session();
            InputRuntime.submit(session, List.of());
            settle();

            ending.run();
            settle();

            assertTrue(transport.closed.contains(session.id()),
                    "a window left open after its question ended is one the player cannot escape");
        }
    }
}
