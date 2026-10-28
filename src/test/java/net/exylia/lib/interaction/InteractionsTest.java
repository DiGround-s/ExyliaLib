package net.exylia.lib.interaction;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.command.Commands;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.teleport.Teleports;
import net.exylia.lib.util.teleport.internal.TeleportRuntime;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionsTest {

    private Plugin plugin;
    private World world;
    private FakePlayer steve;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Displays");
        world = FakeServer.newWorld("world");
        FakeServer.worlds(world);
        steve = new FakePlayer("Steve").at(new Location(world, 0, 64, 0));
        FakeServer.online(steve.player());
    }

    @AfterEach
    void tearDown() {
        Interactions.releaseAll();
        Commands.releaseAll();
        Teleports.releaseAll();
        TeleportRuntime.resetForTests();
        Placeholders.unregisterAll("Displays");
        Tasks.releaseAll();
        FakeServer.reset();
    }

    /** Ticks the fake server until the run is over; fails instead of hanging. */
    private static void finish(java.util.concurrent.CompletableFuture<Void> run) {
        for (int i = 0; i < 200 && !run.isDone(); i++) {
            FakeServer.tick(1);
        }
        assertTrue(run.isDone(), "the interaction never finished");
    }

    private Interaction compile(String... lines) {
        return Interactions.compile(plugin, List.of(lines));
    }

    @Test
    @DisplayName("click filters read like menu prefixes; anything else is not a filter")
    void filters() {
        assertEquals(Set.of(Click.LEFT), Interactions.filter("left"));
        assertEquals(Set.of(Click.SHIFT_LEFT, Click.SHIFT_RIGHT), Interactions.filter("shift"));
        assertEquals(Set.of(Click.LEFT, Click.RIGHT), Interactions.filter("left, right"));
        assertEquals(Set.of(Click.SHIFT_RIGHT), Interactions.filter("Shift-Right"));
        assertEquals(Set.of(Click.values()), Interactions.filter("any"));
        assertNull(Interactions.filter("console"));
        assertNull(Interactions.filter("[MESSAGE] a"));
    }

    @Test
    @DisplayName("the starters offer filters, tokens and command prefixes")
    void starters() {
        List<String> starters = Interactions.starters();
        assertEquals("left:", starters.get(0));
        assertTrue(starters.containsAll(List.of("[DELAY]", "[MESSAGE]", "[SOUND]", "console:")));
        assertFalse(starters.contains("[PARTICLE_SHAPE]"));
    }

    @Test
    @DisplayName("a bad line is skipped and the rest compile")
    void badLinesAreSkipped() {
        Interaction interaction = compile(
                "left: arena join",
                "[NOPE] x",
                "[DELAY] soon",
                "right:",
                "[TELEPORT] world;1;2",
                "[SERVER]",
                "console: say hi",
                "[MESSAGE] hi",
                "  ");
        assertEquals(List.of("left: arena join", "[NOPE] x", "console: say hi", "[MESSAGE] hi"),
                interaction.lines(), "an unknown token may be registered later, so it is kept");
        assertTrue(Interactions.compile(plugin, null).isEmpty());
    }

    @Test
    @DisplayName("handles answers whether any line takes that click")
    void handles() {
        Interaction left = compile("left: hit");
        assertTrue(left.handles(Click.LEFT));
        assertFalse(left.handles(Click.RIGHT));
        assertFalse(left.handles(Click.SHIFT_LEFT));
        Interaction shift = compile("shift: edit");
        assertTrue(shift.handles(Click.SHIFT_LEFT));
        assertTrue(shift.handles(Click.SHIFT_RIGHT));
        assertFalse(shift.handles(Click.RIGHT));
        assertTrue(compile("bare").handles(Click.SHIFT_RIGHT));
    }

    @Test
    @DisplayName("only the lines that answer the click run, in order, as their actor")
    void clickFiltering() {
        Interaction interaction = compile("left: l", "right: r %arena%", "shift_right: sr",
                "any: a", "console: say %arena%", "left,right: lr");
        finish(interaction.run(steve.player(), Click.RIGHT, Map.of("arena", "boxing")));
        assertEquals(List.of("r boxing", "a", "lr"), steve.commands());
        assertEquals(List.of("say boxing"), FakeServer.consoleCommands());
    }

    @Test
    @DisplayName("a refused command stops the lines after it, as a command list always has")
    void refusedCommandStops() {
        steve.rejectsCommands();
        finish(compile("first", "[ACTION] none", "second").run(steve.player(), Click.LEFT));
        assertEquals(List.of(), FakeServer.consoleCommands());
    }

    @Test
    @DisplayName("[DELAY] holds back every line after it")
    void delayOrders() {
        Interaction interaction = compile("first", "[DELAY] 1s", "second", "[DELAY] 0.5", "third");
        interaction.run(steve.player(), Click.LEFT);
        assertEquals(List.of("first"), steve.commands());
        FakeServer.tick(19);
        assertEquals(List.of("first"), steve.commands());
        FakeServer.tick(2);
        assertEquals(List.of("first", "second"), steve.commands());
        FakeServer.tick(11);
        assertEquals(List.of("first", "second", "third"), steve.commands());
    }

    @Test
    @DisplayName("a plugin's own token runs in order with its argument rendered")
    void customToken() {
        List<String> said = new ArrayList<>();
        Interactions.token(plugin, "say", (player, click, argument, data) ->
                said.add(click + " " + player.getName() + ": " + argument));
        finish(compile("before", "[SAY] hello %npc%", "after")
                .run(steve.player(), Click.SHIFT_LEFT, Map.of("npc", "Bob")));
        assertEquals(List.of("SHIFT_LEFT Steve: hello Bob"), said);
        assertEquals(List.of("before", "after"), steve.commands());

        assertThrows(IllegalArgumentException.class,
                () -> Interactions.token(plugin, "DELAY", (p, c, a, d) -> { }));
        Plugin other = FakeServer.newPlugin("Other");
        assertThrows(IllegalArgumentException.class,
                () -> Interactions.token(other, "SAY", (p, c, a, d) -> { }));

        Interaction compiled = compile("[SAY] gone");
        Interactions.release("Displays");
        finish(compiled.run(steve.player(), Click.LEFT));
        assertEquals(1, said.size(), "a released token does not run");
        finish(compile("[SAY] x", "after").run(steve.player(), Click.LEFT));
        assertEquals(1, said.size(), "nor in new lines, which carry on past it");
    }

    @Test
    @DisplayName("a token registered after the lines compiled still runs")
    void lateToken() {
        Interaction compiled = compile("[GREET] hi", "after");
        finish(compiled.run(steve.player(), Click.RIGHT));
        assertEquals(List.of("after"), steve.commands(), "unregistered: skipped, the rest runs");

        List<String> said = new ArrayList<>();
        Interactions.token(plugin, "GREET", (player, click, argument, data) -> said.add(argument));
        finish(compiled.run(steve.player(), Click.RIGHT));
        assertEquals(List.of("hi"), said);
    }

    @Test
    @DisplayName("sequence tokens play for the clicking player")
    void sequenceTokens() {
        finish(compile("[MESSAGE] hi %who%").run(steve.player(), Click.RIGHT, Map.of("who", "there")));
        FakeServer.tick(2);
        assertEquals(List.of("hi there"), steve.messages());
    }

    @Test
    @DisplayName("[SERVER] sends a Connect on the BungeeCord channel, no Redis needed")
    void serverConnects() {
        TeleportRuntime.resetForTests();
        TeleportRuntime.init(plugin);
        finish(compile("[SERVER] lobby", "after").run(steve.player(), Click.RIGHT));
        assertEquals(List.of("BungeeCord"), steve.pluginMessages());
        assertEquals(List.of("after"), steve.commands());
    }

    @Test
    @DisplayName("[TELEPORT] moves the player on this server")
    void teleports() {
        compile("[TELEPORT] world;10;70;-5;90;0").run(steve.player(), Click.RIGHT);
        FakeServer.tick(2);
        assertEquals(1, steve.teleports().size());
        Location to = steve.teleports().get(0);
        assertEquals(10, to.getX());
        assertEquals(70, to.getY());
        assertEquals(-5, to.getZ());
    }
}
