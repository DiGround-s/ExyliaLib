package net.exylia.lib.util.combat;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.combat.internal.CulpritTracker;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Who is behind the hits that do not come from a hand.
 *
 * <p>What is proved is the chain a PvP rule relies on: a crystal counts as
 * whoever set it off however many crystals, arrows or anchors stood between,
 * and an anchor or bed blown up where it cannot work counts as whoever clicked it.
 */
class CulpritsTest {

    private final CulpritTracker tracker = new CulpritTracker();
    private final DamageSource anonymous = fake(DamageSource.class, Map.of());
    private FakePlayer attacker;
    private FakePlayer victim;
    private World world;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        attacker = new FakePlayer("Attacker");
        victim = new FakePlayer("Victim");
        FakeServer.online(attacker.player(), victim.player());
        world = FakeServer.newWorld("overworld");
    }

    @AfterEach
    void tearDown() {
        CulpritTracker.shutdown();
        FakeServer.reset();
    }

    @Test
    @DisplayName("a crystal a player hit counts as that player")
    void crystalHitByHand() {
        EnderCrystal crystal = fake(EnderCrystal.class, Map.of());
        tracker.onCrystalDamage(hit(attacker.player(), crystal));

        assertSame(attacker.player(), Culprits.playerBehind(hit(crystal, victim.player())));
    }

    @Test
    @DisplayName("a crystal set off by another crystal counts as whoever set off the first")
    void crystalChain() {
        EnderCrystal first = fake(EnderCrystal.class, Map.of());
        EnderCrystal second = fake(EnderCrystal.class, Map.of());
        tracker.onCrystalDamage(hit(attacker.player(), first));
        tracker.onCrystalDamage(hit(first, second));

        assertSame(attacker.player(), Culprits.playerBehind(hit(second, victim.player())));
    }

    @Test
    @DisplayName("a crystal shot with an arrow or blown by TNT counts as the archer or the igniter")
    void crystalByArrowAndTnt() {
        EnderCrystal shot = fake(EnderCrystal.class, Map.of());
        tracker.onCrystalDamage(hit(fake(Arrow.class, Map.of("getShooter", attacker.player())), shot));
        assertSame(attacker.player(), Culprits.playerBehind(hit(shot, victim.player())));

        EnderCrystal blown = fake(EnderCrystal.class, Map.of());
        tracker.onCrystalDamage(hit(fake(TNTPrimed.class, Map.of("getSource", attacker.player())), blown));
        assertSame(attacker.player(), Culprits.playerBehind(hit(blown, victim.player())));
    }

    @Test
    @DisplayName("a crystal nobody set off counts as nobody")
    void untrackedCrystal() {
        assertNull(Culprits.playerBehind(hit(fake(EnderCrystal.class, Map.of()), victim.player())));
    }

    @Test
    @DisplayName("a respawn anchor clicked outside the nether counts as the player who clicked it, and so does a crystal it blew")
    void respawnAnchor() {
        Block anchor = block(Material.RESPAWN_ANCHOR, 10, 64, 10, null);
        tracker.onInteract(new PlayerInteractEvent(attacker.player(), Action.RIGHT_CLICK_BLOCK, null, anchor, BlockFace.UP));

        assertSame(attacker.player(), Culprits.playerBehind(blast(anchor, victim.player())));

        EnderCrystal crystal = fake(EnderCrystal.class, Map.of());
        tracker.onCrystalDamage(blast(anchor, crystal));
        assertSame(attacker.player(), Culprits.playerBehind(hit(crystal, victim.player())));
    }

    @Test
    @DisplayName("a bed clicked outside the overworld counts as the clicker, whichever half explodes")
    void bed() {
        Map<String, Object> data = Map.of("getPart", Bed.Part.FOOT, "getFacing", BlockFace.NORTH);
        Block head = block(Material.RED_BED, 0, 70, -1, null);
        Block foot = block(Material.RED_BED, 0, 70, 0, Map.of(BlockFace.NORTH, head));
        Block footWithData = withData(foot, fake(Bed.class, data));
        tracker.onInteract(new PlayerInteractEvent(attacker.player(), Action.RIGHT_CLICK_BLOCK, null, footWithData, BlockFace.UP));

        assertSame(attacker.player(), Culprits.playerBehind(blast(head, victim.player())));
    }

    @Test
    @DisplayName("hooks, wind charges and channeling lightning follow the player who used them")
    void otherSources() {
        assertSame(attacker.player(), Culprits.playerBehind(fake(FishHook.class, Map.of("getShooter", attacker.player()))));
        assertSame(attacker.player(), Culprits.playerBehind(fake(WindCharge.class, Map.of("getShooter", attacker.player()))));
        assertSame(attacker.player(), Culprits.playerBehind(fake(LightningStrike.class, Map.of("getCausingEntity", attacker.player()))));
    }

    private EntityDamageByEntityEvent hit(Entity damager, Entity target) {
        return new EntityDamageByEntityEvent(damager, target, DamageCause.ENTITY_ATTACK, anonymous, 1.0);
    }

    private EntityDamageByBlockEvent blast(Block block, Entity target) {
        return new EntityDamageByBlockEvent(block, null, target, DamageCause.BLOCK_EXPLOSION, anonymous, 1.0);
    }

    private Block block(Material type, int x, int y, int z, Map<BlockFace, Block> neighbours) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("getType", type);
        answers.put("getWorld", world);
        answers.put("getX", x);
        answers.put("getY", y);
        answers.put("getZ", z);
        answers.put("getLocation", new org.bukkit.Location(world, x, y, z));
        return (Block) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Block.class},
                (self, method, args) -> method.getName().equals("getRelative") && neighbours != null
                        ? neighbours.get(args[0])
                        : answer(self, method, args, answers));
    }

    private static Block withData(Block block, Object data) {
        return (Block) Proxy.newProxyInstance(CulpritsTest.class.getClassLoader(), new Class<?>[]{Block.class},
                (self, method, args) -> method.getName().equals("getBlockData") ? data : method.invoke(block, args));
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Map<String, Object> answers) {
        UUID id = UUID.randomUUID();
        return (T) Proxy.newProxyInstance(CulpritsTest.class.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> method.getName().equals("getUniqueId") ? id : answer(self, method, args, answers));
    }

    private static Object answer(Object self, java.lang.reflect.Method method, Object[] args, Map<String, Object> answers) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            case "toString" -> "Fake" + method.getDeclaringClass().getSimpleName();
            default -> answers.containsKey(method.getName())
                    ? answers.get(method.getName())
                    : FakeServer.defaultValue(method.getReturnType());
        };
    }
}
