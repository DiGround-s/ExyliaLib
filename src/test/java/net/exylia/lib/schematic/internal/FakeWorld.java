package net.exylia.lib.schematic.internal;

import net.exylia.lib.FakeServer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A world that records who read it.
 *
 * <p>The recording is the point. Reading the players in a box and reading their
 * locations is a world read, so it must happen on the thread that owns the box
 * and never inside the asynchronous stage that pasted the blocks. A test proves
 * that by asserting the world has <em>not</em> been read yet at the moment the
 * paste finished, and has been once the server ticks.
 *
 * <p>The UUID is computed once rather than on every {@code getUID()}, unlike
 * {@link FakeServer#newWorld}: this one is asked for its identity inside the
 * code under test, and a hash per call would be measuring the harness.
 */
final class FakeWorld {

    private final String name;
    private final UUID id;
    private final World proxy;

    /** Every thread that read this world, in order, with duplicates. */
    private final List<String> readers = Collections.synchronizedList(new ArrayList<>());

    /** Blocks that are not air, as {@code x:y:z}. Everything else is empty. */
    private final Set<String> solid = new HashSet<>();

    private final List<Player> players = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();

    /** A shared log, so a test can assert the order of the three stages. */
    private final List<String> log;

    FakeWorld(String name, List<String> log) {
        this.name = name;
        this.log = log;
        this.id = UUID.nameUUIDFromBytes(name.getBytes());
        this.proxy = (World) Proxy.newProxyInstance(
                FakeWorld.class.getClassLoader(),
                new Class<?>[]{World.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getName" -> this.name;
                    case "getUID" -> this.id;
                    case "getPlayers" -> {
                        record("rescue");
                        yield List.copyOf(players);
                    }
                    case "getNearbyEntities" -> {
                        record("clear");
                        yield List.copyOf(entities);
                    }
                    case "getBlockAt" -> block(args);
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> "FakeWorld[" + this.name + "]";
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
    }

    private void record(String what) {
        readers.add(Thread.currentThread().getName());
        log.add(what);
    }

    private Object block(Object[] args) {
        if (args == null || args.length != 3) {
            return null;
        }
        String key = args[0] + ":" + args[1] + ":" + args[2];
        boolean empty = !solid.contains(key);
        return Proxy.newProxyInstance(
                FakeWorld.class.getClassLoader(),
                new Class<?>[]{Block.class},
                (self, method, ignored) -> switch (method.getName()) {
                    // isEmpty(), never Material.isAir(): the latter resolves
                    // against org.bukkit.Registry and throws
                    // ExceptionInInitializerError without a live server, which
                    // would make the rescue logic untestable.
                    case "isEmpty" -> empty;
                    case "toString" -> "FakeBlock[" + key + "]";
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == ignored[0];
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
    }

    /** The world to hand to the code under test. */
    World world() {
        return proxy;
    }

    /** Every thread that read this world, in order. */
    List<String> readerThreads() {
        synchronized (readers) {
            return List.copyOf(readers);
        }
    }

    /** Puts a player in this world, so a rescue can find them. */
    FakeWorld with(Player player) {
        players.add(player);
        return this;
    }

    /** Puts an entity in this world, so a clear can remove it. */
    FakeWorld with(Entity entity) {
        entities.add(entity);
        return this;
    }

    /** Fills a column with blocks that are not air, both ends included. */
    FakeWorld fill(int x, int fromY, int toY, int z) {
        for (int y = fromY; y <= toY; y++) {
            solid.add(x + ":" + y + ":" + z);
        }
        return this;
    }

    /** Every entity currently in this world, for asserting on removals. */
    Collection<Entity> entities() {
        return List.copyOf(entities);
    }
}
