package net.exylia.lib.input;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An answer reaches the player's thread, and only a player who backed out gets
 * the screen they came from.
 */
class AnsweredTest {

    private Plugin plugin;
    private FakePlayer steve;
    private final List<String> accepted = new ArrayList<>();
    private final AtomicInteger abandoned = new AtomicInteger();

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Homes");
        steve = new FakePlayer("Steve");
        FakeServer.online(steve.player());
    }

    @AfterEach
    void tearDown() {
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private void ask(InputResult<String> result) {
        CompletableFuture<InputResult<String>> asked = new CompletableFuture<>();
        Inputs.of(plugin).answered(steve.player(), asked, accepted::add, abandoned::incrementAndGet);
        asked.complete(result);
        FakeServer.tick(1);
    }

    @Test
    @DisplayName("an answer is handed over on the player's thread")
    void answerIsDelivered() {
        ask(InputResult.completed("base"));

        assertEquals(List.of("base"), accepted);
        assertEquals(0, abandoned.get());
    }

    @Test
    @DisplayName("a cancel runs the abandoned callback")
    void cancelReopens() {
        ask(InputResult.ended(InputOutcome.CANCELLED));

        assertTrue(accepted.isEmpty());
        assertEquals(1, abandoned.get());
    }

    @Test
    @DisplayName("a timeout, a disconnect, a newer request or a shutdown reopens nothing")
    void otherEndingsReopenNothing() {
        ask(InputResult.ended(InputOutcome.TIMED_OUT));
        ask(InputResult.ended(InputOutcome.DISCONNECTED));
        ask(InputResult.ended(InputOutcome.REPLACED));
        ask(InputResult.ended(InputOutcome.SHUT_DOWN));

        assertTrue(accepted.isEmpty());
        assertEquals(0, abandoned.get());
    }
}
