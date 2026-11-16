package net.exylia.lib.stage;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FootingTest {

    /** A world whose floor is at {@code floorY} under every column, made of {@code liquid} or not. */
    private static World world(Double floorY, boolean liquid) {
        Block block = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                (p, m, a) -> m.getName().equals("isLiquid") ? liquid : null);
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "rayTraceBlocks": {
                    Location from = (Location) a[0];
                    if (floorY == null || from.getY() - (double) a[2] > floorY) return null;
                    return new RayTraceResult(new Vector(from.getX(), floorY, from.getZ()), block, null);
                }
                case "hasCollisionsIn": return false;
                case "equals": return p == a[0];
                case "hashCode": return System.identityHashCode(p);
                default: return null;
            }
        });
    }

    private static Player player(World world, double y, Map<String, Object> overrides) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, m, a) -> {
            if (overrides.containsKey(m.getName())) return overrides.get(m.getName());
            return switch (m.getName()) {
                case "getLocation" -> new Location(world, 0.5, y, 0.5);
                case "getUniqueId" -> id;
                case "getWidth" -> 0.6;
                case "getBoundingBox" -> new BoundingBox(0.2, y, 0.2, 0.8, y + 1.8, 0.8);
                case "getGameMode" -> GameMode.SURVIVAL;
                default -> m.getReturnType() == boolean.class ? false : null;
            };
        });
    }

    @Test
    void anAirbornePlayerGoesToTheFloorUnderThem() {
        Location floor = Footing.floorUnder(player(world(64.0, false), 65.2, Map.of()));
        assertEquals(64.0, floor.getY(), 1e-9);
        assertEquals(0.5, floor.getX(), 1e-9);
    }

    @Test
    void nothingMovesWhenThereIsNoReasonTo() {
        assertNull(Footing.floorUnder(player(world(64.0, false), 64.02, Map.of())), "already on it");
        assertNull(Footing.floorUnder(player(world(null, false), 65.2, Map.of())), "no floor within reach");
        assertNull(Footing.floorUnder(player(world(64.0, false), 64.0 + Footing.GROUND_REACH + 1, Map.of())), "a long fall");
        assertNull(Footing.floorUnder(player(world(64.0, true), 65.2, Map.of())), "water or lava under them");
        assertNull(Footing.floorUnder(player(world(64.0, false), 65.2, Map.of("isOnGround", true))), "standing");
        assertNull(Footing.floorUnder(player(world(64.0, false), 65.2, Map.of("isFlying", true))), "flying");
        assertNull(Footing.floorUnder(player(world(64.0, false), 65.2, Map.of("isGliding", true))), "gliding");
        assertNull(Footing.floorUnder(player(world(64.0, false), 65.2, Map.of("getGameMode", GameMode.CREATIVE))), "creative");
    }
}
