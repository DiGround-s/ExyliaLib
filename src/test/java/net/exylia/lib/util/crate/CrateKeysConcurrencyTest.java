package net.exylia.lib.util.crate;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.crate.internal.CrateJournal;
import net.exylia.lib.util.crate.internal.CrateOperation;
import net.exylia.lib.util.crate.internal.CrateRow;
import net.exylia.lib.util.crate.internal.CrateStore;
import net.exylia.lib.util.reward.PendingRewardRow;
import net.exylia.lib.util.reward.RewardCodec;
import net.exylia.lib.util.reward.RewardEntry;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two servers on one database, many threads, every operation sent more than
 * once, aborts racing commits, a crash between an opening and its tokens, and
 * a restart: the keys come out exact and every token is handed over once.
 *
 * <p>Runs on H2 always, and on MySQL when {@code CRATES_MYSQL} is
 * {@code host:port:database:user:password}.
 */
class CrateKeysConcurrencyTest {

    private static final int START = 60;
    private static final String TOKEN = RewardCodec.encode(List.of(RewardEntry.command("say token").build()));
    private static final AtomicInteger DATABASE = new AtomicInteger();

    @BeforeAll
    static void install() {
        FakeServer.install();
    }

    @AfterEach
    void close() {
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    @Test
    void h2Global() throws Exception {
        run(h2(), "");
    }

    @Test
    void h2Scoped() throws Exception {
        run(h2(), "s1");
    }

    @Test
    void mysqlGlobal() throws Exception {
        run(mysql(), "");
    }

    @Test
    void mysqlScoped() throws Exception {
        run(mysql(), "s1");
    }

    private Plugin[] h2() {
        Plugin[] servers = servers();
        TestDatabases.shared(servers[0], "concurrency" + DATABASE.incrementAndGet(), "ServerA", "ServerB");
        return servers;
    }

    private Plugin[] mysql() {
        String target = System.getenv("CRATES_MYSQL");
        Assumptions.assumeTrue(target != null && !target.isBlank(), "CRATES_MYSQL is not set");
        String[] part = target.split(":");
        Plugin[] servers = servers();
        TestDatabases.sharedRemote(servers[0], "mysql", part[0], Integer.parseInt(part[1]), part[2], part[3],
                part[4], "ServerA", "ServerB");
        return servers;
    }

    private static Plugin[] servers() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        return new Plugin[]{FakeServer.newPlugin("ServerA"), FakeServer.newPlugin("ServerB")};
    }

    /** One operation as the test sent it, and every answer it got. */
    private record Op(String id, String kind, int amount, String prize, List<Object> answers) {
    }

