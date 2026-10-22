package net.exylia.lib.session;

import net.exylia.lib.session.internal.Recovering;
import net.exylia.lib.util.snapshot.SnapshotPart;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        Recovering.clearForTests();
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
    void aClaimAlreadyHeldBecomesAStay() {
        PluginSessions mine = new PluginSessions("ExyliaBetCore");
        Claim loan = mine.claim(player.getUniqueId(), "queue", null).orElseThrow();
        assertTrue(stays.enter(player, "match", null).isEmpty());
        assertTrue(stays.enter(player, loan.as("match")).isPresent());
        assertEquals(List.of("save"), backend.calls);
    }

    @Test
    void aClaimThatIsNotOursCannotBecomeAStay() {
        Claim theirs = other.claim(player.getUniqueId(), "arena", null).orElseThrow();
        assertTrue(stays.enter(player, theirs).isEmpty());
        assertTrue(backend.calls.isEmpty());
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

    @Test
    void aNamedContextIsSavedAndRestoredInPart() {
        Set<SnapshotPart> parts = SnapshotPart.allExcept(SnapshotPart.INVENTORY);
        stays.enter(player, "duel", null, "duel_risk");
        stays.leave(player, parts).join();
        assertEquals(List.of("duel_risk", "duel_risk"), backend.contexts);
        assertEquals(parts, backend.restoredParts);
        assertTrue(Sessions.isFree(player.getUniqueId()));

        backend.contexts.clear();
        stays.enter(player, "duel", null);
        stays.leave(player).join();
        assertEquals(List.of(PluginStays.CONTEXT, PluginStays.CONTEXT), backend.contexts);
        assertEquals(SnapshotPart.ALL, backend.restoredParts);
    }

    @Test
    void looseItemsAreKeptOnlyWhenTheInventoryIsNotRestored() {
        stays.enter(player, "duel", null);
        stays.leave(player).join();
        assertTrue(backend.calls.contains("putAway"));

        backend.calls.clear();
        stays.enter(player, "duel", null, "duel_risk");
        stays.leave(player, SnapshotPart.allExcept(SnapshotPart.INVENTORY)).join();
        assertTrue(backend.calls.contains("putAwayKeep"));
    }

    @Test
    void recoveringInPartPutsThatContextBackBeforeTheRest() {
        Set<SnapshotPart> parts = SnapshotPart.allExcept(SnapshotPart.INVENTORY);
        assertEquals(1, stays.recover(player, "duel_risk", parts).join());
        assertEquals(List.of("restore", "restoreAll"), backend.calls);
        assertEquals(parts, backend.restoredParts);
    }

    @Test
    void aRecoveringPlayerIsHeldUntilTheRestoreEnds() {
        CompletableFuture<Integer> recovery = new CompletableFuture<>();
        backend.nextRecover = recovery;
        UUID uuid = player.getUniqueId();

        CompletableFuture<Integer> recovered = stays.recover(player);
        assertTrue(stays.isHeld(uuid));
        assertTrue(Stays.isRecovering(uuid));
        assertFalse(Recovering.allows(uuid, "/sell all"));
        assertTrue(stays.enter(player, "match", null).isEmpty(), "nothing enters before the restore lands");

        recovery.complete(1);
        assertEquals(1, recovered.join());
        assertFalse(stays.isHeld(uuid));
        assertFalse(Stays.isRecovering(uuid));
        assertTrue(Recovering.allows(uuid, "/sell all"));
    }

    @Test
    void aFailedRecoveryStillLetsThePlayerGo() {
        CompletableFuture<Integer> recovery = new CompletableFuture<>();
        backend.nextRecover = recovery;
        backend.quiet = true;
        stays.recover(player);
        recovery.completeExceptionally(new IllegalStateException("database down"));
        assertFalse(Stays.isRecovering(player.getUniqueId()));
    }

    @Test
    void allowedCommandsPassWhileRecovering() {
        backend.nextRecover = new CompletableFuture<>();
        stays.allowWhileRecovering("/MSG", "login");
        stays.recover(player);
        UUID uuid = player.getUniqueId();
        assertTrue(Recovering.allows(uuid, "/msg Steve hi"));
        assertTrue(Recovering.allows(uuid, "/essentials:msg Steve hi"));
        assertTrue(Recovering.allows(uuid, "/LOGIN secret"));
        assertFalse(Recovering.allows(uuid, "/ah"));
    }

    @Test
    void recoveringLocallyAsksForThisServersSnapshotsOnly() {
        stays.recoverLocal(player).join();
        stays.recoverLocal(player, "duel_risk", SnapshotPart.ALL).join();
        assertEquals(List.of("restoreAllLocal", "restoreLocal", "restoreAllLocal"), backend.calls);
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
        CompletableFuture<Integer> nextRecover = CompletableFuture.completedFuture(0);
        final List<String> contexts = new ArrayList<>();
        Set<SnapshotPart> restoredParts;

        @Override
        public CompletableFuture<Void> save(Player player, String context) {
            calls.add("save");
            contexts.add(context);
            return nextSave;
        }

        @Override
        public CompletableFuture<Boolean> returnAndRestore(Player player, String context,
                                                           @Nullable Function<Location, CompletableFuture<?>> goHome,
                                                           Set<SnapshotPart> parts, boolean thisServerOnly) {
            calls.add(thisServerOnly ? "restoreLocal" : "restore");
            contexts.add(context);
            restoredParts = parts;
            return nextRestore;
        }

        @Override
        public CompletableFuture<Integer> returnAndRestoreAll(Player player,
                                                              Function<Location, CompletableFuture<?>> goHome,
                                                              boolean thisServerOnly) {
            calls.add(thisServerOnly ? "restoreAllLocal" : "restoreAll");
            return nextRecover;
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
        public void putAway(Player player, boolean keep) {
            calls.add(keep ? "putAwayKeep" : "putAway");
        }

        @Override
        public void strip(Player player, GameMode mode) {
            calls.add("strip");
        }

        boolean quiet;

        @Override
        public void error(String message, Throwable failure) {
            if (quiet) {
                return;
            }
            throw new AssertionError(message, failure);
        }
    }
}
