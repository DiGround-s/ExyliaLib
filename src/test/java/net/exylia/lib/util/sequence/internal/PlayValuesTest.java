package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.text.Values;
import net.exylia.lib.util.sequence.SequenceStep;
import net.exylia.lib.util.sequence.SequenceTarget;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a sequence learns only when it plays: the values a crate rolled, the
 * item it landed on, and who reads its text.
 */
class PlayValuesTest {

    private final List<String> problems = new ArrayList<>();
    private World world;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        world = FakeServer.newWorld("crates");
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    private SequenceStep compile(String line) {
        problems.clear();
        SequenceCompiler compiler = new SequenceCompiler(SequenceAccess.builtInShapes(),
                (where, problem) -> problems.add(problem));
        List<SequenceStep> steps = compiler.compile(List.of(line));
        return steps.isEmpty() ? null : steps.get(0);
    }

    @Test
    @DisplayName("values fill %name%, survive every copy of the target, and leave unknown names alone")
    void valuesFill() {
        SequenceTarget target = SequenceTarget.at(new Location(world, 0, 64, 0))
                .values(Values.of("reward", "{highlight}Diamond").putText("player", "<red>Bob"))
                .within(8)
                .movedTo(new Location(world, 1, 64, 0));

        assertEquals("You won {highlight}Diamond", target.fill("You won %reward%"));
        assertEquals("by \\<red>Bob %other%", target.fill("by %player% %other%"));
        assertEquals("plain", target.fill("plain"));
        assertEquals("", target.fill(null));
    }

    @Test
    @DisplayName("follow survives every copy of the target and is off by default")
    void followSurvivesCopies() {
        org.bukkit.entity.Player buffed = new FakePlayer("Buffed").at(new Location(world, 0, 64, 0)).player();
        SequenceTarget still = SequenceTarget.at(new Location(world, 0, 64, 0));
        SequenceTarget moving = still.follow(buffed).by(buffed).on(buffed).within(8)
                .movedTo(new Location(world, 5, 64, 0)).values(Values.of("a", "b"));

        assertNull(still.follows());
        assertSame(buffed, moving.follows());
        assertNull(moving.follow(null).follows());
    }

    @Test
    @DisplayName("text lines take a trailing who:, and still mean the source without one")
    void readers() {
        Steps.TitleStep nearby = (Steps.TitleStep) compile("[TITLE] Won;%reward%;0.2;2;0.5;who:nearby");
        Steps.Message plain = (Steps.Message) compile("[MESSAGE] a; b");
        Steps.ActionBarStep all = (Steps.ActionBarStep) compile("[ACTION_BAR] hi;who:all");

        assertEquals(Steps.Readers.NEARBY, nearby.who());
        assertEquals("%reward%", nearby.subtitle());
        assertEquals(500L, nearby.fadeOut());
        assertEquals(Steps.Readers.SOURCE, plain.who());
        assertEquals("a; b", plain.text(), "a semicolon inside the text is still text");
        assertEquals(Steps.Readers.ALL, all.who());
        assertTrue(problems.isEmpty(), problems.toString());

        compile("[MESSAGE] hi;who:everybody-ish");
        assertEquals(1, problems.size(), "a who: nobody understands is named");
    }

    @Test
    @DisplayName("a message reaches both people, with this play's values in it")
    void messageToBoth() {
        FakePlayer opener = new FakePlayer("Opener").at(new Location(world, 0, 64, 0));
        FakePlayer friend = new FakePlayer("Friend").at(new Location(world, 1, 64, 0));
        SequenceTarget target = SequenceTarget.at(new Location(world, 0, 64, 0))
                .by(opener.player()).on(friend.player())
                .values(Values.of("reward", "Diamond"));

        compile("[MESSAGE] got %reward%;who:both").play(target, null);

        assertEquals(1, opener.messages().size());
        assertEquals(1, friend.messages().size());
        assertTrue(opener.messages().get(0).contains("got Diamond"), opener.messages().toString());
    }

    @Test
    @DisplayName("a colour naming a value is read when it plays, and a bad one falls back quietly")
    void deferredColour() {
        SequenceTarget gold = SequenceTarget.at(new Location(world, 0, 64, 0))
                .values(Values.of("tier_color", "255,215,0"));
        SequenceTarget broken = SequenceTarget.at(new Location(world, 0, 64, 0))
                .values(Values.of("tier_color", "not a colour"));

        assertEquals(Color.fromRGB(255, 215, 0), Args.colourFor(gold, "%tier_color%", Color.RED));
        assertEquals(Color.RED, Args.colourFor(broken, "%tier_color%", Color.RED));
        assertEquals(Color.RED, Args.colourFor(gold, null, Color.RED));

        Steps.Fireworks firework = (Steps.Fireworks) compile("[FIREWORK] color:%tier_color%;fade:#ffffff");
        assertEquals("%tier_color%", firework.colourRaw());
        assertNull(firework.fadeRaw());
        assertEquals(Color.WHITE, firework.fade());
        assertTrue(problems.isEmpty(), "a deferred colour is not a bad colour: " + problems);
    }

    @Test
    @DisplayName("a particle line understands fade:, and keeps its paint when nothing is deferred")
    void particleFade() {
        Steps.Particles fixed = (Steps.Particles) compile(
                "[PARTICLE] DUST_COLOR_TRANSITION;color:#ff0000;fade:#0000ff");
        SequenceTarget target = SequenceTarget.at(new Location(world, 0, 64, 0));

        assertTrue(problems.isEmpty(), problems.toString());
        assertSame(fixed.paint(), fixed.paint().forPlay(target));
        Object data = ParticlePaint.dataFor(org.bukkit.Particle.DUST_COLOR_TRANSITION,
                Color.RED, Color.BLUE, 1f, null);
        assertEquals(Color.BLUE, ((org.bukkit.Particle.DustTransition) data).getToColor());
    }

    @Test
    @DisplayName("[LID] reads open and close, and refuses anything else")
    void lid() {
        assertEquals(new Steps.Lid(true, 0.0), compile("[LID] open"));
        assertEquals(new Steps.Lid(false, 1.0), compile("[LID] close;y:1"));
        assertNull(compile("[LID] ajar"));
        assertEquals(1, problems.size());
    }
}
