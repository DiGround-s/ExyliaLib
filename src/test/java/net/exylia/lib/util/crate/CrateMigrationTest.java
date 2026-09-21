package net.exylia.lib.util.crate;

import io.papermc.paper.persistence.PersistentDataContainerView;
import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.item.Items;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.util.crate.internal.CrateFaces;
import net.exylia.lib.util.crate.internal.CrateRow;
import net.exylia.lib.util.crate.internal.CrateStore;
import net.exylia.lib.util.crate.internal.TierTable;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a plugin moving its own crate onto this module relies on: the players'
 * old keys and unlocks come across once, the key items they carry still open
 * it, the menus their owners customised still resolve, and a reward can be
 * drawn as its real item.
 */
class CrateMigrationTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private Plugin plugin;
    private Player player;
    private final AtomicInteger asked = new AtomicInteger();
    private final AtomicReference<Function<UUID, CompletableFuture<CrateImport>>> answer =
            new AtomicReference<>(uuid -> CompletableFuture.completedFuture(null));
    private CrateStore store;

    @BeforeAll
    static void install() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("Effects");
        TestDatabases.memory(plugin, "migration" + DATABASE.incrementAndGet());
        store = new CrateStore(plugin, () -> 3, uuid -> { }, uuid -> {
            asked.incrementAndGet();
            return answer.get().apply(uuid);
        });
        player = new FakePlayer("Steve").player();
        FakeServer.online(player);
    }

    @AfterEach
    void close() {
        Items.release(plugin.getName());
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void imports(int keys, String... unlocked) {
        answer.set(uuid -> CompletableFuture.completedFuture(new CrateImport(keys, List.of(unlocked))));
    }

    private boolean stored(UUID uuid) {
        return await(Databases.of(plugin).repository(CrateRow.class).find(CrateRow.key(plugin.getName(), uuid)))
                .isPresent();
    }

    // ------------------------------------------------------------------
    // Importing
    // ------------------------------------------------------------------

    @Test
    void theOldRowSeedsTheNewOneInsteadOfTheStartKeys() {
        imports(7, "Griddy", "sentry", "bad,id", " ");
        UUID uuid = player.getUniqueId();
        await(store.open(uuid));

        CrateRow row = store.row(uuid);
        assertEquals(7, row.keys(), "the start keys were added to, or replaced, what they had");
        assertEquals(List.of("griddy", "sentry"), row.unlockedIds());
        assertEquals(1, asked.get());
    }

    @Test
    void theImportIsAskedOnlyWhenTheRowIsCreated() {
        imports(7);
        UUID uuid = player.getUniqueId();
        await(store.open(uuid));
        await(store.edit(uuid, current -> current.withKeys(1)));
        await(store.writesForTests(uuid));
        store.close(uuid);

        imports(50);
        await(store.open(uuid));
        assertEquals(1, store.row(uuid).keys(), "a second join imported the old keys again");
        assertEquals(1, asked.get());
    }

    @Test
    void somebodyTheOldTableNeverSawGetsTheStartKeys() {
        await(store.open(player.getUniqueId()));
        assertEquals(3, store.row(player.getUniqueId()).keys());
    }

    @Test
    void anOldPlayerWithNothingLeftIsNotWelcomedAgain() {
        imports(0);
        await(store.open(player.getUniqueId()));
        assertEquals(0, store.row(player.getUniqueId()).keys(), "an old player who spent everything got the start keys");
    }

    @Test
    void aFailedImportCreatesNoRowAndIsTriedAgain() {
        answer.set(uuid -> CompletableFuture.failedFuture(new IllegalStateException("the old table is down")));
        UUID uuid = player.getUniqueId();
        int before = FakeServer.liveTasks();
        CompletableFuture<Void> ready = store.open(uuid);

        // The failure is handled on the database's thread: wait for its retry.
        long deadline = System.currentTimeMillis() + 30_000;
        while (FakeServer.liveTasks() <= before && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertFalse(store.isLoaded(uuid));
        assertFalse(stored(uuid), "a row was created without what the player had");

        imports(9, "griddy");
        while (!ready.isDone() && System.currentTimeMillis() < deadline) {
            FakeServer.tick(1);
        }
        await(ready);
        assertEquals(9, store.row(uuid).keys());
        assertEquals(List.of("griddy"), store.row(uuid).unlockedIds());
    }

    @Test
    void anOfflineEditImportsFirstAndOnlyOnce() {
        imports(5, "sentry");
        UUID stranger = UUID.randomUUID();
        assertEquals(7, await(store.edit(stranger, row -> row.withKeys(row.keys() + 2))).keys());
        assertEquals(8, await(store.edit(stranger, row -> row.withKeys(row.keys() + 1))).keys());
        assertEquals(1, asked.get());
        assertEquals(List.of("sentry"), await(store.fetch(stranger)).unlockedIds());
    }

    @Test
    void anImportIsWrittenEvenWhenTheEditChangesNothing() {
        imports(5);
        UUID stranger = UUID.randomUUID();
        await(store.edit(stranger, row -> row));
        assertTrue(stored(stranger));
        assertEquals(5, await(store.fetch(stranger)).keys());
        assertEquals(1, asked.get());
    }

    @Test
    void withNothingToImportAnEditThatChangesNothingCreatesNothing() {
        UUID stranger = UUID.randomUUID();
        await(store.edit(stranger, row -> row));
        assertFalse(stored(stranger), "a row written now would cost the player their start keys");
    }

    @Test
    void aReadOfSomebodyNewImportsOnce() {
        imports(4);
        UUID stranger = UUID.randomUUID();
        assertEquals(4, await(store.fetch(stranger)).keys());
        assertEquals(4, await(store.fetch(stranger)).keys());
        assertEquals(1, asked.get());
    }

    // ------------------------------------------------------------------
    // Key items handed out before the move
    // ------------------------------------------------------------------

    @Test
    void aLegacyKeyItemIsAKey() {
        // Marking the old keys inert registers a listener, which needs a server.
        Plugin plugin = (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> method.getName().equals("getServer") ? org.bukkit.Bukkit.getServer()
                        : method.invoke(this.plugin, args));
        PluginCrates crates = new PluginCrates(plugin);
        ItemStack old = new Stack(Material.TRIPWIRE_HOOK);
        Items.of(plugin).values().set(old, "item", "key");
        ItemStack token = new Stack(Material.FIREWORK_STAR);
        Items.of(plugin).values().set(token, "item", "token");

        assertFalse(crates.isKey(old));
        crates.legacyKeys("item", "key");
        assertTrue(crates.isKey(old));
        assertFalse(crates.isKey(token), "any item the plugin tagged became a key");
        assertFalse(crates.isKey(new Stack(Material.TRIPWIRE_HOOK)));
        assertFalse(crates.isKey(null));
    }

    // ------------------------------------------------------------------
    // Faces
    // ------------------------------------------------------------------

    /** A reward is written {@code id:tier}. */
    private static class Catalogue implements CrateCatalogue<String> {
        @Nullable String prefix;
        final Map<String, ItemStack> items = new HashMap<>();
        final AtomicInteger iconsAsked = new AtomicInteger();

        @Override public @NotNull Collection<String> all() { return List.of("griddy:legendary"); }
        @Override public @NotNull String id(@NotNull String reward) { return reward.split(":")[0]; }
        @Override public @NotNull String tier(@NotNull String reward) { return reward.split(":")[1]; }
        @Override public @NotNull String name(@NotNull String reward) { return "{highlight}Griddy"; }
        @Override public @NotNull String icon(@NotNull String reward) { return "NOTE_BLOCK"; }
        @Override public @NotNull String description(@NotNull String reward) { return "A dance"; }
        @Override public ItemStack token(@NotNull String reward, @NotNull Player viewer) { return null; }
        @Override public @Nullable String placeholderPrefix() { return prefix; }

        @Override
        public @Nullable ItemStack icon(@NotNull String reward, @NotNull Player viewer) {
            iconsAsked.incrementAndGet();
            return items.get(id(reward));
        }
    }

    private UiEntry face(Catalogue catalogue) {
        return new CrateFaces<>(catalogue, new TierTable(CrateTier.defaults()), player,
                stack -> "bytes:" + stack.getType().name()).face("griddy:legendary").build();
    }

    @Test
    void aPrefixMakesBothSpellingsResolve() {
        Catalogue catalogue = new Catalogue();
        catalogue.prefix = " Effect ";
        UiEntry entry = face(catalogue);

        for (String prefix : List.of("reward", "effect")) {
            assertEquals("griddy", entry.values().get(prefix + "_id"));
            assertEquals("{highlight}Griddy", entry.values().get(prefix + "_name"));
            assertEquals("NOTE_BLOCK", entry.values().get(prefix + "_material"));
            assertEquals("A dance", entry.values().get(prefix + "_description"));
            assertEquals("legendary", entry.values().get(prefix + "_tier_id"));
            assertEquals("{highlight}Legendary", entry.values().get(prefix + "_tier"));
            assertEquals("{highlight}", entry.values().get(prefix + "_tier_color"));
            assertTrue(entry.formatted().contains(prefix + "_name"), "a coloured name reached the menu as text");
            assertFalse(entry.formatted().contains(prefix + "_id"));
        }
        assertEquals("griddy:legendary", entry.value(String.class).orElseThrow());
        assertEquals("effect", CrateFaces.alias(catalogue));
    }

    @Test
    void noPrefixOrAnUnusableOneAddsNothing() {
        Catalogue catalogue = new Catalogue();
        assertEquals(7, face(catalogue).values().size());
        for (String bad : List.of("", "  ", "reward", "my-effect", "%effect%")) {
            catalogue.prefix = bad;
            assertNull(CrateFaces.alias(catalogue), bad);
            assertEquals(7, face(catalogue).values().size(), bad);
        }
    }

    @Test
    void aRealItemReplacesTheMaterialAndIsEncodedOncePerOpening() {
        Catalogue catalogue = new Catalogue();
        assertEquals("NOTE_BLOCK", face(catalogue).values().get("reward_material"), "no item, the icon string");

        catalogue.items.put("griddy", new Stack(Material.WHITE_BANNER));
        catalogue.iconsAsked.set(0);
        CrateFaces<String> faces = new CrateFaces<>(catalogue, new TierTable(CrateTier.defaults()), player,
                stack -> "bytes:" + stack.getType().name());
        for (int frame = 0; frame < 5; frame++) {
            assertEquals("bytes:WHITE_BANNER", faces.face("griddy:legendary").build().values().get("reward_material"));
        }
        assertEquals(1, catalogue.iconsAsked.get(), "the item was built again for every frame");
    }

    // ------------------------------------------------------------------

    /**
     * An item with a working data container and nothing else: {@code new
     * ItemStack(...)} reaches for a real server.
     */
    private static final class Stack extends ItemStack {
        private final Material type;
        private final ItemMeta meta = meta();

        Stack(Material type) {
            this.type = type;
        }

        @Override public @NotNull Material getType() { return type; }
        @Override public ItemMeta getItemMeta() { return meta; }
        @Override public boolean hasItemMeta() { return true; }
        @Override public boolean setItemMeta(ItemMeta replacement) { return true; }
        @Override public @NotNull PersistentDataContainerView getPersistentDataContainer() {
            return meta.getPersistentDataContainer();
        }

        private static ItemMeta meta() {
            Map<NamespacedKey, Object[]> stored = new HashMap<>();
            PersistentDataContainer container = (PersistentDataContainer) Proxy.newProxyInstance(
                    Stack.class.getClassLoader(), new Class<?>[]{PersistentDataContainer.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> {
                            stored.put((NamespacedKey) args[0], new Object[]{args[1], args[2]});
                            yield null;
                        }
                        case "get" -> {
                            Object[] entry = stored.get((NamespacedKey) args[0]);
                            yield entry == null || entry[0] != args[1] ? null : entry[1];
                        }
                        case "has" -> args.length == 1 ? stored.containsKey((NamespacedKey) args[0])
                                : stored.containsKey((NamespacedKey) args[0])
                                && stored.get((NamespacedKey) args[0])[0] == args[1];
                        case "getKeys" -> java.util.Set.copyOf(stored.keySet());
                        case "isEmpty" -> stored.isEmpty();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> method.getReturnType() == boolean.class ? false : null;
                    });
            return (ItemMeta) Proxy.newProxyInstance(Stack.class.getClassLoader(), new Class<?>[]{ItemMeta.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getPersistentDataContainer" -> container;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> method.getReturnType() == boolean.class ? false : null;
                    });
        }
    }
}
