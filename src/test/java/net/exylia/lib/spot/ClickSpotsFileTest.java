package net.exylia.lib.spot;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickSpotsFileTest {

    @Test
    void spotsSurviveAWriteAndARead() throws Exception {
        UUID villager = UUID.randomUUID();
        List<ClickSpots.Spot> spots = List.of(
                new ClickSpots.Spot("chess-1", "chess", "world", 10, 64, -3, null, null, true),
                new ClickSpots.Spot("dealer", "blackjack", "world", 1, 70, 2, villager, null, false),
                new ClickSpots.Spot("npc", "roulette", "casino", -5, 65, 8, null, "ExyliaDisplays:croupier", true));
        YamlConfiguration yaml = new YamlConfiguration();
        ClickSpots.write(yaml, spots);
        YamlConfiguration again = new YamlConfiguration();
        again.loadFromString(yaml.saveToString());

        ClickSpots.Read read = ClickSpots.read(again, 0, (w, x, y, z) -> false);

        assertFalse(read.legacy());
        assertEquals(3, read.spots().size());
        for (ClickSpots.Spot spot : spots) assertEquals(spot, read.spots().get(spot.id()));
    }

    @Test
    void anOldSeatedTableBecomesTheBlockUnderItsBoard() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                t1:
                  game: chess
                  world: world
                  x: 10.5
                  y: 65.0
                  z: -2.5
                  yaw: 90.0
                  seats:
                  - {x: 9.5, y: 64.0, z: -2.5, yaw: -90.0}
                  - {x: 11.5, y: 64.0, z: -2.5, yaw: 90.0}
                """);
        // y 65 is air above the table block at 64.
        ClickSpots.Read read = ClickSpots.read(yaml, 0, (w, x, y, z) -> y >= 65);

        assertTrue(read.legacy());
        assertEquals(new ClickSpots.Spot("t1", "chess", "world", 10, 64, -3, null, null, true), read.spots().get("t1"));
    }

    @Test
    void anOldTableWithoutSeatsBecomesTheBlockAheadOfItsSpot() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                b1:
                  game: blackjack
                  world: world
                  x: 0.5
                  y: 64.0
                  z: 0.5
                  yaw: 0.0
                """);
        // Facing yaw 0 is +z; two blocks ahead, standing height is air.
        ClickSpots.Read read = ClickSpots.read(yaml, 2, (w, x, y, z) -> y >= 64);

        Map<String, ClickSpots.Spot> spots = read.spots();
        assertEquals(new ClickSpots.Spot("b1", "blackjack", "world", 0, 63, 2, null, null, true), spots.get("b1"));
    }
}
