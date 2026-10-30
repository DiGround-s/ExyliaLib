package net.exylia.lib.placeholder.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.session.Sessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The session placeholders: what a player is doing, and how many are doing it. */
class BuiltInActivityTest {

    @AfterEach
    void tearDown() {
        Sessions.releaseAll();
        Registry.clear();
    }

    @Test
    void countsByPluginOrActivityAndNamesTheReadersOwn() {
        FakeServer.install();
        FakeServer.reset();
        Sessions.releaseAll();
        Registry.clear();
        BuiltIn.register(FakeServer.newPlugin("ExyliaLib"));

        FakePlayer ann = new FakePlayer("Ann");
        FakePlayer bob = new FakePlayer("Bob");
        FakePlayer cid = new FakePlayer("Cid");
        Sessions.of(FakeServer.newPlugin("ExyliaFFA")).claim(ann.player(), "FFA kit editor");
        Sessions.of(FakeServer.newPlugin("ExyliaDuels")).claim(bob.player(), "duel_spectate");

        assertEquals("2", Placeholders.apply("%server_playing%"));
        assertEquals("1", Placeholders.apply("%server_playing_exyliaffa%"));
        assertEquals("1", Placeholders.apply("%server_playing_ffa_kit_editor%"));
        assertEquals("1", Placeholders.apply("%server_playing_duel_spectate%"));
        assertEquals("0", Placeholders.apply("%server_playing_exyliaevents%"));
        assertEquals("FFA kit editor", Placeholders.apply("%player_activity%", ann.player()));
        assertEquals("", Placeholders.apply("%player_activity%", cid.player()));
        assertEquals("false", Placeholders.apply("%player_busy%", cid.player()));
    }
}
