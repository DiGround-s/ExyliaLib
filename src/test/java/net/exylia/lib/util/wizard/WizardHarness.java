package net.exylia.lib.util.wizard;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.effect.internal.EffectRuntime;
import net.exylia.lib.effect.internal.Packets;
import net.exylia.lib.input.internal.InputSession;
import net.exylia.lib.input.internal.TestTransports;
import net.exylia.lib.input.internal.Transport;
import net.exylia.lib.input.internal.TransportKind;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import net.exylia.lib.util.wizard.internal.WizardRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every wizard test needs before it can drive a flow.
 *
 * <p>A wizard is a chain of inputs, so nothing about one can be observed
 * without something willing to ask its questions. Rather than each test class
 * standing up its own fake transport &mdash; which is how the same harness ends
 * up subtly different in five files, and how the one copy that forgot to clear
 * the registry leaks a pending question into the next test &mdash; the setup,
 * the teardown and the answering live here once.
 *
 * <p>The transport answers nothing by itself. A test says what the player
 * types, in order, which is the only way a redo can be driven: the same key is
 * asked twice and the two answers must differ.
 */
final class WizardHarness {

    private final Plugin plugin;
    private final FakePlayer player;
    private final Recorder recorder = new Recorder();
    private final PluginWizards wizards;

    private WizardHarness(String pluginName, String playerName) {
        this.plugin = FakeServer.newPlugin(pluginName, null);
        this.player = new FakePlayer(playerName);
        FakeServer.online(this.player.player());
        // The wizard draws a progress bar through the effect module, which
        // refuses to guess an owner. Registered here so a test never has to
        // remember that a boss bar needs one.
        Effects.owner(plugin);
        this.wizards = Wizards.of(plugin);
    }

    /**
     * Installs a fake server, an empty input registry and an empty wizard
     * runtime, and returns a harness holding one plugin and one player.
     *
     * @param pluginName what the owning plugin is called
     * @param playerName who is being walked through the flow
     * @return the harness
     */
    static WizardHarness start(String pluginName, String playerName) {
        FakeServer.install();
        FakeServer.reset();
        // Without this the effect module tries to send real packets, and
        // without a palette every prompt parsed through Text throws.
        Packets.override(false);
        Colors.apply(new Palette());
        EffectRuntime.stopEverything();
        EffectRuntime.releaseAll();
        TestTransports.clear();
        WizardRuntime.resetForTests();
        WizardHarness harness = new WizardHarness(pluginName, playerName);
        TestTransports.install(List.of(harness.recorder));
        // After the install, because init only discovers the built-in transports
        // when none are registered — and a real dialog transport here would try
        // to send packets nothing is listening for.
        TestTransports.init(harness.plugin);
        return harness;
    }

    /**
     * Lets go of everything, in the order the library does.
     *
     * <p>The wizard runtime first: a run that is still alive holds a question,
     * and clearing the input registry underneath it would leave the run waiting
     * on a future nothing will complete.
     */
    void stop() {
        WizardRuntime.resetForTests();
        Wizards.releaseAll();
        TestTransports.clear();
        EffectRuntime.stopEverything();
        EffectRuntime.releaseAll();
        FakeServer.reset();
    }

    Plugin plugin() {
        return plugin;
    }

    PluginWizards wizards() {
        return wizards;
    }

    FakePlayer fake() {
        return player;
    }

    Player player() {
        return player.player();
    }

    /** Every prompt a transport was asked to show, in order. */
    List<String> prompts() {
        return List.copyOf(recorder.prompts);
    }

    /** How many questions are still open, which should be zero once a run ends. */
    int openQuestions() {
        return recorder.open.size();
    }

    /**
     * Lets the scheduled work run.
     *
     * <p>A wizard hops to the player's thread for everything, so nothing that
     * looks synchronous in a test actually is. One tick is a step; several
     * settle a chain of them.
     *
     * @param ticks how many server ticks to simulate
     */
    void settle(int ticks) {
        FakeServer.tick(ticks);
    }

