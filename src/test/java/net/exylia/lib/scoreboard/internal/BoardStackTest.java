package net.exylia.lib.scoreboard.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who owns a board, and what happens when its owner goes away.
 *
 * <p>A board that outlives its plugin is a scoreboard on a player's screen
 * that no command can remove, and a board that keeps rendering while hidden is
 * work paid for nothing. Both are what these tests are about.
 */
class BoardStackTest {

    private final AtomicLong clock = new AtomicLong(10_000L);
    private final List<FakeSidebar> created = new ArrayList<>();

    private Plugin lobby;
    private Plugin event;
    private FakePlayer viewer;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Colors.apply(new Palette());

        created.clear();
        lobby = FakeServer.newPlugin("LobbyPlugin", null);
        event = FakeServer.newPlugin("EventPlugin", null);

        BoardManager.clock(clock::get);
        BoardManager.init(lobby, (player, maxLines) -> {
            FakeSidebar sidebar = new FakeSidebar();
            created.add(sidebar);
            return sidebar;
        });

        viewer = new FakePlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        BoardManager.stopEverything();
        BoardManager.clock(null);
        FakeServer.reset();
    }

    private static SidebarConfig config(String title) {
        return new SidebarConfig(true, List.of(title), List.of("a line"),
                new SidebarConfig.Update());
    }

    private static void drive() {
        FakeServer.tick(2);
    }

    // ------------------------------------------------------------------
    // Stacking
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a second board hides the first and gives it back afterwards")
    void boardsStack() {
        Board lobbyBoard = Scoreboards.show(lobby, viewer.player(), config("LOBBY"));
        drive();
        FakeSidebar lobbySidebar = created.get(0);

        Board eventBoard = Scoreboards.show(event, viewer.player(), config("EVENT"));
        drive();
        FakeSidebar eventSidebar = created.get(1);

        assertFalse(lobbySidebar.visible(), "the lobby board should be hidden underneath");
        assertTrue(eventSidebar.visible());
        assertSame(eventBoard, Scoreboards.get(viewer.player()).orElseThrow());

        eventBoard.stop();
        assertTrue(lobbySidebar.visible(), "the lobby board should come back");
        assertSame(lobbyBoard, Scoreboards.get(viewer.player()).orElseThrow());
    }

    @Test
    @DisplayName("a hidden board renders nothing while it waits underneath")
    void pausedBoardsDoNotRender() {
        Scoreboards.show(lobby, viewer.player(), config("LOBBY"));
        drive();
        FakeSidebar lobbySidebar = created.get(0);

        Scoreboards.show(event, viewer.player(), config("EVENT"));
        drive();
        lobbySidebar.clear();

        clock.addAndGet(10_000);
        drive();

        assertEquals(List.of(), lobbySidebar.calls());
    }

    @Test
    @DisplayName("a board that comes back is re-sent in full")
    void resumedBoardIsResent() {
        Scoreboards.show(lobby, viewer.player(), config("LOBBY"));
        drive();
        FakeSidebar lobbySidebar = created.get(0);

        Board eventBoard = Scoreboards.show(event, viewer.player(), config("EVENT"));
        drive();
        lobbySidebar.clear();

        eventBoard.stop();
        drive();

        assertEquals(List.of("show", "title:LOBBY", "line:0:a line"), lobbySidebar.calls());
    }

    @Test
    @DisplayName("hide takes down the visible board, not the whole stack")
    void hideTakesDownTheVisibleBoard() {
        Scoreboards.show(lobby, viewer.player(), config("LOBBY"));
        Scoreboards.show(event, viewer.player(), config("EVENT"));
        drive();

        assertTrue(Scoreboards.hide(viewer.player()));
        assertTrue(Scoreboards.has(viewer.player()));
        assertEquals("LOBBY", Scoreboards.get(viewer.player()).orElseThrow().config().title().get(0));

        assertTrue(Scoreboards.hide(viewer.player()));
        assertFalse(Scoreboards.has(viewer.player()));
        assertFalse(Scoreboards.hide(viewer.player()));
    }

    // ------------------------------------------------------------------
    // Nothing outlives its owner
    // ------------------------------------------------------------------

    @Test
    @DisplayName("disabling a plugin takes down its boards and only its boards")
    void disablingAPluginStopsOnlyItsBoards() {
        Board lobbyBoard = Scoreboards.show(lobby, viewer.player(), config("LOBBY"));
        Board eventBoard = Scoreboards.show(event, viewer.player(), config("EVENT"));
        drive();

        assertEquals(1, BoardManager.stopAll("EventPlugin"));

        assertTrue(eventBoard.stopped());
        assertFalse(lobbyBoard.stopped());
        assertTrue(created.get(0).visible(), "the surviving board should be visible again");
        assertSame(lobbyBoard, Scoreboards.get(viewer.player()).orElseThrow());
    }

    @Test
    @DisplayName("a plugin disabled while its board is buried loses it too")
    void disablingAPluginStopsBuriedBoards() {
        Board lobbyBoard = Scoreboards.show(lobby, viewer.player(), config("LOBBY"));
        Board eventBoard = Scoreboards.show(event, viewer.player(), config("EVENT"));
        drive();

        assertEquals(1, BoardManager.stopAll("LobbyPlugin"));

        assertTrue(lobbyBoard.stopped());
        assertFalse(eventBoard.stopped());
        assertSame(eventBoard, Scoreboards.get(viewer.player()).orElseThrow());
    }

    @Test
    @DisplayName("a stopped board lets go of its viewer before it closes")
    void stoppingABoardReleasesTheViewerFirst() {
        Scoreboards.show(lobby, viewer.player(), config("LOBBY"));
        Board eventBoard = Scoreboards.show(event, viewer.player(), config("EVENT"));
        drive();
        FakeSidebar eventSidebar = created.get(1);

        eventBoard.stop();

        // The order is the point: a sidebar that closes while it still holds
        // the viewer is never taken out of the player's sidebar queue, and
        // everything shown afterwards queues behind it.
        List<String> calls = eventSidebar.calls();
        assertEquals(List.of("hide", "close"), calls.subList(calls.size() - 2, calls.size()));
    }

    @Test
    @DisplayName("a board buried under another lets go of its viewer too")
    void stoppingABuriedBoardReleasesTheViewer() {
        Board lobbyBoard = Scoreboards.show(lobby, viewer.player(), config("LOBBY"));
        Scoreboards.show(event, viewer.player(), config("EVENT"));
        drive();
        FakeSidebar lobbySidebar = created.get(0);

        // The buried board is the one that used to rot in the queue: it was
        // closed without ever being on screen, so nothing took it out.
        lobbyBoard.stop();

        List<String> calls = lobbySidebar.calls();
        assertEquals(List.of("hide", "close"), calls.subList(calls.size() - 2, calls.size()));
    }

    @Test
    @DisplayName("a player leaving takes down every board they had")
    void leavingStopsEverything() {
        Board lobbyBoard = Scoreboards.show(lobby, viewer.player(), config("LOBBY"));
        Board eventBoard = Scoreboards.show(event, viewer.player(), config("EVENT"));
        drive();

        assertEquals(2, BoardManager.stopFor(viewer.player()));

        assertTrue(lobbyBoard.stopped());
        assertTrue(eventBoard.stopped());
        assertFalse(Scoreboards.has(viewer.player()));
    }

    @Test
    @DisplayName("a board whose player vanished without a quit event is dropped")
    void offlinePlayersAreCleanedUp() {
        Scoreboards.show(lobby, viewer.player(), config("LOBBY"));
        drive();

        viewer.disconnect();
        drive();

        assertFalse(Scoreboards.has(viewer.player()));
        assertEquals(0, BoardManager.activeCount());
    }

    // ------------------------------------------------------------------
    // The refresh driver
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the refresh driver only runs while there are boards")
    void driverStopsWhenIdle() {
        assertEquals(0, FakeServer.liveRepeatingTasks());

        Board board = Scoreboards.show(lobby, viewer.player(), config("LOBBY"));
        drive();
        assertEquals(1, FakeServer.liveRepeatingTasks());

        board.stop();
        assertEquals(0, FakeServer.liveRepeatingTasks(),
                "the driver should stop once the last board is gone");
    }

    @Test
    @DisplayName("a board is reclaimed after a world change")
    void reinitReclaimsTheBoard() {
        Scoreboards.show(lobby, viewer.player(), config("LOBBY"));
        drive();
        FakeSidebar sidebar = created.get(0);
        sidebar.clear();

        BoardManager.reinit(viewer.player());
        FakeServer.tick(21);
        // The delayed task runs after the driver within the same tick, so the
        // re-render it asks for lands on the next one.
        drive();

        assertEquals(List.of("hide", "show"),
                sidebar.calls().stream()
                        .filter(call -> call.equals("hide") || call.equals("show"))
                        .toList());
        assertTrue(sidebar.calls().contains("title:LOBBY"),
                "the reclaimed board should be re-sent in full");
    }

    @Test
    @DisplayName("stopping a board twice is harmless")
    void stoppingTwiceIsHarmless() {
        Board board = Scoreboards.show(lobby, viewer.player(), config("LOBBY"));
        drive();

        board.stop();
        board.stop();

        assertTrue(board.stopped());
        assertEquals(1, created.get(0).countStartingWith("close"));
    }
}
