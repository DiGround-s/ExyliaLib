package net.exylia.lib.scoreboard.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.internal.PapiBridge;
import net.exylia.lib.scoreboard.Board;
import net.exylia.lib.scoreboard.Scoreboards;
import net.exylia.lib.scoreboard.SidebarConfig;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a board actually sends to a player.
 *
 * <p>A scoreboard is the most repeated work on a server: every player, every
 * second, forever. So these tests are mostly about what is <em>not</em> sent —
 * a line whose value did not change must cost nothing, or the module is worse
 * than writing the packets by hand.
 */
class BoardTest {

    private static final String PLUGIN = "BoardTest";

    private final AtomicLong clock = new AtomicLong(10_000L);
    private final AtomicReference<String> kills = new AtomicReference<>("0");
    private final AtomicReference<String> deaths = new AtomicReference<>("0");
    private final AtomicReference<String> top = new AtomicReference<>("first");
    private final List<FakeSidebar> created = new ArrayList<>();

    private Plugin plugin;
    private FakePlayer viewer;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Colors.apply(new Palette());

        plugin = FakeServer.newPlugin(PLUGIN, null);
        created.clear();
        BoardManager.clock(clock::get);
        BoardManager.init(plugin, (player, maxLines) -> {
            FakeSidebar sidebar = new FakeSidebar();
            created.add(sidebar);
            return sidebar;
        });

        Placeholders.group(plugin, "test")
                .add("kills", request -> kills.get())
                .add("deaths", request -> deaths.get())
                .add("top", request -> top.get())
                .add("arena", request -> request.get("arena", String.class))
                .register();

        viewer = new FakePlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        BoardManager.stopEverything();
        BoardManager.clock(null);
        Placeholders.unregisterAll(PLUGIN);
        PapiBridge.resetForTests();
        FakeServer.reset();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static SidebarConfig config(List<String> title, List<String> lines) {
        return new SidebarConfig(true, title, lines, new SidebarConfig.Update());
    }

    /** Runs the refresh driver, honouring its one tick delay. */
    private static void drive() {
        FakeServer.tick(2);
    }

    /** Moves the refresh clock forward by whole seconds. */
    private void advanceSeconds(double seconds) {
        clock.addAndGet((long) (seconds * 1000));
    }

    private FakeSidebar sidebar() {
        return created.get(created.size() - 1);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the first render sends the title and every line")
    void firstRenderSendsEverything() {
        Scoreboards.show(plugin, viewer.player(),
                config(List.of("{primary}FFA"), List.of("Kills: %test_kills%", "", "exylia.net")));
        drive();

        assertEquals(List.of("FFA"), sidebar().titles());
        assertEquals(List.of("0=Kills: 0", "1=", "2=exylia.net"), sidebar().lines());
    }

    @Test
    @DisplayName("a refresh with nothing changed sends nothing at all")
    void unchangedRefreshSendsNothing() {
        Scoreboards.show(plugin, viewer.player(),
                config(List.of("FFA"), List.of("Kills: %test_kills%", "Deaths: %test_deaths%")));
        drive();
        sidebar().clear();

        advanceSeconds(5);
        drive();

        assertEquals(List.of(), sidebar().calls());
    }

    @Test
    @DisplayName("only the line whose value changed is re-sent")
    void onlyChangedLinesAreSent() {
        Scoreboards.show(plugin, viewer.player(),
                config(List.of("FFA"), List.of("Kills: %test_kills%", "Deaths: %test_deaths%")));
        drive();
        sidebar().clear();

        kills.set("7");
        advanceSeconds(5);
        drive();

        assertEquals(List.of("line:0:Kills: 7"), sidebar().calls());
    }

    @Test
    @DisplayName("a title with several frames animates, one frame per refresh")
    void titleFramesAdvance() {
        Scoreboards.show(plugin, viewer.player(),
                config(List.of("one", "two", "three"), List.of("static")));
        drive();
        advanceSeconds(5);
        drive();
        advanceSeconds(5);
        drive();
        advanceSeconds(5);
        drive();

        assertEquals(List.of("one", "two", "three", "one"), sidebar().titles());
    }

    @Test
    @DisplayName("a placeholder with line breaks expands into several lines")
    void placeholderExpandsIntoLines() {
        top.set("first\nsecond\nthird");
        Scoreboards.show(plugin, viewer.player(),
                config(List.of("Top"), List.of("%test_top%", "end")));
        drive();

        assertEquals(List.of("0=first", "1=second", "2=third", "3=end"), sidebar().lines());
    }

    @Test
    @DisplayName("a board that shrinks clears the lines the player still has")
    void shrinkingClearsLeftoverLines() {
        top.set("a\nb\nc");
        Scoreboards.show(plugin, viewer.player(), config(List.of("Top"), List.of("%test_top%")));
        drive();
        sidebar().clear();

        top.set("a");
        advanceSeconds(5);
        drive();

        assertEquals(List.of("clear:1", "clear:2"),
                sidebar().calls().stream().filter(call -> call.startsWith("clear")).toList());
    }

    @Test
    @DisplayName("a board never shows more than fifteen lines")
    void linesAreCappedAtFifteen() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add("line " + i);
        }
        Scoreboards.show(plugin, viewer.player(), config(List.of("Long"), lines));
        drive();