    /**
     * Lets a chain of hops happen.
     *
     * <p>Four ticks rather than one, because almost nothing a wizard does is a
     * single hop: answering a question hops to deliver the result, the session
     * hops again to run the next step, and opening that step hops once more to
     * show it. The fake scheduler runs each tick against a snapshot, so work
     * scheduled during a tick waits for the next one. Four is comfortably more
     * than the longest of those chains and far below any timeout, so it settles
     * the flow without moving a clock a test is asserting on.
     */
    void settle() {
        settle(4);
    }

    /**
     * Answers whatever question is open right now, as the player typing.
     *
     * <p>Raw text on purpose, rather than a value: it goes through the same
     * parse and validation pipeline every real transport uses, so a test that
     * answers {@code "12"} to an integer step proves the wizard receives a
     * {@code Long} rather than proving the harness can put one in a map.
     *
     * @param raw what the player typed
     */
    void answer(String raw) {
        InputSession session = recorder.current();
        assertTrue(session != null, "nothing was asking the player anything");
        Object request = session.request();
        assertTrue(request instanceof net.exylia.lib.input.InputRequest<?, ?>,
                "the wizard asked with something that is not an input request");
        net.exylia.lib.input.InputRequest<?, ?> input =
                (net.exylia.lib.input.InputRequest<?, ?>) request;
        net.exylia.lib.input.InputParser.Parsed<?> parsed = input.parseRaw(raw);
        assertTrue(parsed.ok(), "'" + raw + "' was rejected: " + parsed.error()
                + " (asked: " + input.prompt() + ')');
        session.complete(parsed.value());
        settle();
    }

    /** Answers several questions in a row. */
    void answerAll(String... raws) {
        for (String raw : raws) {
            answer(raw);
        }
    }

    /** Confirms the review screen. */
    void confirm() {
        answer("yes");
    }

    /** Denies the review screen, which offers the list of answers to change. */
    void deny() {
        answer("no");
    }

    /** Denies the review and asks to change one named answer. */
    void redo(String key) {
        deny();
        answer(key);
    }

    /** Ends whatever question is open the way a player closing a window does. */
    void cancelQuestion() {
        InputSession session = recorder.current();
        assertTrue(session != null, "nothing was asking the player anything");
        session.end(net.exylia.lib.input.InputOutcome.CANCELLED);
        settle();
    }

    /** Simulates the player leaving, both to the server and to the module. */
    void disconnect() {
        player.disconnect();
        WizardRuntime.forget(player.player().getUniqueId());
        settle();
    }

    /**
     * Asserts a run left nothing of itself behind.
     *
     * <p>All four things a run holds that a player can feel. The boss bar is
     * counted through the effect module rather than through the scheduler
     * because a bar with static text schedules no task at all &mdash; there is
     * nothing about it that changes &mdash; so a leaked one is invisible to a
     * task count and perfectly visible to the player.
     */
    void assertNothingLeaked() {
        assertEquals(0, Wizards.active(), "a finished run still holds its player's wizard slot");
        assertEquals(0, openQuestions(), "a finished run left a question the player cannot answer");
        assertEquals(0, FakeServer.liveRepeatingTasks(),
                "a finished run left a repeating task running");
        assertEquals(0, EffectRuntime.active(),
                "a finished run left its progress bar on the player's screen");
    }

    /**
     * A transport that shows nothing and remembers what it was asked.
     *
     * <p>Deliberately the last-resort kind: a wizard never names a preferred
     * transport, so the registry order is what it gets, and a test that had to
     * match kinds would be testing the input module instead.
     */
    private static final class Recorder implements Transport {

        final List<String> prompts = new CopyOnWriteArrayList<>();
        final List<InputSession> open = new CopyOnWriteArrayList<>();

        @Override
        public boolean show(@NotNull InputSession session) {
            open.add(session);
            if (session.request() instanceof net.exylia.lib.input.InputRequest<?, ?> request) {
                prompts.add(request.prompt());
            }
            return true;
        }

        @Override
        public void close(@NotNull InputSession session) {
            open.remove(session);
        }

        @Override
        public @NotNull TransportKind kind() {
            return TransportKind.CHAT;
        }

        /** The question waiting for an answer, or {@code null}. */
        InputSession current() {
            return open.isEmpty() ? null : open.get(open.size() - 1);
        }
    }
}
