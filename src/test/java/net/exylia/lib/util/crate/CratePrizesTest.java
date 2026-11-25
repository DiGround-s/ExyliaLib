package net.exylia.lib.util.crate;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.crate.internal.CrateRow;
import net.exylia.lib.util.crate.internal.CrateStore;
import net.exylia.lib.util.crate.internal.Prizes;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a key buys, against a real database: the roll, the duplicate refund, the
 * three reward modes, and the keys on the account.
 */
class CratePrizesTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();
    private static final String TOKEN = net.exylia.lib.util.reward.RewardCodec.encode(
            List.of(net.exylia.lib.util.reward.RewardEntry.command("say token").build()));

    /** A reward is written {@code id:tier}. */
    private static final class Catalogue implements CrateCatalogue<String> {
        final List<String> rewards = new ArrayList<>();

        @Override public @NotNull Collection<String> all() { return rewards; }
        @Override public @NotNull String id(@NotNull String reward) { return reward.split(":")[0]; }
        @Override public @NotNull String tier(@NotNull String reward) { return reward.split(":")[1]; }
        @Override public @NotNull String name(@NotNull String reward) { return id(reward); }
        @Override public @NotNull String icon(@NotNull String reward) { return "STONE"; }
        @Override public @NotNull String description(@NotNull String reward) { return ""; }
        @Override public ItemStack token(@NotNull String reward, @NotNull Player viewer) { return null; }
    }

    private Plugin plugin;
    private CrateStore store;
    private final Catalogue catalogue = new Catalogue();
    private final AtomicReference<CrateSettings> settings = new AtomicReference<>(new CrateSettings());
    private final AtomicInteger startKeys = new AtomicInteger();

    /** Whether the plugin has token items, and what became of them. */
    private final AtomicBoolean hasTokens = new AtomicBoolean();
    private final AtomicBoolean keepable = new AtomicBoolean(true);
    private final List<String> given = new ArrayList<>();
    private final List<String> kept = new ArrayList<>();

    private Prizes<String> prizes;
    private FakePlayer fake;
    private Player player;

    @BeforeAll
    static void install() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("Trims");
        TestDatabases.memory(plugin, "crates" + DATABASE.incrementAndGet());
        store = new CrateStore(plugin, startKeys::get, uuid -> { });
        prizes = new Prizes<>(catalogue, store, settings::get, (prize, viewer) -> !hasTokens.get() ? null
                : new Prizes.Token() {
                    @Override public void give(@NotNull Player to) { given.add(prize); }
                    @Override public boolean keep(@NotNull UUID to) {
                        if (keepable.get()) kept.add(prize);
                        return keepable.get();
                    }
                    @Override public @NotNull String payload() { return TOKEN; }
                });
        fake = new FakePlayer("Steve");
        player = fake.player();
        FakeServer.online(player);
    }

    @AfterEach
    void close() {
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void join() {
        await(store.open(player.getUniqueId()));
    }

    private void keys(int keys) {
        await(store.edit(player.getUniqueId(), row -> row.withKeys(keys)));
    }

    private int keys() {
        CrateRow row = store.row(player.getUniqueId());
        return row == null ? -1 : row.keys();
    }

    private void reward(CrateReward mode) {
        CrateSettings s = settings.get();
        settings.set(new CrateSettings(s.enabled(), s.startKeys(), s.duplicateRefund(), mode, s.blocks(),
                s.maxAtOnce(), s.spinFrames(), s.staggerSeconds(), s.onSpin(), s.onWin(), s.onDuplicate(),
                s.keyItem(), s.tiers()));
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    @Test
    void startKeysAreGivenOnlyWhenTheRowIsFirstCreated() {
        startKeys.set(3);
        join();
        assertEquals(3, keys());
        keys(1);
        await(store.writesForTests(player.getUniqueId()));

        store.close(player.getUniqueId());
        startKeys.set(10);
        join();
        assertEquals(1, keys(), "a second join handed the start keys out again");
    }

    @Test
    void anOfflineEditIsWrittenWithoutStartKeysAndReadOnJoin() {
        startKeys.set(5);
        UUID stranger = UUID.randomUUID();
        CrateRow written = await(store.edit(stranger, row -> row.withKeys(row.keys() + 2)));
        assertEquals(2, written.keys(), "an admin's row got the welcome keys");
        assertNull(store.row(stranger), "an offline row was cached");

        await(store.open(stranger));
        assertEquals(2, store.row(stranger).keys());
    }

    /** Writes an opening of these prizes, as the screens do once the player clicked. */
    private Prizes.Opened<String> open(int spend, String... rolled) {
        return await(prizes.commit(player.getUniqueId(), prizes.ticket(player, spend, List.of(rolled))));
    }

    @Test
    void spendingAKeyFailsAtZero() {
        catalogue.rewards.add("a:common");
        join();
        keys(1);
        assertEquals(Prizes.Result.OPENED, open(1, "a:common").result());
        assertEquals(Prizes.Result.NO_KEYS, open(1, "a:common").result(), "a key was spent that was not there");
        assertEquals(0, keys());
    }

    @Test
    void anOpeningWithNoKeysSpendsAndUnlocksNothing() {
        catalogue.rewards.add("a:common");
        join();
        assertEquals(Prizes.Result.NO_KEYS, open(1, "a:common").result());
        assertEquals(0, keys());
        assertFalse(store.row(player.getUniqueId()).isUnlocked("a"), "the last key's prize leaked without the key");
    }

    @Test
    void anEmptyCrateDoesNotChargeForTheDisappointment() {
        join();
        keys(2);
        assertNull(prizes.rollAll(1));
        assertEquals(2, keys());
    }

    @Test
    void theSameOperationAskedTwiceIsWrittenOnce() {
        catalogue.rewards.add("a:common");
        join();
        keys(3);
        Prizes.Ticket<String> ticket = prizes.ticket(player, 2, List.of("a:common", "a:common"));
        Prizes.Opened<String> first = await(prizes.commit(player.getUniqueId(), ticket));
        Prizes.Opened<String> again = await(prizes.commit(player.getUniqueId(), ticket));
        assertEquals(Prizes.Result.OPENED, again.result());
        assertEquals(first.outcomes(), again.outcomes(), "a replay answered something else than what was written");
        // Two keys spent once, one refunded for the second reel's duplicate.
        assertEquals(2, keys());
    }

    @Test
    void anAbortedOperationNeverLandsAndOneThatLandedCannotBeAborted() {
        catalogue.rewards.add("a:common");
        join();
        keys(1);
        Prizes.Ticket<String> given = prizes.ticket(player, 1, List.of("a:common"));
        assertEquals(CrateStore.Result.ABORTED, await(store.abort(player.getUniqueId(), given.id())).result());
        assertEquals(Prizes.Result.ABORTED, await(prizes.commit(player.getUniqueId(), given)).result(),
                "a write still in flight landed after the refusal");
        assertEquals(1, keys());

        Prizes.Ticket<String> landed = prizes.ticket(player, 1, List.of("a:common"));
        await(prizes.commit(player.getUniqueId(), landed));
        assertEquals(CrateStore.Result.APPLIED, await(store.abort(player.getUniqueId(), landed.id())).result());
        assertEquals(0, keys());
    }

    @Test
    void aGrantWithAnIdCountsOnce() {
        join();
        UUID uuid = player.getUniqueId();
        assertEquals(5, await(store.addKeys(uuid, 5, "grant:order-1")));
        assertEquals(5, await(store.addKeys(uuid, 5, "grant:order-1")), "the same purchase counted twice");
        assertEquals(6, await(store.addKeys(uuid, 1, null)));
        assertEquals(7, await(store.addKeys(uuid, 1, null)), "grants without an id are each their own");
    }

    // ------------------------------------------------------------------
    // The roll
    // ------------------------------------------------------------------

    @Test
    void theRollOnlyLandsOnRaritiesWithSomethingInThem() {
        catalogue.rewards.add("a:common");
        catalogue.rewards.add("b:legendary");
        catalogue.rewards.add("c:legendary");
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < 20_000; i++) {
            seen.merge(prizes.roll(), 1, Integer::sum);
        }
        // Weights 60 and 3 over the two rarities that hold anything: rare and
        // epic are empty and their weight goes nowhere.
        double legendary = (seen.getOrDefault("b:legendary", 0) + seen.getOrDefault("c:legendary", 0)) / 20_000.0;
        assertEquals(3.0 / 63.0, legendary, 0.01, "the legendary share does not follow the weights");
        // Inside a rarity every reward is equally likely.
        assertEquals(1.0, seen.get("b:legendary") / (double) seen.get("c:legendary"), 0.35);
    }

    @Test
    void aRewardNamingAMissingRarityLandsInTheFirst() {
        catalogue.rewards.add("a:mythic");
        assertEquals("common", prizes.tierOf("a:mythic"));
        assertEquals("a:mythic", prizes.roll());
    }

    // ------------------------------------------------------------------
    // What it hands over
    // ------------------------------------------------------------------

    @Test
    void somethingNewIsUnlockedAndADuplicateRefundsKeys() {
        catalogue.rewards.add("a:common");
        join();
        keys(2);

        Prizes.Opened<String> first = open(1, "a:common");
        assertEquals(Prizes.Status.WON, first.outcomes().get(0).status());
        assertTrue(store.row(player.getUniqueId()).isUnlocked("a"));
        assertEquals(1, keys());

        Prizes.Outcome<String> again = open(1, "a:common").outcomes().get(0);
        assertEquals(Prizes.Status.DUPLICATE, again.status());
        assertEquals(1, again.refunded());
        assertEquals(1, keys(), "the duplicate did not hand its key back");
    }

    @Test
    void twoReelsOfOneRewardInOneOpeningAreAWinAndADuplicate() {
        catalogue.rewards.add("a:common");
        join();
        keys(2);
        List<Prizes.Outcome<String>> both = open(2, "a:common", "a:common").outcomes();
        assertEquals(Prizes.Status.WON, both.get(0).status());
        assertEquals(Prizes.Status.DUPLICATE, both.get(1).status());
        assertEquals(1, keys());
    }

    @Test
    void itemModeIsNeverADuplicateAndUnlocksNothing() {
        catalogue.rewards.add("a:common");
        hasTokens.set(true);
        reward(CrateReward.ITEM);
        join();
        keys(3);

        for (int i = 0; i < 3; i++) {
            Prizes.Outcome<String> outcome = open(1, "a:common").outcomes().get(0);
            assertEquals(Prizes.Status.WON, outcome.status());
            assertTrue(outcome.token(), "a won token was not owed");
        }
        assertFalse(store.row(player.getUniqueId()).isUnlocked("a"));
    }

    @Test
    void itemModeWithoutTokensBehavesAsUnlock() {
        catalogue.rewards.add("a:common");
        reward(CrateReward.ITEM);
        join();
        keys(2);

        Prizes.Outcome<String> first = open(1, "a:common").outcomes().get(0);
        assertEquals(Prizes.Status.WON, first.status());
        assertFalse(first.token());
        assertTrue(store.row(player.getUniqueId()).isUnlocked("a"), "a key bought nothing");
        assertEquals(Prizes.Status.DUPLICATE, open(1, "a:common").outcomes().get(0).status());
    }

    @Test
    void bothUnlocksAndOwesTheTokenOnlyWhenNew() {
        catalogue.rewards.add("a:common");
        hasTokens.set(true);
        reward(CrateReward.BOTH);
        join();
        keys(2);

        assertTrue(open(1, "a:common").outcomes().get(0).token());
        Prizes.Outcome<String> again = open(1, "a:common").outcomes().get(0);
        assertEquals(Prizes.Status.DUPLICATE, again.status());
        assertFalse(again.token(), "a duplicate owed the token as well");
    }

    @Test
    void aTokenIsWrittenPendingWithTheOpeningAndTakenOnce() {
        catalogue.rewards.add("a:common");
        hasTokens.set(true);
        reward(CrateReward.ITEM);
        join();
        keys(1);
        UUID uuid = player.getUniqueId();
        Prizes.Ticket<String> ticket = prizes.ticket(player, 1, List.of("a:common"));
        await(prizes.commit(uuid, ticket));
        assertTrue(await(Databases.of(plugin).repository(net.exylia.lib.util.reward.PendingRewardRow.class)
                .exists(CrateStore.deliveryId(ticket.id(), 0))), "the token is not pending with the opening");

        assertTrue(await(prizes.takeDelivery(ticket.id(), 0)));
        assertFalse(await(prizes.takeDelivery(ticket.id(), 0)), "a token was taken twice");
    }

    @Test
    void aTokenLeftByACrashIsHandedOverOnTheNextJoinOnce() {
        catalogue.rewards.add("a:common");
        hasTokens.set(true);
        reward(CrateReward.ITEM);
        join();
        keys(1);
        UUID uuid = player.getUniqueId();
        Prizes.Ticket<String> ticket = prizes.ticket(player, 1, List.of("a:common"));
        await(prizes.commit(uuid, ticket));
        // The animation never revealed it: the next join claims the pending rewards.
        net.exylia.lib.util.reward.PendingRewards queue = net.exylia.lib.util.reward.PendingRewards.database(plugin);
        assertEquals(1, queue.claim(uuid).size(), "the token was lost with the animation");
        assertTrue(queue.claim(uuid).isEmpty(), "the token was handed over twice");
        assertFalse(await(prizes.takeDelivery(ticket.id(), 0)), "the reveal would hand it over again");
        assertEquals(0, keys());
    }

    @Test
    void aDisabledCrateSaysSo() {
        catalogue.rewards.add("a:common");
        CrateSettings s = settings.get();
        settings.set(new CrateSettings(false, s.startKeys(), s.duplicateRefund(), s.reward(), s.blocks(),
                s.maxAtOnce(), s.spinFrames(), s.staggerSeconds(), s.onSpin(), s.onWin(), s.onDuplicate(),
                s.keyItem(), s.tiers()));
        assertFalse(prizes.enabled());
    }
}
