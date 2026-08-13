package net.exylia.lib.effect.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.effect.Effects;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packet path is a preference, never a requirement.
 *
 * <p>These exist because a live server heard nothing: the packet registry did
 * not know the sound name, {@code show()} returned {@code false}, and nothing
 * ever tried the Bukkit API.
 */
class EffectFallbackTest {

    private FakePlayer player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Packets.reset();
        player = new FakePlayer("Steve")
                .at(new Location(FakeServer.newWorld("world"), 0, 64, 0));
    }

    @AfterEach
    void tearDown() {
        Packets.reset();
        SoundBuilder.keyResolver = null;
        FakeServer.reset();
    }

    @Test
    @DisplayName("a sound plays through Bukkit when packets are unavailable")
    void soundFallsBackToBukkit() {
        Packets.override(false);

        boolean played = Effects.sound("BLOCK_NOTE_BLOCK_PLING").show(player.player());

        assertTrue(played);
        assertEquals(1, player.sounds().size());
        // Without a server registry the enum cannot be consulted; what must
        // hold either way is that no dots are invented.
        assertEquals("block_note_block_pling", player.sounds().get(0));
    }

    @Test
    @DisplayName("an enum name reaches the client as the key the enum knows")
    void enumNameBecomesItsRealKey() {
        // What the enum answers on a real server: the underscore inside
        // note_block stays an underscore, which no string rule would guess.
        SoundBuilder.keyResolver = name -> "minecraft:block.note_block.pling";
        Packets.override(false);

        Effects.sound("BLOCK_NOTE_BLOCK_PLING").show(player.player());

        assertEquals("minecraft:block.note_block.pling", player.sounds().get(0));
    }

    @Test
    @DisplayName("a broken packet path still ends in the Bukkit API")
    void brokenPacketPathFallsBack() {
        // PacketEvents is absent from the test classpath, so forcing the packet
        // path makes it fail exactly the way a PlugMan classloader does.
        Packets.override(true);

        boolean played = Effects.sound("BLOCK_NOTE_BLOCK_PLING").show(player.player());

        assertTrue(played, "a packet path that cannot serve must end in Bukkit");
        assertEquals(1, player.sounds().size());
    }

    @Test
    @DisplayName("a custom sound keeps its namespace through the fallback")
    void namespaceSurvives() {
        Packets.override(false);

        Effects.sound("myresourcepack:custom.jingle").show(player.player());

        // The old fallback stripped everything before the colon, sending the
        // client looking for minecraft:custom.jingle, which does not exist.
        assertEquals("myresourcepack:custom.jingle", player.sounds().get(0));
    }

    @Test
    @DisplayName("a raw key is passed to Bukkit unchanged")
    void rawKeyIsNotInvented() {
        Packets.override(false);

        Effects.sound("custom.menu_open").show(player.player());

        // Bukkit's string API deliberately accepts resource-pack keys it cannot
        // validate server-side. Mutating this would make a valid custom sound
        // silently disappear on the client.
        assertEquals("custom.menu_open", player.sounds().get(0));
    }

    @Test
    @DisplayName("a particle falls back the same way")
    void particleFallsBack() {
        Packets.override(true);

        // Does not throw and does not lose the effect silently.
        Effects.particle("FLAME").count(5).show(player.player());
    }
}
