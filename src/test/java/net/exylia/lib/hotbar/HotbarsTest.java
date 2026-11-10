package net.exylia.lib.hotbar;

import net.exylia.lib.FakeServer;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.item.Item;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotbarsTest {

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @Test
    void aSetRefusesTwoItemsInOneSlotAndBadIds() {
        Item compass = Item.of("COMPASS").build();
        assertThrows(IllegalArgumentException.class, () -> HotbarSet.of("lobby", List.of(
                HotbarItem.of("a", 0, compass), HotbarItem.of("b", 0, compass))));
        assertThrows(IllegalArgumentException.class, () -> HotbarItem.of("has space", 0, compass));
        assertThrows(IllegalArgumentException.class, () -> HotbarItem.of("a", 0, compass).withCooldown(Duration.ofSeconds(-1)));
    }

    @Test
    void registeringCompilesTheLinesAndOneInstancePerPlugin() {
        FakeServer.reset();
        Plugin plugin = FakeServer.newPlugin("HotbarPlugin");
        PluginActions actions = Actions.of(plugin, "hotbartest");
        actions.registerSync("selector", (context, arguments) -> ActionResult.success());
        Hotbars hotbars = Hotbars.of(plugin, actions);
        assertSame(hotbars, Hotbars.of(plugin, actions));

        Item compass = Item.of("COMPASS").build();
        hotbars.register(HotbarSet.of("lobby", List.of(HotbarItem.of("selector", 0, compass)
                .withActions(List.of("right: hotbartest:selector")).withCooldown(Duration.ofMillis(500)))));
        assertTrue(hotbars.set("lobby").isPresent());
        assertEquals(1, hotbars.set("lobby").get().items().size());
        assertThrows(IllegalArgumentException.class, () -> hotbars.register(HotbarSet.of("broken", List.of(
                HotbarItem.of("x", 1, compass).withActions(List.of("right: hotbartest:missing"))))));
        Hotbars.release(plugin);
    }
}
