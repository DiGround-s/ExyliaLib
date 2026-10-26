package net.exylia.lib.lobby;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.session.Sessions;
import net.exylia.lib.task.Tasks;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyTest {

    private Plugin plugin;
    private FakePlayer host;
    private FakePlayer ann;
    private FakePlayer bob;
    private final List<String> calls = new ArrayList<>();
    /** What the next admissions answer, in order; empty admits. */
    private final List<CompletableFuture<String>> answers = new ArrayList<>();
    private Lobby<String> lobby;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Sessions.releaseAll();
        plugin = FakeServer.newPlugin("Tabletop");
        World world = FakeServer.newWorld("world");
        FakeServer.worlds(world);
        host = new FakePlayer("Host").at(new Location(world, 0, 64, 0));
        ann = new FakePlayer("Ann").at(new Location(world, 5, 64, 0));
        bob = new FakePlayer("Bob").at(new Location(world, 9, 64, 0));
        FakeServer.online(host.player(), ann.player(), bob.player());
        lobby = Lobby.of(plugin, new LobbyHooks<>() {
            @Override
            public CompletableFuture<String> admit(Table<String> table, Player player) {
                calls.add("admit " + player.getName());
                return answers.isEmpty() ? CompletableFuture.completedFuture(null) : answers.removeFirst();
            }

            @Override
            public void released(Table<String> table, Seat seat) {
                calls.add("released " + seat.name());
            }

            @Override
            public void joined(Table<String> table, Seat seat) {
                calls.add("joined " + seat.name());
            }

            @Override
            public void started(Table<String> table) {
                calls.add("started " + table.seats().size());
            }

            @Override
            public void dropped(Table<String> table, Seat seat) {
                calls.add("dropped " + seat.name());
            }

            @Override
            public void ended(Table<String> table, TableEnd end) {
                calls.add("ended " + end);
            }
        }, LobbyMessages::new);
    }

    @AfterEach
    void tearDown() {
        Sessions.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static TableRules rules(int min, int max) {
        return new TableRules("tabletop:test", "/test", min, max, Duration.ofSeconds(10), Duration.ofSeconds(2));
    }

    private static boolean told(FakePlayer player, String part) {
        FakeServer.tick(1);
        return player.messages().stream().anyMatch(line -> line.contains(part));
    }

    @Test
    @DisplayName("an open table fills, starts once, is played and finished; every claim goes with it")
    void openTable() {
        Table<String> table = lobby.create(host.player(), "chess", rules(2, 2), null).orElseThrow();
        assertEquals(TableState.OPEN, table.state());
        assertEquals("Tabletop", Sessions.holder(host.player()).orElseThrow().plugin());
        assertEquals(List.of(table), lobby.open(subject -> subject.equals("chess")));
        assertTrue(told(host, "Your table is open"));

        lobby.join(ann.player(), table);
        assertEquals(List.of("admit Host", "joined Host", "admit Ann", "joined Ann", "started 2"), calls);
        assertEquals(TableState.STARTING, table.state());
        assertTrue(lobby.isPlaying(ann.player().getUniqueId()));
        assertTrue(lobby.open(subject -> true).isEmpty());

        assertTrue(lobby.play(table));
        assertTrue(lobby.finish(table));
        assertFalse(lobby.finish(table), "once");
        assertEquals("ended FINISHED", calls.getLast());
        assertTrue(Sessions.isFree(host.player()));
        assertTrue(Sessions.isFree(ann.player()));
        assertTrue(lobby.of(ann.player().getUniqueId()).isEmpty());
        assertTrue(lobby.all().isEmpty());
    }

    @Test
    @DisplayName("a player at a table, or held by another plugin, cannot take a second seat")
    void oneTableAtATime() {
        Table<String> first = lobby.create(host.player(), "chess", rules(2, 3), null).orElseThrow();
        assertTrue(lobby.create(host.player(), "chess", rules(2, 2), null).isEmpty());
        assertTrue(told(host, "already at a table"));

        Sessions.of(FakeServer.newPlugin("Arena")).claim(ann.player(), "arena");
        lobby.join(ann.player(), first);
        assertTrue(told(ann, "busy in Arena"));
        assertEquals(1, first.seats().size());
        assertTrue(lobby.create(host.player(), "chess", rules(2, 2), ann.player()).isEmpty());
    }

    @Test
    @DisplayName("an invitation nobody answers expires; both are told and the claim goes")
    void invitationExpires() {
        Table<String> table = lobby.create(host.player(), "chess", rules(2, 2), ann.player()).orElseThrow();
        assertTrue(told(ann, "Host invites you to their table."));
        assertEquals(List.of("Host"), lobby.invitesTo(ann.player().getUniqueId()));
        assertTrue(lobby.open(subject -> true).isEmpty(), "an invitation is not listed");

        FakeServer.tick(45);
        assertEquals(TableState.ENDED, table.state());
        assertEquals("ended EXPIRED", calls.getLast());
        assertTrue(told(host, "Your invitation to Ann expired."));
        assertTrue(told(ann, "The invitation from Host expired."));
        assertTrue(Sessions.isFree(host.player()));

        lobby.accept(ann.player(), "Host");
        assertTrue(told(ann, "no invitation from Host"));
    }

    @Test
    @DisplayName("an invitation is accepted by name or as the newest, and declined")
    void answers() {
        lobby.create(host.player(), "chess", rules(2, 2), ann.player()).orElseThrow();
        lobby.accept(ann.player(), null);
        assertEquals("started 2", calls.getLast());

        assertTrue(lobby.create(bob.player(), "chess", rules(2, 2), host.player()).isEmpty());
        assertTrue(told(bob, "Host is busy right now"), "the host is playing");
        assertTrue(Sessions.isFree(bob.player()));

        calls.clear();
        FakePlayer cy = new FakePlayer("Cy");
        FakeServer.online(host.player(), ann.player(), bob.player(), cy.player());
        lobby.create(bob.player(), "chess", rules(2, 2), cy.player()).orElseThrow();
        lobby.decline(cy.player(), "bob");
        assertEquals("ended DECLINED", calls.getLast());
        assertTrue(told(bob, "Cy declined your invitation."));
        assertTrue(Sessions.isFree(bob.player()));
    }

    @Test
    @DisplayName("an invitation to several keeps its seats for them and starts once every one sat down")
    void invitationToSeveral() {
        FakePlayer cy = new FakePlayer("Cy");
        FakeServer.online(host.player(), ann.player(), bob.player(), cy.player());
        Table<String> table = lobby.invite(host.player(), "poker", rules(2, 3), List.of(ann.player(), bob.player()))
                .orElseThrow();
        assertTrue(told(host, "Invitation sent to Ann, Bob."));
        assertTrue(told(bob, "Host invites you to their table."));
        assertTrue(lobby.open(subject -> true).isEmpty(), "an invitation is not listed");

        lobby.accept(ann.player(), "Host");
        assertFalse(table.isPublic(), "the last seat stays Bob's");
        lobby.join(cy.player(), table);
        assertEquals(2, table.seats().size());
        lobby.accept(bob.player(), null);
        assertEquals("started 3", calls.getLast());
    }

    @Test
    @DisplayName("one invitee declining leaves the others; the host may start with those who accepted")
    void severalDecline() {
        Table<String> table = lobby.invite(host.player(), "poker", rules(2, 3), List.of(ann.player(), bob.player()))
                .orElseThrow();
        lobby.accept(ann.player(), null);
        lobby.decline(bob.player(), "Host");
        assertEquals(TableState.OPEN, table.state());
        assertTrue(told(host, "Bob declined your invitation."));
        assertTrue(lobby.invitesTo(bob.player().getUniqueId()).isEmpty());
        assertTrue(told(host, "Enough players are seated."));
        lobby.start(host.player());
        assertEquals("started 2", calls.getLast());

        calls.clear();
        lobby.finish(table);
        lobby.invite(host.player(), "poker", rules(2, 3), List.of(ann.player(), bob.player())).orElseThrow();
        lobby.decline(ann.player(), null);
        assertFalse(calls.contains("ended DECLINED"), "Bob may still answer");
        lobby.decline(bob.player(), null);
        assertEquals("ended DECLINED", calls.getLast());
        assertTrue(Sessions.isFree(host.player()));
    }

    @Test
    @DisplayName("a refused admission gives the seat back and tells the player why")
    void refused() {
        Table<String> table = lobby.create(host.player(), "chess", rules(2, 2), null).orElseThrow();
        answers.add(CompletableFuture.completedFuture("%prefix% You cannot pay %seats%."));
        lobby.join(ann.player(), table);
        assertTrue(told(ann, "You cannot pay 2."));
        assertEquals(TableState.OPEN, table.state());
        assertEquals(1, table.seats().size());
        assertTrue(Sessions.isFree(ann.player()));

        CompletableFuture<String> slow = new CompletableFuture<>();
        answers.add(slow);
        lobby.join(bob.player(), table);
        assertEquals(TableState.STARTING, table.state(), "the seat is held while it is asked");
        lobby.cancel(table);
        slow.complete(null);
        assertEquals(List.of("ended CANCELLED", "released Bob"), calls.subList(calls.size() - 2, calls.size()),
                "a yes for a table that ended meanwhile is given back");
        assertTrue(Sessions.isFree(bob.player()));
    }

    @Test
    @DisplayName("the host starts early with the fewest seated, and a leaver mid-play keeps their seat number")
    void earlyStartAndDrop() {
        Table<String> table = lobby.create(host.player(), "chess", rules(2, 4), null).orElseThrow();
        lobby.start(host.player());
        assertTrue(told(host, "at least 2"));
        lobby.join(ann.player(), table);
        assertTrue(told(host, "Enough players are seated."));
        lobby.join(bob.player(), table);
        lobby.start(host.player());
        assertEquals("started 3", calls.getLast());
        assertTrue(lobby.play(table));

        assertTrue(lobby.leave(ann.player()));
        assertEquals("dropped Ann", calls.getLast());
        assertTrue(table.hasLeft(1));
        assertTrue(Sessions.isFree(ann.player()));
        assertTrue(lobby.of(ann.player().getUniqueId()).isEmpty());
        assertEquals(TableState.PLAYING, table.state());
        assertFalse(lobby.leave(ann.player()), "no longer at a table");
    }

    @Test
    @DisplayName("another plugin asking for a waiting player takes them off the table; the host's closes it")
    void eviction() {
        Table<String> table = lobby.create(host.player(), "chess", rules(2, 3), null).orElseThrow();
        lobby.join(ann.player(), table);
        assertTrue(Sessions.release(ann.player()));
        assertEquals("released Ann", calls.getLast());
        assertTrue(Sessions.isFree(ann.player()));
        assertTrue(told(host, "Ann left the table"));

        lobby.join(bob.player(), table);
        assertTrue(Sessions.release(host.player()));
        assertEquals("ended CANCELLED", calls.getLast());
        assertTrue(told(bob, "Host closed the table."));
        assertTrue(Sessions.isFree(bob.player()));
    }

    @Test
    @DisplayName("leaving a table that is about to start calls it off for everyone")
    void abandoned() {
        Table<String> table = lobby.create(host.player(), "chess", rules(2, 2), null).orElseThrow();
        lobby.join(ann.player(), table);
        lobby.leave(ann.player());
        assertEquals("ended CANCELLED", calls.getLast());
        assertTrue(told(host, "Ann left before the start"));
        assertFalse(lobby.play(table));
    }
}
