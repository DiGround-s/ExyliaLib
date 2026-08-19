package net.exylia.lib.util.combat.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.combat.Combat;
import net.exylia.lib.util.combat.CombatBridge;
import net.exylia.lib.util.combat.CombatStats;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the module promises around a combat plugin.
 *
 * <p>Not what PvPManager does — that is their test suite. What is worth proving
 * here is the part a plugin relies on without thinking: a hot question is not
 * asked twice, a write is never answered from a cache, a plugin that throws
 * cannot stop the server from fighting, and a server with nothing installed
 * behaves like a server where nobody is in combat.
 */
class CombatTest {

    private FakePlayer attacker;
    private FakePlayer defender;
    private FakeProvider provider;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        attacker = new FakePlayer("Attacker");
        defender = new FakePlayer("Defender");
        FakeServer.online(attacker.player(), defender.player());

        provider = new FakeProvider();
        CombatRuntime.install(provider);
    }

    @AfterEach
    void tearDown() {
        CombatRuntime.shutdown();
        FakeServer.reset();
    }

    @Test
    @DisplayName("tagging a player makes them tagged")
    void tags() {
        Combat.tag(defender.player(), attacker.player(), Duration.ofSeconds(15));

        assertTrue(Combat.isTagged(defender.player()));
        assertEquals(Duration.ofSeconds(10), Combat.remaining(defender.player()));
    }

    @Test
    @DisplayName("asking twice in a row only reaches the plugin once")
    void cachesTheTag() {
        Combat.isTagged(defender.player());
        Combat.isTagged(defender.player());
        Combat.isTagged(defender.player());

        assertEquals(1, provider.tagQuestions(),
                "this runs on every damage event; asking their map each time is the"
                        + " cost this cache exists to avoid");
    }

    @Test
    @DisplayName("tagging drops the cached answer, so the next read is the truth")
    void writeInvalidatesTheCache() {
        assertFalse(Combat.isTagged(defender.player()));

        Combat.tag(defender.player(), attacker.player());

        assertTrue(Combat.isTagged(defender.player()),
                "a cached 'not in combat' that outlives the tag is a player who can"
                        + " warp out of a fight");
    }

    @Test
    @DisplayName("untagging drops the cached answer too")
    void untagInvalidatesTheCache() {
        Combat.tag(defender.player(), attacker.player());
        assertTrue(Combat.isTagged(defender.player()));

        Combat.untag(defender.player());

        assertFalse(Combat.isTagged(defender.player()));
    }

    @Test
    @DisplayName("the remaining time is never cached, because it is a countdown")
    void remainingIsNotCached() {
        Combat.tag(defender.player(), attacker.player());
        provider.clear();

        Combat.remaining(defender.player());
        provider.left = Duration.ofSeconds(3);
        Duration second = Combat.remaining(defender.player());

        assertEquals(Duration.ofSeconds(3), second,
                "a cached countdown is a number that sits still and then jumps");
        assertEquals(2, provider.calls().size());
    }

    @Test
    @DisplayName("a plugin that throws leaves PvP working")
    void failsOpen() {
        provider.broken = true;

        assertTrue(Combat.isPvpEnabled(attacker.player()),
                "their bug must not stop everyone on the server from fighting");
        assertTrue(Combat.canAttack(attacker.player(), defender.player()));
        assertFalse(Combat.isTagged(defender.player()),
                "and it must not tag somebody who is not fighting either");
    }

    @Test
    @DisplayName("a plugin that throws does not escape to the caller")
    void brokenPluginIsContained() {
        provider.broken = true;

        Combat.tag(defender.player(), attacker.player());
        Combat.untag(defender.player());
        Combat.statsOf(defender.player());
    }

    @Test
    @DisplayName("with nothing installed nobody is in combat and everyone can fight")
    void unsupportedIsHonest() {
        CombatRuntime.install(null);

        assertFalse(Combat.isSupported());
        assertFalse(Combat.isTagged(defender.player()));
        assertEquals(Duration.ZERO, Combat.remaining(defender.player()));
        assertTrue(Combat.isPvpEnabled(defender.player()));
        assertTrue(Combat.canAttack(attacker.player(), defender.player()));
        assertEquals(Optional.empty(), Combat.statsOf(defender.player()));
        assertEquals("", Combat.providerName());
    }

    @Test
    @DisplayName("a plugin that counts nothing says so instead of answering zero")
    void statsAreEmptyRatherThanZero() {
        assertEquals(Optional.empty(), Combat.statsOf(defender.player()),
                "'no kills' and 'nobody is counting' are different answers");
    }

    @Test
    @DisplayName("a registered bridge beats whatever was detected")
    void bridgeWins() {
        Combat.registerBridge(new CombatBridge() {
            @Override
            public @NotNull String name() {
                return "CelestCombat";
            }

            @Override
            public boolean isTagged(@NotNull Player player) {
                return true;
            }
        }, 10);

        assertEquals("CelestCombat", Combat.providerName());
        assertTrue(Combat.isTagged(defender.player()));
    }

    @Test
    @DisplayName("a bridge only writes what it can answer, and the rest is a quiet server")
    void bridgeDefaults() {
        Combat.registerBridge(new CombatBridge() {
            @Override
            public @NotNull String name() {
                return "Minimal";
            }

            @Override
            public boolean isTagged(@NotNull Player player) {
                return false;
            }
        }, 1);

        assertEquals(Duration.ZERO, Combat.remaining(defender.player()));
        assertTrue(Combat.isPvpEnabled(defender.player()));
        assertEquals(Optional.empty(), Combat.statsOf(defender.player()));
    }

    @Test
    @DisplayName("a player who never died is not infinitely good")
    void ratioHandlesZeroDeaths() {
        assertEquals(10.0, new CombatStats(10, 0, 0, 0, 0, 0).ratio());
        assertEquals(2.0, new CombatStats(10, 5, 0, 0, 0, 0).ratio());
        assertEquals(0.0, new CombatStats(0, 0, 0, 0, 0, 0).ratio());
    }

    @Test
    @DisplayName("a player who leaves is forgotten")
    void forgetsPlayerWhoLeft() {
        Combat.tag(defender.player(), attacker.player());
        Combat.isTagged(defender.player());
        provider.clear();

        CombatRuntime.forget(defender.player().getUniqueId());
        Combat.isTagged(defender.player());

        assertEquals(1, provider.tagQuestions(),
                "a cached answer for somebody who left would be inherited by nobody,"
                        + " but it is memory that never comes back");
    }
}
