package net.exylia.lib.ui;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.action.Actions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redrawing an open menu from new values instead of opening it again.
 *
 * <p>Opening a real window needs a server, so the decision is checked against a
 * session that records what was asked of it: which menu counts as the open one,
 * and what gets written before the redraw.
 */
class PluginMenusUpdateTest {

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Actions.releaseAll();
        Menus.releaseAll();
    }

    @AfterEach
    void tearDown() {
        Menus.releaseAll();
        Actions.releaseAll();
        FakeServer.reset();
    }

    /** A session showing one menu that writes down every call it receives. */
    private static UiSession recording(String menuId, List<String> calls) {
        return (UiSession) Proxy.newProxyInstance(UiSession.class.getClassLoader(),
                new Class<?>[] {UiSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "menuId" -> menuId;
                    case "context" -> {
                        calls.add("context " + args[0] + "=" + args[1]);
                        yield proxy;
                    }
                    case "entries" -> {
                        calls.add("entries " + ((java.util.Collection<?>) args[0]).size());
                        yield null;
                    }
                    case "refresh" -> {
                        calls.add("refresh");
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    @DisplayName("the open menu takes the new values, then redraws once")
    void writesThenRedraws() {
        List<String> calls = new ArrayList<>();

        boolean updated = PluginMenus.update(recording("mines:levels", calls), "mines:levels",
                Map.of("mine_id", "spawn"), null);

        assertTrue(updated);
        assertEquals(List.of("context mine_id=spawn", "refresh"), calls,
                "with no rows the list is left alone");
    }

    @Test
    @DisplayName("new rows replace the list before the redraw")
    void replacesRows() {
        List<String> calls = new ArrayList<>();

        PluginMenus.update(recording("mines:levels", calls), "mines:levels",
                Map.of(), List.of(UiEntry.row().build(), UiEntry.row().build()));

        assertEquals(List.of("entries 2", "refresh"), calls);
    }

    @Test
    @DisplayName("a different menu is left untouched, even one whose id ends the same")
    void otherMenuUntouched() {
        List<String> calls = new ArrayList<>();

        boolean updated = PluginMenus.update(recording("mines:mine_levels", calls), "mines:levels",
                Map.of("mine_id", "spawn"), List.of());

        assertFalse(updated, "the caller has to open it instead");
        assertTrue(calls.isEmpty(), calls::toString);
    }

    @Test
    @DisplayName("a player with nothing open is the caller's cue to open it")
    void nothingOpen() {
        PluginMenus menus = Menus.of(FakeServer.newPlugin("Mines", null), "mines");

        assertFalse(menus.update(new FakePlayer("Steve").player(), "levels", Map.of("mine_id", "spawn")));
    }
}
