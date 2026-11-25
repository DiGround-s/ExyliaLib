package net.exylia.lib.packet.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GhostTeamsTest {

    private final UUID viewer = UUID.randomUUID();

    @AfterEach
    void clear() {
        GhostTeams.clear();
    }

    @Test
    void aFaintTargetLeavesItsNametagTeamAndGoesBackToIt() {
        // TAB put both in their nametag teams first.
        assertEquals(List.of("Alex"), GhostTeams.filter(viewer, "tab_alex", 0, List.of("Alex")));
        GhostTeams.filter(viewer, "tab_steve", 0, List.of("Steve"));

        List<GhostTeams.Op> on = GhostTeams.set(viewer, "Steve", "Alex", 7, true);
        assertEquals(List.of(new GhostTeams.Op(GhostTeams.Kind.CREATE, GhostTeams.TEAM, List.of("Steve", "Alex"))), on);
        assertTrue(GhostTeams.faint(viewer, 7));

        // TAB sending them again while faint would pull them out of the ghost team: stripped, but remembered.
        assertEquals(List.of(), GhostTeams.filter(viewer, "tab_alex2", 3, List.of("Alex")));
        assertEquals(List.of("Bob"), GhostTeams.filter(viewer, "tab_x", 0, List.of("Steve", "Bob")));
        assertEquals(List.of("x"), GhostTeams.filter(viewer, GhostTeams.TEAM, 3, List.of("x")));

        List<GhostTeams.Op> off = GhostTeams.set(viewer, "Steve", "Alex", 7, false);
        assertEquals(List.of(
                new GhostTeams.Op(GhostTeams.Kind.DISBAND, GhostTeams.TEAM, List.of()),
                new GhostTeams.Op(GhostTeams.Kind.ADD, "tab_alex2", List.of("Alex")),
                new GhostTeams.Op(GhostTeams.Kind.ADD, "tab_x", List.of("Steve"))), off);
        assertFalse(GhostTeams.faint(viewer, 7));
        assertFalse(GhostTeams.any());
        assertEquals(List.of("Alex"), GhostTeams.filter(viewer, "tab_alex", 3, List.of("Alex")));
    }

    @Test
    void secondTargetJoinsTheTeamAndALeaverIsDropped() {
        GhostTeams.set(viewer, "Steve", "Alex", 7, true);
        assertEquals(List.of(new GhostTeams.Op(GhostTeams.Kind.ADD, GhostTeams.TEAM, List.of("Bob"))),
                GhostTeams.set(viewer, "Steve", "Bob", 8, true));
        assertEquals(List.of(), GhostTeams.set(viewer, "Steve", "Bob", 8, true));
        var left = GhostTeams.leave("Bob", 8);
        assertEquals(List.of(new GhostTeams.Op(GhostTeams.Kind.REMOVE, GhostTeams.TEAM, List.of("Bob"))), left.get(viewer));
        assertTrue(GhostTeams.faint(viewer, 7));
        assertFalse(GhostTeams.faint(viewer, 8));
    }
}
