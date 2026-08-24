package net.exylia.lib.ui.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuListenerTest {

    @Test
    @DisplayName("a pre-cancelled spectator click still reaches its menu button")
    void handlesPreCancelledSpectatorClick() {
        assertTrue(MenuListener.handlesCancelledClick(true, true));
    }

    @Test
    @DisplayName("the click listener receives Bukkit's pre-cancelled spectator clicks")
    void clickListenerReceivesCancelledEvents() throws NoSuchMethodException {
        Method click = MenuListener.class.getMethod("onClick", InventoryClickEvent.class);
        EventHandler handler = click.getAnnotation(EventHandler.class);

        assertNotNull(handler);
        assertFalse(handler.ignoreCancelled());
    }

    @Test
    @DisplayName("a pre-cancelled non-spectator click remains vetoed")
    void respectsOtherCancelledClicks() {
        assertFalse(MenuListener.handlesCancelledClick(true, false));
    }
}
