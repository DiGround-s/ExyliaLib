package net.exylia.lib.region;

import net.exylia.lib.region.internal.SelectionRuntime;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second corner is a proposal, not an answer.
 *
 * <p>ExyliaCommons asked for a deliberate shift + left-click before it accepted
 * a selection, and the first port of this module answered the instant the
 * second corner was clicked — so an admin who misclicked had already created
 * the arena. This is that confirmation, and the tests that keep it.
 *
 * <p>Nothing here needs a running server: the selection is started with no
 * tool, no outline and no messages, which is exactly the part a server owns.
 */
class SelectionConfirmTest {

    private static final WorldIdentity WORLD = new WorldIdentity(UUID.randomUUID(), "world");

    @AfterEach
    void cleanRuntime() {
        SelectionRuntime.releaseAll();
    }

    @Test
    @DisplayName("both corners wait to be confirmed rather than answering")
    void awaitsConfirmation() {
        UUID playerId = UUID.randomUUID();
        SelectionSession session = begin(playerId, confirmed());

        SelectionRuntime.select(playerId, true, at(1, 2, 3));
        assertEquals(SelectionState.ACTIVE, session.state());

        SelectionRuntime.select(playerId, false, at(8, 9, 10));
        assertEquals(SelectionState.AWAITING_CONFIRMATION, session.state());
        assertFalse(session.result().toCompletableFuture().isDone(),
                "The second corner is a proposal; nothing has been answered yet");
        assertTrue(SelectionRuntime.selection("Owner", playerId).isPresent(),
                "A session waiting to be confirmed is still the player's");
    }

    @Test
    @DisplayName("confirming answers with the corners as they stand")
    void confirmCompletes() {
        UUID playerId = UUID.randomUUID();
        SelectionSession session = begin(playerId, confirmed());

        SelectionRuntime.select(playerId, true, at(1, 2, 3));
        SelectionRuntime.select(playerId, false, at(8, 9, 10));
        assertTrue(SelectionRuntime.confirm(playerId));

        assertEquals(SelectionState.COMPLETED, session.state());
        assertEquals(new SelectionResult(WORLD, at(1, 2, 3), at(8, 9, 10)),
                session.result().toCompletableFuture().join());
        assertTrue(SelectionRuntime.selection("Owner", playerId).isEmpty());
    }

    @Test
    @DisplayName("a corner can still be moved while the box waits, and the answer follows it")
    void cornersStayEditable() {
        UUID playerId = UUID.randomUUID();
        SelectionSession session = begin(playerId, confirmed());

        SelectionRuntime.select(playerId, true, at(1, 2, 3));
        SelectionRuntime.select(playerId, false, at(8, 9, 10));
        SelectionRuntime.select(playerId, false, at(40, 41, 42));
        assertEquals(SelectionState.AWAITING_CONFIRMATION, session.state(),
                "Correcting a corner must not answer, and must not throw the box away");

        SelectionRuntime.select(playerId, true, at(20, 21, 22));
        assertEquals(SelectionState.AWAITING_CONFIRMATION, session.state());

        assertTrue(SelectionRuntime.confirm(playerId));
        assertEquals(new SelectionResult(WORLD, at(20, 21, 22), at(40, 41, 42)),
                session.result().toCompletableFuture().join());
    }

    @Test
    @DisplayName("confirming a box that is not there does nothing")
    void confirmWithoutBothCorners() {
        UUID playerId = UUID.randomUUID();
        SelectionSession session = begin(playerId, confirmed());

        assertFalse(SelectionRuntime.confirm(playerId));
        SelectionRuntime.select(playerId, true, at(1, 2, 3));
        assertFalse(SelectionRuntime.confirm(playerId), "One corner is not a box");
        assertEquals(SelectionState.ACTIVE, session.state());
        assertFalse(session.result().toCompletableFuture().isDone());
    }

    @Test
    @DisplayName("confirming twice answers once")
    void confirmIsTerminal() {
        UUID playerId = UUID.randomUUID();
        begin(playerId, confirmed());

        SelectionRuntime.select(playerId, true, at(1, 2, 3));
        SelectionRuntime.select(playerId, false, at(8, 9, 10));
        assertTrue(SelectionRuntime.confirm(playerId));
        assertFalse(SelectionRuntime.confirm(playerId));
    }

    @Test
    @DisplayName("walking away from a box waiting to be confirmed cancels it")
    void cancelWhileAwaiting() {
        UUID playerId = UUID.randomUUID();
        SelectionSession session = begin(playerId, confirmed());

        SelectionRuntime.select(playerId, true, at(1, 2, 3));
        SelectionRuntime.select(playerId, false, at(8, 9, 10));
        assertTrue(session.cancel());

        assertEquals(SelectionState.CANCELLED, session.state());
        CompletionException failure = assertThrows(CompletionException.class,
                () -> session.result().toCompletableFuture().join());
        assertTrue(failure.getCause() instanceof CancellationException);
    }

    @Test
    @DisplayName("a caller that turned the confirmation off still answers on the second corner")
    void withoutConfirmation() {
        UUID playerId = UUID.randomUUID();
        SelectionSession session = begin(playerId, confirmed().toBuilder()
                .requireConfirmation(false)
                .build());

        SelectionRuntime.select(playerId, true, at(1, 2, 3));
        SelectionRuntime.select(playerId, false, at(8, 9, 10));

        assertEquals(SelectionState.COMPLETED, session.state());
        assertTrue(session.result().toCompletableFuture().isDone());
    }

    @Test
    @DisplayName("the session's own confirm is the same door the click uses")
    void sessionConfirm() {
        UUID playerId = UUID.randomUUID();
        SelectionSession session = begin(playerId, confirmed());

        SelectionRuntime.select(playerId, true, at(1, 2, 3));
        SelectionRuntime.select(playerId, false, at(8, 9, 10));

        assertTrue(session.confirm());
        assertEquals(SelectionState.COMPLETED, session.state());
    }

    // ------------------------------------------------------------------

    /** Confirmation on, and nothing that would need a server. */
    private static SelectionOptions confirmed() {
        return SelectionOptions.builder()
                .giveSelector(false)
                .feedback(false)
                .previewParticle(null)
                .build();
    }

    private static SelectionSession begin(UUID playerId, SelectionOptions options) {
        return SelectionRuntime.begin(plugin(), player(playerId), options);
    }

    private static BlockPosition at(int x, int y, int z) {
        return new BlockPosition(WORLD, x, y, z);
    }

    private static org.bukkit.entity.Player player(UUID id) {
        return (org.bukkit.entity.Player) Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "toString" -> "Player[" + id + ']';
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "Owner";
                    case "toString" -> "Plugin[Owner]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
