package net.exylia.lib.util.reward;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.reward.internal.ItemGiver;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The leftovers of {@code Inventory.addItem}.
 *
 * <p>This is the one line ExyliaCommons got wrong, and the reason this module
 * exists. {@code addItem} returns what would not fit; commons discarded that
 * map, so an item a player had no room for was destroyed with no message, no log
 * and no failure.
 *
 * <p>Everything else in the module decides in terms of a snapshot string and a
 * count and is tested without a server. Only this reaches Bukkit, so only this
 * needs a stand-in inventory. The items are subclasses carrying nothing but an
 * amount, because {@code Material} needs a registry a real server owns and
 * nothing here reads one.
 */
class ItemGiverTest {

    private FakePlayer player;
    private Room room;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        room = new Room();
        player = new FakePlayer("Steve");
        player.inventory(room.inventory());
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    @Test
    @DisplayName("an item that fits reports nothing left over")
    void fits() {
        room.accepts(64);

        assertEquals(0, ItemGiver.hand(player.player(), new Stack(16)));
        assertEquals(List.of(16), room.accepted);
    }

    @Test
    @DisplayName("an item that does not fit reports every one of them")
    void doesNotFit() {
        room.accepts(0);

        assertEquals(16, ItemGiver.hand(player.player(), new Stack(16)),
                "commons discarded this and the item was destroyed");
        assertEquals(List.of(), room.accepted);
    }

    @Test
    @DisplayName("a partial fit reports only what was left")
    void partialFit() {
        room.accepts(10);

        assertEquals(6, ItemGiver.hand(player.player(), new Stack(16)));
        assertEquals(List.of(10), room.accepted);
    }

    @Test
    @DisplayName("several leftover stacks are counted together")
    void severalLeftovers() {
        room.leavesOver(4, 3, 2);

        assertEquals(9, ItemGiver.hand(player.player(), new Stack(16)));
    }

    /** An inventory whose {@code addItem} decides what does not fit. */
    private static final class Room {

        private final List<Integer> accepted = new ArrayList<>();
        private int capacity;
        private int[] leftovers;

        void accepts(int amount) {
            this.capacity = amount;
            this.leftovers = null;
        }

        void leavesOver(int... amounts) {
            this.capacity = 0;
            this.leftovers = amounts;
        }

        Object inventory() {
            return Proxy.newProxyInstance(
                    Room.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.inventory.PlayerInventory.class},
                    (self, method, args) -> method.getName().equals("addItem")
                            ? add((ItemStack[]) args[0])
                            : FakeServer.defaultValue(method.getReturnType()));
        }

        private Map<Integer, ItemStack> add(ItemStack[] items) {
            Map<Integer, ItemStack> leftOver = new HashMap<>();
            if (leftovers != null) {
                for (int index = 0; index < leftovers.length; index++) {
                    leftOver.put(index, new Stack(leftovers[index]));
                }
                return leftOver;
            }
            for (int index = 0; index < items.length; index++) {
                int amount = items[index].getAmount();
                int fits = Math.min(amount, capacity);
                if (fits > 0) {
                    accepted.add(fits);
                    capacity -= fits;
                }
                if (fits < amount) {
                    leftOver.put(index, new Stack(amount - fits));
                }
            }
            return leftOver;
        }
    }

    /** An item that is only an amount. */
    private static final class Stack extends ItemStack {

        private int amount;

        private Stack(int amount) {
            this.amount = amount;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public void setAmount(int amount) {
            this.amount = amount;
        }
    }
}
