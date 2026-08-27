package net.exylia.lib.region.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.SelectionOptions;
import net.exylia.lib.region.SelectionSession;
import net.exylia.lib.region.WorldIdentity;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handing the tool over, and getting it back.
 *
 * <p>Two separate things are proved here, because two separate things went
 * wrong before:
 *
 * <ul>
 *   <li>ExyliaCommons wrote the wand straight into the main hand, so an admin
 *       holding anything lost it. The slot logic is asserted against a stand-in
 *       inventory.
 *   <li>The first port of this module handed out nothing at all, so an admin
 *       was told to select with a wooden axe they did not have. That the tool
 *       is given on start and taken back on <em>every</em> ending is asserted
 *       through the runtime, with the inventory writes behind their seam.
 * </ul>
 */
class SelectorWandTest {

    private static final WorldIdentity WORLD = new WorldIdentity(UUID.randomUUID(), "world");

    private Wand wand;
    private FakePlayer player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        wand = new Wand();
        SelectionRuntime.installWand(wand);
        player = new FakePlayer("Steve");
        FakeServer.online(player.player());
    }

    @AfterEach
    void tearDown() {
        SelectionRuntime.releaseAll();
        SelectionRuntime.resetWand();
        FakeServer.reset();
    }

    // ------------------------------------------------------------ the lifecycle

    @Test
    @DisplayName("starting a selection hands the player the tool")
    void givenOnStart() {
        begin(SelectionOptions.builder().feedback(false).previewParticle(null).build());

        assertEquals(1, wand.given, "An admin told to select needs the thing that selects");
        assertEquals(0, wand.taken);
    }

    @Test
    @DisplayName("confirming a selection takes the tool back")
    void takenOnConfirm() {
        begin(SelectionOptions.builder().feedback(false).previewParticle(null).build());

        SelectionRuntime.select(player.player().getUniqueId(), true, at(1, 2, 3));
        SelectionRuntime.select(player.player().getUniqueId(), false, at(4, 5, 6));
        assertTrue(SelectionRuntime.confirm(player.player().getUniqueId()));

        assertEquals(1, wand.taken);
    }

    @Test
    @DisplayName("cancelling takes the tool back too")
    void takenOnCancel() {
        SelectionSession session =
                begin(SelectionOptions.builder().feedback(false).previewParticle(null).build());

        assertTrue(session.cancel());
        assertEquals(1, wand.taken);
    }

    @Test
    @DisplayName("axes left behind by an earlier session are swept too")
    void sweepsEveryAxe() {
        SelectionSession session =
                begin(SelectionOptions.builder().feedback(false).previewParticle(null).build());
        wand.carried = 3;

        assertTrue(session.cancel());
        assertEquals(3, wand.removed,
                "Every selection axe goes, not only the one this session handed over");
    }

    @Test
    @DisplayName("the owning plugin being released takes the tool back")
    void takenOnRelease() {
        begin(SelectionOptions.builder().feedback(false).previewParticle(null).build());

        assertEquals(1, SelectionRuntime.release("SelectorWandOwner"));
        assertEquals(1, wand.taken, "A plugin that goes away must not leave its tool behind");
    }

    @Test
    @DisplayName("a caller that does not want the tool is never given one")
    void notGivenWhenOff() {
        SelectionSession session = begin(SelectionOptions.builder()
                .giveSelector(false)
                .feedback(false)
                .previewParticle(null)
                .build());

        assertEquals(0, wand.given);
        session.cancel();
        assertEquals(0, wand.taken,
                "A caller that hands out nothing does not sweep the player's inventory either");
    }

    // ---------------------------------------------------------------- the slot

    @Test
    @DisplayName("an empty hand gets the tool, so it is where the player is looking")
    void goesIntoAnEmptyHand() {
        Slots inventory = new Slots(9, 3);
        player.inventory(inventory.proxy());

        assertEquals(3, SelectorWand.BUKKIT.give(player.player(), new Stack()));
        assertEquals(3, inventory.filled());
    }

    @Test
    @DisplayName("a full hand is emptied into the inventory, and the tool takes the hand")
    void movesWhatWasHeld() {
        Slots inventory = new Slots(9, 3);
        ItemStack held = new Stack();
        inventory.put(3, held);
        player.inventory(inventory.proxy());

        ItemStack tool = new Stack();
        assertEquals(3, SelectorWand.BUKKIT.give(player.player(), tool),
                "The hand, always: that is where the player is looking");
        assertSame(tool, inventory.at(3));
        assertSame(held, inventory.at(0), "What was held goes to the first free slot");
    }

    @Test
    @DisplayName("an inventory with no free slot loses what was held, and still gets the tool")
    void destroysWhatCannotBeMoved() {
        Slots inventory = new Slots(2, 0);
        ItemStack held = new Stack();
        inventory.put(0, held);
        inventory.put(1, new Stack());
        player.inventory(inventory.proxy());

        ItemStack tool = new Stack();
        assertEquals(0, SelectorWand.BUKKIT.give(player.player(), tool));
        assertSame(tool, inventory.at(0));
        assertNotSame(held, inventory.at(1), "Nowhere to put it, so it is gone rather than duplicated");
    }

    // ------------------------------------------------------------------

    private SelectionSession begin(SelectionOptions options) {
        return SelectionRuntime.begin(plugin(), player.player(), options);
    }

    private static BlockPosition at(int x, int y, int z) {
        return new BlockPosition(WORLD, x, y, z);
    }

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "SelectorWandOwner";
                    case "toString" -> "Plugin[SelectorWandOwner]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
    }

    /** A selector that only counts. */
    private static final class Wand implements SelectorWand {

        private int given;
        private int taken;
        private int carried;
        private int removed;

        @Override
        public ItemStack build(Plugin owner, SelectionOptions options) {
            return new Stack();
        }

        @Override
        public int give(Player player, ItemStack item) {
            given++;
            return 0;
        }

        @Override
        public int take(Player player) {
            taken++;
            removed = carried > 0 ? carried : 1;
            return removed;
        }
    }

    /** Just enough inventory to say what is in which slot. */
    private static final class Slots {

        private final List<ItemStack> contents;
        private final int held;

        private Slots(int size, int held) {
            this.contents = new ArrayList<>(java.util.Collections.nCopies(size, null));
            this.held = held;
        }

        private void put(int slot, ItemStack item) {
            contents.set(slot, item);
        }

        private ItemStack at(int slot) {
            return contents.get(slot);
        }

        private int filled() {
            for (int slot = 0; slot < contents.size(); slot++) {
                if (contents.get(slot) != null) {
                    return slot;
                }
            }
            return -1;
        }

        private Object proxy() {
            return Proxy.newProxyInstance(
                    Slots.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.inventory.PlayerInventory.class},
                    (self, method, args) -> switch (method.getName()) {
                        case "getHeldItemSlot" -> held;
                        case "getSize" -> contents.size();
                        case "getItem" -> contents.get((int) args[0]);
                        case "setItem" -> {
                            contents.set((int) args[0], (ItemStack) args[1]);
                            yield null;
                        }
                        case "firstEmpty" -> {
                            int free = contents.indexOf(null);
                            yield free;
                        }
                        default -> FakeServer.defaultValue(method.getReturnType());
                    });
        }
    }

    /** An item that is only a material. */
    private static final class Stack extends ItemStack {

        @Override
        public Material getType() {
            return Material.GOLDEN_AXE;
        }

        @Override
        public int getAmount() {
            return 1;
        }
    }
}
