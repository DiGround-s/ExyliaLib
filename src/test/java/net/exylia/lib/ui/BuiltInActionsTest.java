package net.exylia.lib.ui;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The actions every menu already writes and no plugin should have to.
 *
 * <p>Five hundred menu files across the ecosystem write {@code next_page}, so
 * whether it exists at all is the thing worth checking. Opening a real window
 * needs a server; being registered, answering outside a menu, and yielding to a
 * plugin's own handler do not.
 */
class BuiltInActionsTest {

    private Plugin plugin;
    private PluginActions actions;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Actions.releaseAll();
        Menus.releaseAll();
        plugin = FakeServer.newPlugin("Practice", null);
    }

    @AfterEach
    void tearDown() {
        Menus.releaseAll();
        Actions.releaseAll();
        FakeServer.reset();
    }

    /** Asking a plugin for its menus is what registers them. */
    private void withMenus() {
        Menus.of(plugin, "practice");
        actions = Actions.of(plugin, "practice");
    }

    @Test
    @DisplayName("the page and window actions exist without a plugin writing them")
    void registered() {
        withMenus();

        for (String id : new String[] {"next_page", "previous_page", "back", "close", "refresh"}) {
            assertNotNull(actions.compile(id), id + " should be registered");
        }
    }

    @Test
    @DisplayName("asking twice does not fail on the second registration")
    void registeringTwiceIsFine() {
        Menus.of(plugin, "practice");

        // Two classes in one plugin both asking for menus is ordinary, and the
        // second must not blow up on an id the first already took.
        Menus.of(plugin, "practice");

        assertNotNull(Actions.of(plugin, "practice").compile("next_page"));
    }

    @Test
    @DisplayName("outside a menu they stop the sequence rather than failing it")
    void outsideAMenu() {
        withMenus();
        FakePlayer viewer = new FakePlayer("Steve");

        ActionResult result = actions.compile("next_page")
                .execute(ActionContext.forPlayer(viewer.player()).origin("test").build())
                .toCompletableFuture().join();

        // An item in a hand can carry the same string. Not being in a menu is
        // an ordinary state, not a broken configuration.
        assertEquals(ActionResult.Status.STOP, result.status());
    }

    @Test
    @DisplayName("a plugin's own action by the same name wins")
    void pluginHandlerWins() {
        // Registered before the menus ask, as a plugin's onEnable would.
        PluginActions own = Actions.of(plugin, "practice");
        ActionResult mine = ActionResult.denied("mine");
        own.registerSync("close", (context, arguments) -> mine);

        Menus.of(plugin, "practice");

        FakePlayer viewer = new FakePlayer("Steve");
        ActionResult result = own.compile("close")
                .execute(ActionContext.forPlayer(viewer.player()).origin("test").build())
                .toCompletableFuture().join();

        assertSame(mine, result, "silently replacing a plugin's handler would be worse"
                + " than not having the convenience");
    }

    @Test
    @DisplayName("releasing a plugin takes its menus with it")
    void releaseIsClean() {
        withMenus();
        assertEquals(1, Menus.registered());

        Menus.release(plugin.getName());

        assertEquals(0, Menus.registered());
    }
}
