package net.exylia.lib.region;

import net.exylia.lib.region.internal.SelectionRuntime;
import org.bukkit.Material;
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

class SelectionSessionTest {

    @AfterEach
    void cleanRuntime() {
        SelectionRuntime.releaseAll();
    }

    @Test
    @DisplayName("A session completes only after left then right corners and leaves the registry")
    void completionLifecycle() {
        Plugin plugin = plugin("Owner");
        UUID playerId = UUID.randomUUID();
        WorldIdentity world = new WorldIdentity(UUID.randomUUID(), "world");
        SelectionSession session = SelectionRuntime.begin(plugin, player(playerId), bare());

        assertEquals(SelectionState.ACTIVE, session.state());
        assertTrue(SelectionRuntime.select(playerId, false,
                new BlockPosition(world, 30, 40, 50)));
        assertEquals(SelectionState.ACTIVE, session.state());
        assertTrue(session.second().isPresent());

        BlockPosition first = new BlockPosition(world, 3, 4, 5);
        BlockPosition second = new BlockPosition(world, 8, 9, 10);
        assertTrue(SelectionRuntime.select(playerId, true, first));
        assertEquals(SelectionState.ACTIVE, session.state(),
                "A left click never completes even when a previous right corner exists");
        assertTrue(SelectionRuntime.select(playerId, false, second));

        assertEquals(SelectionState.COMPLETED, session.state());
        assertEquals(new SelectionResult(world, first, second),
                session.result().toCompletableFuture().join());
        assertTrue(SelectionRuntime.selection("Owner", playerId).isEmpty());
        assertFalse(session.cancel());
    }

    @Test
    @DisplayName("Same-world validation keeps the session active until a valid right corner")
    void sameWorldValidation() {
        UUID playerId = UUID.randomUUID();
        WorldIdentity firstWorld = new WorldIdentity(UUID.randomUUID(), "first");
        WorldIdentity secondWorld = new WorldIdentity(UUID.randomUUID(), "second");
        SelectionSession session = SelectionRuntime.begin(plugin("Owner"), player(playerId),
                bare().toBuilder().selectorMaterial(Material.STICK).build());

        SelectionRuntime.select(playerId, true, new BlockPosition(firstWorld, 1, 2, 3));
        SelectionRuntime.select(playerId, false, new BlockPosition(secondWorld, 4, 5, 6));
        assertEquals(SelectionState.ACTIVE, session.state());
        assertEquals(secondWorld, session.second().orElseThrow().world());
        assertFalse(session.result().toCompletableFuture().isDone());

        SelectionRuntime.select(playerId, false, new BlockPosition(firstWorld, 7, 8, 9));
        assertEquals(SelectionState.COMPLETED, session.state());
    }

    @Test
    @DisplayName("A player has one globally routed selector across plugin owners")
    void globalPlayerExclusivity() {
        UUID playerId = UUID.randomUUID();
        SelectionSession first = SelectionRuntime.begin(plugin("First"), player(playerId),
                bare());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SelectionRuntime.begin(plugin("Second"), player(playerId), bare()));
        assertTrue(failure.getMessage().contains("First"));
        assertTrue(SelectionRuntime.selection("First", playerId).isPresent());
        assertTrue(SelectionRuntime.selection("Second", playerId).isEmpty());

        assertTrue(first.cancel());
        SelectionSession second = SelectionRuntime.begin(plugin("Second"), player(playerId),
                bare());
        assertEquals("Second", second.owner());
    }

    @Test
    @DisplayName("Cancellation, close, owner release, and global release terminate stages")
    void cancellationAndReleaseLifecycle() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        SelectionSession first = SelectionRuntime.begin(plugin("Owner"), player(firstId),
                bare());
        SelectionSession second = SelectionRuntime.begin(plugin("Other"), player(secondId),
                bare());

        assertEquals(1, SelectionRuntime.release("Owner"));
        assertCancelled(first);
        assertEquals(SelectionState.ACTIVE, second.state());

        second.close();
        assertCancelled(second);
        assertFalse(second.cancel());

        SelectionSession third = SelectionRuntime.begin(plugin("Third"), player(UUID.randomUUID()),
                bare());
        SelectionRuntime.releaseAll();
        assertCancelled(third);
    }

    private static void assertCancelled(SelectionSession session) {
        assertEquals(SelectionState.CANCELLED, session.state());
        CompletionException failure = assertThrows(CompletionException.class,
                () -> session.result().toCompletableFuture().join());
        assertTrue(failure.getCause() instanceof CancellationException);
    }

    /**
     * A selection with nothing but the state machine.
     *
     * <p>No tool handed over, no outline, no messages — so nothing here needs a
     * running server, and what is asserted is the routing and the lifecycle.
     * The selector, the preview and the confirmation have their own tests.
     */
    private static SelectionOptions bare() {
        return SelectionOptions.builder()
                .giveSelector(false)
                .requireConfirmation(false)
                .feedback(false)
                .previewParticle(null)
                .build();
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

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "toString" -> "Plugin[" + name + ']';
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }
}
