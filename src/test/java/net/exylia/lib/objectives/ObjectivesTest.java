package net.exylia.lib.objectives;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.objectives.internal.ObjectiveRuntime;
import net.exylia.lib.task.Tasks;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ObjectivesTest {

    private Plugin plugin;
    private Player player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        ObjectiveRuntime.resetForTests();
        plugin = FakeServer.newPlugin("Quests");
        player = new FakePlayer("Steve").at(new Location(FakeServer.newWorld("world"), 0, 64, 0)).player();
    }

    @AfterEach
    void tearDown() {
        Objectives.release("Quests");
        ObjectiveRuntime.resetForTests();
        Tasks.releaseAll();
    }

    @Test
    void filtersMatchAnythingExactAndGlobs() {
        assertTrue(Objectives.matches("*", "DIAMOND_ORE"));
        assertTrue(Objectives.matches("", "DIAMOND_ORE"));
        assertTrue(Objectives.matches(null, null));
        assertTrue(Objectives.matches("diamond_ore", "DIAMOND_ORE"));
        assertTrue(Objectives.matches("minecraft:diamond_ore", "diamond_ore"));
        assertFalse(Objectives.matches("diamond_ore", "DEEPSLATE_DIAMOND_ORE"));
        assertTrue(Objectives.matches("*_ore", "DEEPSLATE_DIAMOND_ORE"));
        assertTrue(Objectives.matches("*diamond*", "DEEPSLATE_DIAMOND_ORE"));
        assertTrue(Objectives.matches("oak_?og", "OAK_LOG"));
        assertFalse(Objectives.matches("*_ore", "STONE"));
        assertFalse(Objectives.matches("oak_?og", "OAK_LO"));
        assertFalse(Objectives.matches("stone", null));
        assertFalse(Objectives.matches("#not a tag", "STONE"));
        assertTrue(Objectives.matchesAny(List.of(), "STONE"));
        assertTrue(Objectives.matchesAny(List.of("dirt", "*_ore"), "IRON_ORE"));
        assertFalse(Objectives.matchesAny(List.of("dirt"), "IRON_ORE"));
    }

    @Test
    void commandsChatKeysAndNpcs() {
        assertTrue(Objectives.command("/spawn", "/SPAWN now"));
        assertTrue(Objectives.command("spawn", "/spawn"));
        assertFalse(Objectives.command("/spawn", "/spawner"));
        assertFalse(Objectives.command(" ", "/x"));
        assertTrue(Objectives.chat("Hello", "well HELLO there"));
        assertFalse(Objectives.chat("", "hi"));
        assertTrue(Objectives.key("exylia:mines:break", "exylia:mines:break:gold"));
        assertFalse(Objectives.key("exylia:mines:break:gold", "exylia:mines:break"));
        assertFalse(Objectives.key("exylia:mines:break", "exylia:mines:breaker"));
        assertTrue(Objectives.npc("guide", "exyliadisplays:Guide"));
        assertFalse(Objectives.npc("guide", "guard"));
    }

    @Test
    void deliversOnlyToSubscribersThatWantThePlayer() {
        List<ObjectiveProgress> wanted = new ArrayList<>();
        List<ObjectiveProgress> unwanted = new ArrayList<>();
        Objectives.of(plugin).listen(ObjectiveType.LEVEL, p -> true, wanted::add);
        Objectives.of(plugin).listen(ObjectiveType.LEVEL, p -> false, unwanted::add);

        FakeServer.dispatch(new PlayerLevelChangeEvent(player, 2, 5));
        FakeServer.dispatch(new PlayerLevelChangeEvent(player, 5, 1));

        assertEquals(1, wanted.size());
        assertEquals("5", wanted.get(0).thing());
        assertEquals(3, wanted.get(0).amount());
        assertTrue(unwanted.isEmpty());
    }

    @Test
    void nothingIsBuiltForATypeNobodyListensTo() {
        List<ObjectiveProgress> got = new ArrayList<>();
        Objectives.of(plugin).listen(ObjectiveType.CHAT, p -> true, got::add);
        FakeServer.dispatch(new PlayerLevelChangeEvent(player, 0, 1));
        assertTrue(got.isEmpty());
        assertFalse(ObjectiveRuntime.any(ObjectiveType.LEVEL));
    }

    @Test
    void commandsCountAfterTheCommandRan() {
        List<ObjectiveProgress> got = new ArrayList<>();
        Objectives.of(plugin).listen(ObjectiveType.RUN_COMMAND, p -> true, got::add);
        FakeServer.dispatch(new PlayerCommandPreprocessEvent(player, "/spawn now", Set.of()));
        assertTrue(got.isEmpty());
        FakeServer.tick(1);
        assertEquals("/spawn now", got.get(0).thing());
    }

    @Test
    void customObjectivesCarryTheirType() {
        List<ObjectiveProgress> got = new ArrayList<>();
        Objectives.of(plugin).listen(ObjectiveType.CUSTOM, p -> true, got::add);
        Objectives.fire(player, "exylia:mines:break", "gold", 0);
        ObjectiveProgress progress = got.get(0);
        assertEquals("exylia:mines:break", progress.customType());
        assertEquals("gold", progress.thing());
        assertEquals(1, progress.amount());
    }

    @Test
    void aFailingHandlerDoesNotStopTheOthers() {
        List<ObjectiveProgress> got = new ArrayList<>();
        Objectives.of(plugin).listen(ObjectiveType.CUSTOM, p -> true, p -> { throw new IllegalStateException("boom"); });
        Objectives.of(plugin).listen(ObjectiveType.CUSTOM, p -> true, got::add);
        Objectives.fire(player, "x", "", 1);
        assertEquals(1, got.size());
    }

    @Test
    void cancelledAndReleasedSubscriptionsStop() {
        List<ObjectiveProgress> got = new ArrayList<>();
        Subscription subscription = Objectives.of(plugin).listen(ObjectiveType.CUSTOM, p -> true, got::add);
        subscription.cancel();
        Objectives.fire(player, "x", "", 1);
        Objectives.of(plugin).listen(ObjectiveType.CUSTOM, p -> true, got::add);
        Objectives.release("Quests");
        Objectives.fire(player, "x", "", 1);
        assertTrue(got.isEmpty());
    }

    @Test
    void progressSaysWhereAMobCameFrom() {
        UUID victim = UUID.randomUUID();
        ObjectiveProgress spawner = new ObjectiveProgress(player, ObjectiveType.KILL_MOB, "ZOMBIE", player.getLocation(),
                1, false, net.exylia.lib.util.SpawnOrigin.SPAWNER, null, null);
        assertTrue(spawner.fromSpawner());
        assertTrue(spawner.farmed());
        ObjectiveProgress kill = new ObjectiveProgress(player, ObjectiveType.KILL_PLAYER, "PLAYER", player.getLocation(),
                1, false, null, victim, null);
        assertFalse(kill.fromSpawner());
        assertEquals(victim, kill.victim());
    }

    @Test
    void indexAnswersWhoWaitsForWhat() {
        ObjectiveIndex<ObjectiveType> index = new ObjectiveIndex<>();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertFalse(index.any(ObjectiveType.BREAK_BLOCK));
        index.set(a, Set.of(ObjectiveType.BREAK_BLOCK, ObjectiveType.CHAT));
        index.set(b, Set.of(ObjectiveType.CHAT));
        assertTrue(index.has(ObjectiveType.BREAK_BLOCK, a));
        assertFalse(index.has(ObjectiveType.BREAK_BLOCK, b));
        index.set(a, Set.of(ObjectiveType.REACH));
        assertFalse(index.any(ObjectiveType.BREAK_BLOCK));
        index.remove(b);
        assertFalse(index.any(ObjectiveType.CHAT));
    }
}