        assertEquals(15, sidebar().lines().size());
        assertEquals(0, sidebar().countStartingWith("line:15"));
    }

    @Test
    @DisplayName("an expanding placeholder cannot push the board past fifteen lines")
    void expansionIsCappedAtFifteen() {
        top.set(String.join("\n", java.util.Collections.nCopies(30, "x")));
        Scoreboards.show(plugin, viewer.player(), config(List.of("Top"), List.of("%test_top%")));
        drive();

        assertEquals(15, sidebar().lines().size());
    }

    // ------------------------------------------------------------------
    // Refresh rate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a board does not render again before its interval is up")
    void intervalIsHonoured() {
        Scoreboards.show(plugin, viewer.player(),
                config(List.of("FFA"), List.of("Kills: %test_kills%")));
        drive();
        sidebar().clear();

        // Ticking without time passing must not render: the driver ticks every
        // tick, the board decides whether its own interval elapsed.
        kills.set("1");
        drive();
        assertEquals(List.of(), sidebar().calls());

        // One full interval later it renders again. The default is 20 ticks.
        advanceSeconds(1.1);
        drive();
        assertEquals(List.of("line:0:Kills: 1"), sidebar().calls());
    }

    @Test
    @DisplayName("refresh asks for a render without dropping the diff")
    void refreshRendersOnlyWhatChanged() {
        Board board = Scoreboards.show(plugin, viewer.player(),
                config(List.of("FFA"), List.of("Kills: %test_kills%", "Deaths: %test_deaths%")));
        drive();
        sidebar().clear();

        deaths.set("3");
        board.refresh();
        drive();

        assertEquals(List.of("line:1:Deaths: 3"), sidebar().calls());
    }

    @Test
    @DisplayName("new data reaches the placeholders immediately")
    void updateDataIsPickedUp() {
        Board board = Scoreboards.show(plugin, viewer.player(),
                config(List.of("FFA"), List.of("Arena: %test_arena%")),
                Map.of("arena", "nether"));
        drive();
        assertEquals(List.of("0=Arena: nether"), sidebar().lines());
        sidebar().clear();

        board.updateData(Map.of("arena", "end"));
        drive();

        assertEquals(List.of("line:0:Arena: end"), sidebar().calls());
    }

    @Test
    @DisplayName("invalidating re-sends everything, palette changes included")
    void invalidateResendsEverything() {
        Scoreboards.show(plugin, viewer.player(),
                config(List.of("FFA"), List.of("Kills: %test_kills%", "exylia.net")));
        drive();
        sidebar().clear();

        BoardManager.invalidateAll();
        drive();

        assertEquals(List.of("title:FFA", "line:0:Kills: 0", "line:1:exylia.net"),
                sidebar().calls());
    }

    @Test
    @DisplayName("smart off re-sends the whole board every refresh")
    void smartOffResendsEverything() {
        SidebarConfig config = new SidebarConfig(true, List.of("FFA"),
                List.of("Kills: %test_kills%"), new SidebarConfig.Update(20, false, true));
        Scoreboards.show(plugin, viewer.player(), config);
        drive();
        sidebar().clear();

        advanceSeconds(1.1);
        drive();

        assertEquals(List.of("title:FFA", "line:0:Kills: 0"), sidebar().calls());
    }

    // ------------------------------------------------------------------
    // Degrading instead of failing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a disabled config shows nothing and creates no sidebar")
    void disabledConfigShowsNothing() {
        Board board = Scoreboards.show(plugin, viewer.player(),
                new SidebarConfig(false, List.of("FFA"), List.of("a"), new SidebarConfig.Update()));
        drive();

        assertTrue(board.stopped());
        assertTrue(created.isEmpty());
        assertFalse(Scoreboards.has(viewer.player()));
    }

    @Test
    @DisplayName("a resolver that throws does not stop the rest of the board")
    void failingResolverDoesNotBreakTheBoard() {
        Placeholders.register(plugin, "test_broken", request -> {
            throw new IllegalStateException("boom");
        });

        Scoreboards.show(plugin, viewer.player(),
                config(List.of("FFA"), List.of("%test_broken%", "Kills: %test_kills%")));
        drive();

        assertTrue(sidebar().lines().contains("1=Kills: 0"));
    }

    @Test
    @DisplayName("a PlaceholderAPI expansion that refuses async work does not blank the sidebar")
    void asyncPapiDoesNotAbortTheRender() {
        // Boards render on an async timer, and PlaceholderAPI runs third-party
        // expansions that read the world. Reached from there, Paper answers
        // with "Asynchronous ... access" — thrown through the render, so not a
        // single line reached the player and the sidebar went blank.
        PapiBridge.setApplierForTests((viewer, text) -> {
            throw new IllegalStateException("Asynchronous entity world add!");
        });
        FakeServer.setPrimaryThread(false);

        Scoreboards.show(plugin, viewer.player(),
                config(List.of("FFA"), List.of("Kills: %test_kills%", "%external_value%")));
        drive();

        assertEquals(List.of("FFA"), sidebar().titles());
        assertEquals(List.of("0=Kills: 0", "1=%external_value%"), sidebar().lines());
    }

    @Test
    @DisplayName("a render that fails is reported once per board, not once per tick")
    void renderFailureIsReportedOncePerBoard() {
        List<LogRecord> reported = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                reported.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        BoardManager.logger().addHandler(handler);
        try {
            Scoreboards.show(plugin, viewer.player(),
                    config(List.of("FFA"), List.of("Kills: %test_kills%")));
            // Not the resolver: that failure is swallowed one level down. This
            // is the sidebar itself refusing, which is what an async
            // PlaceholderAPI used to look like from here.
            created.get(0).failOnEveryCall();

            drive();
            advanceSeconds(5);
            drive();
            advanceSeconds(5);
            drive();

            assertEquals(1, reported.size());
            // getMessage() alone never said where it came from.
            assertNotNull(reported.get(0).getThrown());
        } finally {
            BoardManager.logger().removeHandler(handler);
        }
    }
}
