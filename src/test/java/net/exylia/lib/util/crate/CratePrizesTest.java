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

    @Test
    void spendingAKeyFailsAtZero() {
        join();
        keys(1);
        assertTrue(store.spendKey(player.getUniqueId()));
        assertFalse(store.spendKey(player.getUniqueId()), "a key was spent that was not there");
        assertEquals(0, keys());
    }

    @Test
    void aDrawWithNoKeysSpendsNothing() {
        catalogue.rewards.add("a:common");
        join();
        assertEquals(Prizes.Status.NO_KEYS, prizes.draw(player).status());
        assertEquals(0, keys());
    }

    @Test
    void anEmptyCrateDoesNotChargeForTheDisappointment() {
        join();
        keys(2);
        assertEquals(Prizes.Status.EMPTY, prizes.draw(player).status());
        assertEquals(2, keys());
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

        Prizes.Draw<String> first = prizes.draw(player);
        assertEquals(Prizes.Status.DRAWN, first.status());
        assertEquals(Prizes.Status.WON, prizes.award(player, first.prize()).status());
        assertTrue(store.row(player.getUniqueId()).isUnlocked("a"));
        assertEquals(1, keys());

        Prizes.Outcome<String> again = prizes.award(player, prizes.draw(player).prize());
        assertEquals(Prizes.Status.DUPLICATE, again.status());
        assertEquals(1, again.refunded());
        assertEquals(1, keys(), "the duplicate did not hand its key back");
    }

    @Test
    void itemModeIsNeverADuplicateAndUnlocksNothing() {
        catalogue.rewards.add("a:common");
        hasTokens.set(true);
        reward(CrateReward.ITEM);
        join();

        for (int i = 0; i < 3; i++) {
            assertEquals(Prizes.Status.WON, prizes.award(player, "a:common").status());
        }
        assertEquals(List.of("a:common", "a:common", "a:common"), given);
        assertFalse(store.row(player.getUniqueId()).isUnlocked("a"));
    }

    @Test
    void itemModeWithoutTokensBehavesAsUnlock() {
        catalogue.rewards.add("a:common");
        reward(CrateReward.ITEM);
        join();

        assertEquals(Prizes.Status.WON, prizes.award(player, "a:common").status());
        assertTrue(store.row(player.getUniqueId()).isUnlocked("a"), "a key bought nothing");
        assertEquals(Prizes.Status.DUPLICATE, prizes.award(player, "a:common").status());
    }

    @Test
    void bothUnlocksAndGivesTheTokenOnlyWhenNew() {
        catalogue.rewards.add("a:common");
        hasTokens.set(true);
        reward(CrateReward.BOTH);
        join();

        assertEquals(Prizes.Status.WON, prizes.award(player, "a:common").status());
        assertEquals(Prizes.Status.DUPLICATE, prizes.award(player, "a:common").status());
        assertEquals(List.of("a:common"), given, "a duplicate handed the token over as well");
    }

    @Test
    void aPlayerLeavingKeepsTheirTokenForLater() {
        catalogue.rewards.add("a:common");
        hasTokens.set(true);
        reward(CrateReward.ITEM);
        join();

        prizes.awardLater(player, "a:common");
        assertEquals(List.of(), given);
        assertEquals(List.of("a:common"), kept);
    }

    @Test
    void aTokenThatCannotBeKeptGivesItsKeyBack() {
        catalogue.rewards.add("a:common");
        hasTokens.set(true);
        keepable.set(false);
        reward(CrateReward.ITEM);
        join();

        prizes.awardLater(player, "a:common");
        assertEquals(1, keys(), "a key was spent on a token nobody received");
    }

    @Test
    void aDisabledCrateDrawsNothing() {
        catalogue.rewards.add("a:common");
        CrateSettings s = settings.get();
        settings.set(new CrateSettings(false, s.startKeys(), s.duplicateRefund(), s.reward(), s.blocks(),
                s.maxAtOnce(), s.spinFrames(), s.staggerSeconds(), s.onSpin(), s.onWin(), s.onDuplicate(),
                s.keyItem(), s.tiers()));
        join();
        keys(1);
        assertEquals(Prizes.Status.DISABLED, prizes.draw(player).status());
        assertEquals(1, keys());
    }
}
