package net.exylia.lib.item;

import io.papermc.paper.persistence.PersistentDataContainerView;
import net.exylia.lib.FakeServer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a plugin stores on a live item, and what it reads back.
 *
 * <p>The container is faked rather than mocked at the Bukkit level: the point
 * under test is that a value written as one type is still readable as another,
 * which needs a store that actually enforces types the way the server's does.
 */
class ItemValuesTest {

    private Plugin plugin;
    private ItemValues values;
    private ItemStack item;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        plugin = FakeServer.newPlugin("ItemValuesTestPlugin", null);
        values = new ItemValues(plugin);
        item = new Stack(Material.DIAMOND_SWORD);
    }

    @Test
    @DisplayName("stores and reads back each type it writes")
    void roundTrip() {
        values.set(item, "id", "reapers-edge");
        values.set(item, "kills", 12L);
        values.set(item, "multiplier", 1.5);
        values.set(item, "bound", true);

        assertEquals("reapers-edge", values.text(item, "id", ""));
        assertEquals(12L, values.number(item, "kills", 0));
        assertEquals(1.5, values.decimal(item, "multiplier", 0.0));
        assertTrue(values.flag(item, "bound", false));
    }

    @Test
    @DisplayName("reads a value whatever type it was written as")
    void typeTolerance() {
        // What the declarative nbt block writes for `uses: 3`.
        raw().set(new NamespacedKey(plugin, "uses"), PersistentDataType.INTEGER, 3);

        assertEquals("3", values.text(item, "uses", ""),
                "commons answered null here, which read as a fresh item");
        assertEquals(3L, values.number(item, "uses", 0));
        assertEquals(3.0, values.decimal(item, "uses", 0.0));

        values.set(item, "charges", "7");
        assertEquals(7L, values.number(item, "charges", 0), "and the other way round");
    }

    @Test
    @DisplayName("overwriting a key does not leave the old type behind")
    void overwriteReplacesType() {
        values.set(item, "uses", "5");
        values.set(item, "uses", 2L);

        assertEquals(2L, values.number(item, "uses", 0));
        assertEquals(1, raw().getKeys().size(), "a rewrite replaces the entry rather than adding a second");
    }

    @Test
    @DisplayName("will not invent a number out of text that is not one")
    void refusesToGuess() {
        values.set(item, "id", "banana");

        assertEquals(-1L, values.number(item, "id", -1));
        assertEquals(-1.0, values.decimal(item, "id", -1.0));
        assertTrue(values.flag(item, "id", true), "an unreadable flag keeps the caller's default");
    }

    @Test
    @DisplayName("a missing key gives the fallback, and says it is missing")
    void missingKey() {
        assertFalse(values.has(item, "id"));
        assertEquals("none", values.text(item, "id", "none"));
        assertEquals(4L, values.number(item, "id", 4));
        assertTrue(values.text(item, "id").isEmpty());
    }

    @Test
    @DisplayName("air and null are read and written without complaint")
    void toleratesNothing() {
        ItemStack air = new Stack(Material.AIR);

        values.set(air, "id", "x");
        values.set(null, "id", "x");

        assertFalse(values.has(air, "id"));
        assertFalse(values.has(null, "id"));
        assertEquals("none", values.text(null, "id", "none"));
        assertTrue(values.keys(null).isEmpty());
    }

    @Test
    @DisplayName("lists this plugin's keys and nobody else's")
    void listsOwnKeysOnly() {
        values.set(item, "id", "reapers-edge");
        values.set(item, "kills", 3L);
        raw().set(NamespacedKey.fromString("otherplugin:id"), PersistentDataType.STRING, "theirs");

        assertEquals(Set.of("id", "kills"), values.keys(item));
        assertEquals("reapers-edge", values.text(item, "id", ""),
                "and reads its own, not the other plugin's");
    }

    @Test
    @DisplayName("clearing removes the value and nothing else")
    void clearing() {
        values.set(item, "id", "x");
        values.set(item, "kills", 3L);

        values.clear(item, "id");

        assertFalse(values.has(item, "id"));
        assertEquals(3L, values.number(item, "kills", 0));
    }

    // ------------------------------------------------------------------
    // A container that enforces types the way the server's does
    // ------------------------------------------------------------------

    private PersistentDataContainer raw() {
        return item.getItemMeta().getPersistentDataContainer();
    }

    /** An item whose meta is one container, kept across get and set. */
    private static final class Stack extends ItemStack {

        private final Material type;
        private final ItemMeta meta = fakeMeta();

        private Stack(Material type) {
            this.type = type;
        }

        @Override
        public Material getType() {
            return type;
        }

        @Override
        public ItemMeta getItemMeta() {
            return type == Material.AIR ? null : meta;
        }

        @Override
        public PersistentDataContainerView getPersistentDataContainer() {
            // What the server's view reads is what the meta writes; the same
            // container here, so a set through the meta is visible to a read.
            return meta.getPersistentDataContainer();
        }

        @Override
        public boolean setItemMeta(ItemMeta replacement) {
            // The real one copies; here the caller always hands back the same
            // instance it was given, so there is nothing to copy.
            return true;
        }
    }

    private static ItemMeta fakeMeta() {
        PersistentDataContainer container = fakeContainer();
        return (ItemMeta) Proxy.newProxyInstance(
                ItemValuesTest.class.getClassLoader(),
                new Class<?>[]{ItemMeta.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPersistentDataContainer" -> container;
                    case "toString" -> "fake-meta";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> defaultOf(method);
                });
    }

    private static PersistentDataContainer fakeContainer() {
        Map<NamespacedKey, Object[]> stored = new LinkedHashMap<>();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "set" -> {
                stored.put((NamespacedKey) args[0], new Object[]{args[1], args[2]});
                yield null;
            }
            case "get" -> {
                Object[] entry = stored.get((NamespacedKey) args[0]);
                // The server answers null for the wrong type rather than
                // converting, which is the whole reason ItemValues tries several.
                yield entry != null && entry[0] == args[1] ? entry[1] : null;
            }
            case "has" -> args.length == 1
                    ? stored.containsKey((NamespacedKey) args[0])
                    : stored.get((NamespacedKey) args[0]) != null
                    && stored.get((NamespacedKey) args[0])[0] == args[1];
            case "remove" -> {
                stored.remove((NamespacedKey) args[0]);
                yield null;
            }
            case "getKeys" -> Set.copyOf(stored.keySet());
            case "isEmpty" -> stored.isEmpty();
            case "toString" -> stored.toString();
            case "hashCode" -> stored.hashCode();
            case "equals" -> proxy == args[0];
            default -> defaultOf(method);
        };
        return (PersistentDataContainer) Proxy.newProxyInstance(
                ItemValuesTest.class.getClassLoader(),
                new Class<?>[]{PersistentDataContainer.class},
                handler);
    }

    private static Object defaultOf(Method method) {
        Class<?> returns = method.getReturnType();
        if (returns == boolean.class) {
            return false;
        }
        if (returns.isPrimitive() && returns != void.class) {
            return 0;
        }
        return null;
    }
}
