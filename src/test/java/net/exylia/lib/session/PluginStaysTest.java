package net.exylia.lib.session;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three orderings a stay exists to keep, and the doors it must keep shut. */
class PluginStaysTest {

    private FakeBackend backend;
    private PluginStays stays;
    private PluginSessions other;
    private Player player;

    @BeforeEach
    void setUp() {
        Sessions.releaseAll();
        backend = new FakeBackend();
        stays = new PluginStays(null, new PluginSessions("ExyliaBetCore"), backend);
        other = new PluginSessions("ExyliaFFA");
        player = player(UUID.randomUUID());
    }

    @Test
    void enteringClaimsAndSavesButTakesNothingYet() {
        assertTrue(stays.enter(player, "match", null).isPresent());
        assertTrue(stays.isHeld(player.getUniqueId()));
        assertEquals(List.of("save"), backend.calls);
    }

    @Test
    void aPlayerAnotherPluginHoldsCannotEnter() {
        other.claim(player.getUniqueId(), "arena", null);
        assertTrue(stays.enter(player, "match", null).isEmpty());
        assertTrue(backend.calls.isEmpty());
    }

    @Test
    void aPlayerAlreadyStayingCannotEnterTwice() {
        stays.enter(player, "match", null);
        assertTrue(stays.enter(player, "match", null).isEmpty());
        assertEquals(List.of("save"), backend.calls);
    }

    @Test
    void theRestoreWaitsForTheSaveToLand() {
        CompletableFuture<Void> save = new CompletableFuture<>();
        backend.nextSave = save;
        stays.enter(player, "match", null);

        CompletableFuture<Void> left = stays.leave(player);
        assertFalse(backend.calls.contains("restore"));
        assertFalse(left.isDone());

        save.complete(null);
        assertTrue(backend.calls.contains("restore"));
        assertTrue(left.isDone());
    }

    @Test
    void theClaimIsReleasedOnlyAfterTheRestore() {
        CompletableFuture<Boolean> restore = new CompletableFuture<>();
        backend.nextRestore = restore;
        stays.enter(player, "match", null);

        stays.leave(player);
        assertTrue(stays.isHeld(player.getUniqueId()));
        assertFalse(Sessions.isFree(player.getUniqueId()));

        restore.complete(true);
        assertFalse(stays.isHeld(player.getUniqueId()));
        assertTrue(Sessions.isFree(player.getUniqueId()));
    }

    @Test
    void aPlayerWithNoSnapshotLeftIsStillSentHome() {
        backend.nextRestore = CompletableFuture.completedFuture(false);
        stays.enter(player, "match", null);
        stays.leave(player).join();
        assertTrue(backend.calls.contains("teleport"));
    }

    @Test
    void leavingHereNeverMovesThePlayer() {
        backend.nextRestore = CompletableFuture.completedFuture(false);
        stays.enter(player, "match", null);
        stays.leaveHere(player).join();
        assertFalse(backend.calls.contains("teleport"));
        assertTrue(Sessions.isFree(player.getUniqueId()));
    }

    @Test
    void aPlayerNotStayingIsLeftAlone() {
        stays.leave(player).join();
        assertTrue(backend.calls.isEmpty());
    }

    @Test
    void aSecondLeaveWhileTheFirstRunsDoesNothing() {
        CompletableFuture<Boolean> restore = new CompletableFuture<>();
        backend.nextRestore = restore;
        stays.enter(player, "match", null);
        stays.leave(player);
        stays.leave(player);
        restore.complete(true);
        assertEquals(1, backend.calls.stream().filter("restore"::equals).count());
    }

    @Test
    void dressingSomebodyWhoLeftTakesNothing() {
        stays.enter(player, "match", null);
        stays.dress(player, GameMode.ADVENTURE, () -> false).join();
        assertFalse(backend.calls.contains("strip"));
        stays.dress(player, GameMode.ADVENTURE, () -> true).join();
        assertTrue(backend.calls.contains("strip"));
    }

    private static Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(PluginStaysTest.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "Player";
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    /** Records what the rules asked for, in order. */
    private static final class FakeBackend implements PluginStays.Backend {

        final List<String> calls = new ArrayList<>();
        CompletableFuture<Void> nextSave = CompletableFuture.completedFuture(null);
        CompletableFuture<Boolean> nextRestore = CompletableFuture.completedFuture(true);

        @Override
        public CompletableFuture<Void> save(Player player) {
            calls.add("save");
            return nextSave;
        }

        @Override
        public CompletableFuture<Boolean> returnAndRestore(Player player,
                                                           @Nullable Function<Location, CompletableFuture<?>> goHome) {
            calls.add("restore");
            return nextRestore;
        }

        @Override
        public CompletableFuture<Integer> returnAndRestoreAll(Player player,
                                                              Function<Location, CompletableFuture<?>> goHome) {
            calls.add("restoreAll");
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public CompletableFuture<Void> teleport(Player player, @Nullable Location where) {
            calls.add("teleport");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> onEntity(Player player, Runnable work) {
            work.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean owns(Player player) {
            return false;
        }

        @Override
        public void close(Player player) {
            calls.add("close");
        }

        @Override
        public void putAway(Player player) {
        }

        @Override
        public void strip(Player player, GameMode mode) {
            calls.add("strip");
        }

        @Override
        public void error(String message, Throwable failure) {
            throw new AssertionError(message, failure);
        }
    }
}
