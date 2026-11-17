package net.exylia.lib.party;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartyBookTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test
    void inviteAcceptLeaveEndsLoneParty() {
        PartyBook book = new PartyBook(3);
        assertEquals(PartyBook.Invite.SENT, book.invite(a, b, 0, 1000).result());
        assertNotNull(book.of(a), "an invitation starts the party");
        assertEquals(PartyBook.Invite.ALREADY_INVITED, book.invite(a, b, 0, 1000).result());
        assertEquals(PartyBook.Answer.JOINED, book.accept(b, null, 10).result());
        assertEquals(2, book.of(b).size());
        PartyBook.Removal removal = book.leave(b);
        assertTrue(removal.ended());
        assertNull(book.of(a));
    }

    @Test
    void fullPartyRefusesAndLeaderMovesOnQuit() {
        PartyBook book = new PartyBook(2);
        book.invite(a, b, 0, 1000);
        book.accept(b, null, 0);
        assertEquals(PartyBook.Invite.FULL, book.invite(a, c, 0, 1000).result());
        assertEquals(PartyBook.Invite.NOT_LEADER, book.invite(b, c, 0, 1000).result());
        assertEquals(b, book.quit(a, 0, Set.of(b)));
        assertEquals(b, book.of(a).leader());
        PartyBook.Sweep sweep = book.sweep(5000, 5000);
        assertEquals(1, sweep.removed().size());
        assertNull(book.of(b), "alone after the timeout, the party ends");
    }

    @Test
    void invitesExpireAndSwitchingLeavesTheOldParty() {
        PartyBook book = new PartyBook(5);
        book.invite(a, b, 0, 100);
        assertEquals(1, book.sweep(200, 1000).invites().size());
        assertNull(book.of(a), "a party only waiting on an invitation ends with it");
        book.invite(a, b, 0, 1000);
        book.accept(b, null, 0);
        book.invite(c, b, 0, 1000);
        PartyBook.AcceptOutcome outcome = book.accept(b, c, 0);
        assertEquals(PartyLeave.SWITCHED, outcome.left().reason());
        assertEquals(c, book.of(b).leader());
    }

    @Test
    void readyCheckCountsVotesAndBreaksOnChange() {
        PartyBook book = new PartyBook(5);
        book.invite(a, b, 0, 1000);
        book.accept(b, null, 0);
        book.invite(a, c, 0, 1000);
        book.accept(c, null, 0);
        book.startReady(book.of(a).id(), "run", 1000);
        assertEquals(PartyBook.Vote.COUNTED, book.vote(b, true).result());
        assertEquals(PartyBook.Vote.ALL_READY, book.vote(c, true).result());
        assertEquals(PartyBook.Vote.NO_CHECK, book.vote(c, true).result());

        PartyBook.Ready second = book.startReady(book.of(a).id(), "run", 1000);
        book.quit(c, 0, Set.of(a, b));
        assertEquals(ReadyResult.CHANGED, second.future.getNow(null));
        PartyBook.Ready third = book.startReady(book.of(a).id(), "run", 1000);
        assertEquals(ReadyResult.OFFLINE, third.future.getNow(null));
    }
}