    private void run(Plugin[] servers, String scope) throws Exception {
        String owner = "Effects" + DATABASE.incrementAndGet();
        CrateStore a = CrateStore.sharedForTests(servers[0], owner, () -> START, scope);
        CrateStore b = CrateStore.sharedForTests(servers[1], owner, () -> START, "");
        List<UUID> players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (UUID uuid : players) {
            CompletableFuture.allOf(a.open(uuid), b.open(uuid)).get(30, TimeUnit.SECONDS);
        }

        Random random = new Random(7);
        List<Op> ops = new ArrayList<>();
        Map<String, UUID> whose = new HashMap<>();
        for (UUID uuid : players) {
            for (int i = 0; i < 35; i++) ops.add(op(whose, uuid, "open", 1, "p" + random.nextInt(8)));
            for (int i = 0; i < 15; i++) ops.add(op(whose, uuid, "grant", 2, null));
            for (int i = 0; i < 10; i++) ops.add(op(whose, uuid, "take", -1, null));
        }
        Set<String> aborted = new HashSet<>();
        List<Runnable> work = new ArrayList<>();
        for (Op op : ops) {
            int copies = 1 + random.nextInt(3);
            for (int c = 0; c < copies; c++) {
                CrateStore store = random.nextBoolean() ? a : b;
                work.add(() -> op.answers().add(join(send(store, whose.get(op.id()), op))));
            }
            if (op.kind().equals("open") && random.nextInt(4) == 0) {
                aborted.add(op.id());
                CrateStore store = random.nextBoolean() ? a : b;
                work.add(() -> op.answers().add(join(store.abort(whose.get(op.id()), op.id())).result()));
            }
        }
        // Plain unlocks on the same rows, as a shop would write them, mixed in.
        for (UUID uuid : players) {
            for (int i = 0; i < 10; i++) {
                String id = "bought" + i;
                work.add(() -> join((random.nextBoolean() ? a : b).edit(uuid, row -> row.withUnlocked(id))));
            }
        }
        Collections.shuffle(work, random);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<java.util.concurrent.Future<?>> running = new ArrayList<>();
        for (Runnable task : work) running.add(pool.submit(task));
        for (java.util.concurrent.Future<?> future : running) future.get(120, TimeUnit.SECONDS);
        pool.shutdown();

        Repository<CrateOperation> ledger = Databases.of(servers[0]).repository(CrateOperation.class);
        Repository<PendingRewardRow> pending = Databases.of(servers[0]).repository(PendingRewardRow.class);
        // A restart of server A: a new store, nothing in memory.
        CrateStore restarted = CrateStore.sharedForTests(servers[0], owner, () -> START, scope);
        List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        for (UUID uuid : players) {
            int keys = START;
            Set<String> won = new HashSet<>();
            List<String> owed = new ArrayList<>();
            for (Op op : ops) {
                if (!whose.get(op.id()).equals(uuid)) continue;
                CrateOperation row = join(ledger.findFresh(CrateOperation.key(owner, uuid, ledgerId(op)))).orElse(null);
                Set<Object> distinct = new HashSet<>(op.answers());
                distinct.remove(CrateStore.Result.NO_KEYS);
                assertTrue(distinct.size() <= 1, op + " was answered two ways");
                if (row == null) {
                    assertFalse(distinct.contains(CrateStore.Result.APPLIED), op + " answered applied, not in the ledger");
                    assertFalse(op.kind().equals("grant") || op.kind().equals("take"), op + " grant never landed");
                    continue;
                }
                CrateJournal.Entry entry = row.read();
                assertNotNull(entry);
                if (entry.state() == CrateJournal.State.ABORTED) {
                    assertTrue(aborted.contains(op.id()), op + " aborted by nobody");
                    assertFalse(distinct.contains(CrateStore.Result.APPLIED), op + " answered applied and aborted");
                    continue;
                }
                assertFalse(distinct.contains(CrateStore.Result.ABORTED), op + " answered aborted but landed");
                if (op.kind().equals("open")) {
                    CrateJournal.Reel reel = entry.reels().get(0);
                    keys -= entry.amount();
                    keys += reel.refunded();
                    if (reel.won()) won.add(reel.prizeId());
                    owed.add(CrateStore.deliveryId(op.id(), 0));
                } else {
                    keys += entry.amount();
                }
            }
            CrateRow now = join(restarted.fetch(uuid));
            assertEquals(keys, now.keys(), "keys of " + uuid);
            Set<String> unlocks = new HashSet<>(now.unlockedIds());
            for (int i = 0; i < 10; i++) assertTrue(unlocks.remove("bought" + i), "a plain unlock was lost");
            assertEquals(won, unlocks, "unlocks of " + uuid);
            for (String id : owed) assertTrue(join(pending.exists(id)), "a paid token is not pending: " + id);

            // Half of the tokens are revealed before the crash, raced by both servers; the rest after the restart.
            List<CompletableFuture<Boolean>> takes = new ArrayList<>();
            for (int i = 0; i < owed.size(); i++) {
                String op = owed.get(i).substring("crate:".length(), owed.get(i).lastIndexOf(':'));
                List<CrateStore> takers = i % 2 == 0 ? List.of(a, b, a) : List.of(restarted, b);
                for (CrateStore taker : takers) {
                    CompletableFuture<Boolean> taken = taker.takeDelivery(op, 0);
                    takes.add(taken.thenApply(yes -> {
                        if (yes) delivered.add(owed.get(0) + op);
                        return yes;
                    }));
                }
            }
            CompletableFuture.allOf(takes.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
            for (String id : owed) assertFalse(join(pending.exists(id)), "a token is still pending: " + id);
        }
        Set<String> once = new HashSet<>(delivered);
        assertEquals(once.size(), delivered.size(), "a token was handed over twice");
        long opened = 0;
        for (Op op : ops) {
            if (!op.kind().equals("open")) continue;
            CrateOperation row = join(ledger.findFresh(CrateOperation.key(owner, whose.get(op.id()), op.id()))).orElse(null);
            if (row != null && row.read().state() == CrateJournal.State.DONE) opened++;
        }
        assertEquals(opened, delivered.size(), "every paid token is handed over exactly once");
    }

    private static Op op(Map<String, UUID> whose, UUID uuid, String kind, int amount, String prize) {
        Op op = new Op(UUID.randomUUID().toString(), kind, amount, prize, Collections.synchronizedList(new ArrayList<>()));
        whose.put(op.id(), uuid);
        return op;
    }

    private static String ledgerId(Op op) {
        return op.kind().equals("open") ? op.id() : op.id();
    }

    private static CompletableFuture<?> send(CrateStore store, UUID uuid, Op op) {
        if (!op.kind().equals("open")) {
            return store.addKeys(uuid, op.amount(), op.id()).thenApply(keys -> CrateStore.Result.APPLIED);
        }
        String prize = op.prize();
        return store.commit(uuid, op.id(), 1, java.util.Arrays.asList(TOKEN), (paid, elsewhere) -> {
            boolean isNew = !paid.isUnlocked(prize) && !elsewhere.test(prize);
            CrateRow row = isNew ? paid.withUnlocked(prize) : paid.withKeys(paid.keys() + 1);
            return new CrateStore.Settled(row, List.of(new CrateJournal.Reel(isNew, prize, isNew ? 0 : 1, true)));
        }).thenApply(CrateStore.Commit::result);
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
