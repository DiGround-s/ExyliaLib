package net.exylia.lib.chat;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How rules combine, which is the whole contract of the module. */
class ChatsTest {

    private Plugin events;
    private Plugin ffa;
    private Player alice;
    private Player bob;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Chats.releaseAll();
        events = FakeServer.newPlugin("ExyliaEvents");
        ffa = FakeServer.newPlugin("ExyliaFFA");
        alice = new FakePlayer("Alice").player();
        bob = new FakePlayer("Bob").player();
        FakeServer.online(alice, bob);
    }

    @AfterEach
    void tearDown() {
        Chats.releaseAll();
    }

    @Test
    @DisplayName("with no rules everybody reads everybody")
    void openByDefault() {
        assertTrue(Chats.canHear(alice, bob));
    }

    @Test
    @DisplayName("one refusal is enough, and a rule only speaks for its plugin")
    void everyRuleHasToAgree() {
        Chats.rule(events, (listener, speaker) -> true);
        Chats.rule(ffa, (listener, speaker) -> false);
        assertFalse(Chats.canHear(alice, bob));

        Chats.clear(ffa);
        assertTrue(Chats.canHear(alice, bob));
    }

    @Test
    @DisplayName("the question is asymmetric: a spectator reads what does not read back")
    void asymmetry() {
        Chats.rule(events, (listener, speaker) -> listener == alice);
        assertTrue(Chats.canHear(alice, bob));
        assertFalse(Chats.canHear(bob, alice));
    }

    @Test
    @DisplayName("a speaker always reads themselves")
    void speakerReadsThemselves() {
        Chats.rule(events, (listener, speaker) -> false);
        assertTrue(Chats.canHear(alice, alice));
    }

    @Test
    @DisplayName("a rule that throws loses its say, and the rest still answer")
    void brokenRuleIsIgnored() {
        Chats.rule(events, (listener, speaker) -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(Chats.canHear(alice, bob));

        Chats.rule(ffa, (listener, speaker) -> false);
        assertFalse(Chats.canHear(alice, bob));
    }

    @Test
    @DisplayName("a disabled plugin stops having a say")
    void releaseDropsTheRule() {
        Chats.rule(events, (listener, speaker) -> false);
        assertFalse(Chats.canHear(alice, bob));

        Chats.release("ExyliaEvents");
        assertTrue(Chats.canHear(alice, bob));
    }
}
