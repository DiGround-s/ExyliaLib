package net.exylia.lib.packet.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.packet.ItemPlace;
import net.exylia.lib.packet.Packets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which lines go under an item, and where a slot of an inventory packet is drawn. */
class ItemDecorTest {

    private Plugin shop;
    private Plugin skins;
    private Player viewer;
    private UUID id;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Packets.releaseAll();
        shop = FakeServer.newPlugin("Shop");
        skins = FakeServer.newPlugin("Skins");
        FakePlayer alice = new FakePlayer("Alice");
        viewer = alice.player();
        id = viewer.getUniqueId();
    }

    @AfterEach
    void tearDown() {
        Packets.releaseAll();
    }

    @Test
    @DisplayName("every plugin's lines, in the order they registered, upright unless they say otherwise")
    void linesInOrder() {
        Packets.of(skins).itemLines().provider((v, item, place) -> List.of(Component.text("skin")));
        Packets.of(shop).itemLines().provider((v, item, place) ->
                List.of(Component.text("worth"), Component.text("slanted").decoration(TextDecoration.ITALIC, true)));

        List<Component> lines = ItemDecor.lines(viewer, new Stack(), ItemPlace.OWN);

        assertEquals(List.of("skin", "worth", "slanted"), lines.stream().map(ItemDecorTest::plain).toList());
        assertEquals(TextDecoration.State.FALSE, lines.get(0).decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.TRUE, lines.get(2).decoration(TextDecoration.ITALIC));
    }

    @Test
    @DisplayName("registering again moves a plugin to the end; releasing it drops its lines")
    void replaceAndRelease() {
        Packets.of(shop).itemLines().provider((v, item, place) -> List.of(Component.text("old")));
        Packets.of(skins).itemLines().provider((v, item, place) -> List.of(Component.text("skin")));
        Packets.of(shop).itemLines().provider((v, item, place) -> List.of(Component.text("new")));
        assertEquals(List.of("skin", "new"),
                ItemDecor.lines(viewer, new Stack(), ItemPlace.OWN).stream().map(ItemDecorTest::plain).toList());

        Packets.release("Skins");
        Packets.of(shop).itemLines().clearProvider();
        assertFalse(ItemDecor.decoratesAnything());
        assertTrue(ItemDecor.lines(viewer, new Stack(), ItemPlace.OWN).isEmpty());
    }

    @Test
    @DisplayName("a provider that throws writes nothing and the others still write theirs")
    void failureIsolated() {
        Packets.of(skins).itemLines().provider((v, item, place) -> {
            throw new IllegalStateException("broken");
        });
        Packets.of(shop).itemLines().provider((v, item, place) -> List.of(Component.text("worth")));

        assertEquals(List.of("worth"),
                ItemDecor.lines(viewer, new Stack(), ItemPlace.OWN).stream().map(ItemDecorTest::plain).toList());
    }

    @Test
    @DisplayName("each provider after the first gets its own copy of the item")
    void copies() {
        Stack original = new Stack();
        Object[] seen = new Object[2];
        Packets.of(skins).itemLines().provider((v, item, place) -> {
            seen[0] = item;
            return null;
        });
        Packets.of(shop).itemLines().provider((v, item, place) -> {
            seen[1] = item;
            return null;
        });
        ItemDecor.lines(viewer, original, ItemPlace.OWN);
        assertSame(original, seen[0]);
        assertFalse(original == seen[1]);
    }

    @Test
    @DisplayName("a window's own slots are the container's, the rest are the viewer's")
    void placeInWindow() {
        InventoryHolder chest = holder(BlockState.class);
        ItemDecor.opening(id, 27, chest);
        ItemDecor.opened(id, 3);

        ItemPlace top = ItemDecor.place(id, 3, 26);
        assertTrue(top.container());
        assertSame(chest, top.holder());
        assertFalse(top.isMenu());
        assertSame(ItemPlace.OWN, ItemDecor.place(id, 3, 27));
        assertSame(ItemPlace.OWN, ItemDecor.place(id, 0, 5));
        assertSame(ItemPlace.OWN, ItemDecor.place(id, -1, -1));
    }

    @Test
    @DisplayName("a retitle keeps the window; one opened without the server's event is a menu")
    void retitleAndUnknown() {
        InventoryHolder menu = holder(InventoryHolder.class);
        ItemDecor.opening(id, 54, menu);
        ItemDecor.opened(id, 4);
        ItemDecor.opened(id, 4);
        assertSame(menu, ItemDecor.place(id, 4, 0).holder());
        assertTrue(ItemDecor.place(id, 4, 0).isMenu());

        ItemDecor.opened(id, 5);
        ItemPlace unknown = ItemDecor.place(id, 5, 60);
        assertTrue(unknown.container());
        assertTrue(unknown.isMenu());

        ItemDecor.closed(id);
        assertTrue(ItemDecor.place(id, 5, 0).isMenu());
    }

    private static InventoryHolder holder(Class<?> type) {
        return (InventoryHolder) Proxy.newProxyInstance(ItemDecorTest.class.getClassLoader(),
                type == InventoryHolder.class ? new Class<?>[]{type} : new Class<?>[]{type, InventoryHolder.class}, (proxy, method, args) ->
                        method.getName().equals("equals") ? proxy == args[0] : null);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** An item with nothing behind it: {@code new ItemStack(...)} reaches for a real server. */
    private static final class Stack extends ItemStack {
        @Override
        public Material getType() {
            return Material.STONE;
        }

        @Override
        public ItemStack clone() {
            return new Stack();
        }
    }
}
