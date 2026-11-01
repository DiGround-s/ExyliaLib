package net.exylia.lib.ui;

import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickGuardTest {

    private static final long MS = 1_000_000L;

    @AfterEach
    void reset() {
        ClickGuard.resetClock();
        ClickGuard.cooldown(ClickGuard.DEFAULT_COOLDOWN);
    }

    @Test
    @DisplayName("a burst inside the cooldown is one click; the next deliberate one passes")
    void debounces() {
        AtomicLong now = new AtomicLong(1_000 * MS);
        ClickGuard.setClock(now::get);
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertTrue(ClickGuard.tryClick(player));
        now.addAndGet(120 * MS); // the release burst of a shift-double-click
        assertFalse(ClickGuard.tryClick(player));
        assertTrue(ClickGuard.tryClick(other), "per player");
        now.addAndGet(30 * MS); // 150 ms after the accepted one, not the refused one
        assertTrue(ClickGuard.tryClick(player));

        assertTrue(ClickGuard.tryClick(player, Duration.ZERO), "zero turns it off");
        ClickGuard.forget(player);
        assertTrue(ClickGuard.tryClick(player), "forgotten on quit");
    }

    @Test
    @DisplayName("a double-click is the press before it, never a second run")
    void doubleClickIsRefused() {
        AtomicLong now = new AtomicLong(1_000 * MS);
        ClickGuard.setClock(now::get);
        UUID player = UUID.randomUUID();

        assertTrue(ClickGuard.tryClick(click(player, ClickType.LEFT)));
        now.addAndGet(500 * MS); // past the cooldown: refused for what it is, not for timing
        assertFalse(ClickGuard.tryClick(click(player, ClickType.DOUBLE_CLICK)));
        assertTrue(ClickGuard.tryClick(click(player, ClickType.LEFT)));
    }

    /** A click whose view knows only who is looking. */
    private static InventoryClickEvent click(UUID player, ClickType type) {
        HumanEntity who = proxy(HumanEntity.class, player);
        InventoryView view = proxy(InventoryView.class, who);
        return new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, type, InventoryAction.PICKUP_ALL);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Object answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            Class<?> returns = method.getReturnType();
            if (returns.isInstance(answer)) return answer;
            if (returns == int.class) return 0;
            if (returns == boolean.class) return false;
            return null;
        });
    }
}
