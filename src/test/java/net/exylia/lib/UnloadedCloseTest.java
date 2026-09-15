package net.exylia.lib;

import net.exylia.lib.input.internal.InputListener;
import net.exylia.lib.ui.internal.MenuListener;
import net.exylia.lib.util.editor.internal.EditorListener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * A chunk unloading closes the block windows inside it from inside the unload.
 *
 * <p>Asking such a window for its holder reads the block, which loads the very
 * chunk being unloaded, and Paper throws "Cannot update ticket level while
 * unloading chunks". None of ExyliaLib's windows is a block, so no close
 * listener has anything to look up.
 */
class UnloadedCloseTest {

    @Test
    @DisplayName("an unload close never reads the window's holder")
    void unloadCloseSkipsHolderLookup() {
        InventoryCloseEvent event = new InventoryCloseEvent(chestUnloading(), InventoryCloseEvent.Reason.UNLOADED);

        assertDoesNotThrow(() -> new EditorListener().onClose(event));
        assertDoesNotThrow(() -> new InputListener().onClose(event));
        assertDoesNotThrow(() -> new MenuListener().onClose(event));
    }

    /** A view whose top window fails the way a block in an unloading chunk does. */
    private static InventoryView chestUnloading() {
        Inventory chest = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[] {Inventory.class}, (proxy, method, args) -> {
                    throw new IllegalStateException(
                            "Cannot update ticket level while unloading chunks or updating entity manager");
                });
        return (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
                new Class<?>[] {InventoryView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getTopInventory" -> chest;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
