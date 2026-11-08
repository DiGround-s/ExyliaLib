package net.exylia.lib.ui.internal;

import net.exylia.lib.ui.ClickBindings;
import net.exylia.lib.ui.ClickKind;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuScreensTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void cardsShowTheSubjectOfAName() {
        Component name = Component.text("Mine ", NamedTextColor.WHITE)
                .append(Component.text("▸ ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Reset", NamedTextColor.LIGHT_PURPLE));
        assertEquals("Reset", plain(MenuScreens.subject(name)));
        assertEquals("Next page", plain(MenuScreens.subject(Component.text("Next page"))));
        assertEquals("Job hub", plain(MenuScreens.subject(Component.text("Back ▸ Job hub"))));
    }

    @Test
    void sprintHeldNeverMakesAClickDead() {
        ClickBindings leftOnly = new ClickBindings.Builder().addCommand("left: player: spawn").build();
        // Toggle sprint holds sprint on every click: a button that binds no shift click takes the plain one.
        assertEquals(ClickKind.LEFT, MenuScreens.kindOf(false, true, leftOnly));
        assertEquals(ClickKind.RIGHT, MenuScreens.kindOf(true, false, leftOnly));
        ClickBindings shifted = new ClickBindings.Builder().addCommand("shift_left: player: spawn").build();
        assertEquals(ClickKind.SHIFT_LEFT, MenuScreens.kindOf(false, true, shifted));
        ClickBindings any = new ClickBindings.Builder().addCommand("player: spawn").build();
        assertEquals(ClickKind.SHIFT_LEFT, MenuScreens.kindOf(false, true, any));
    }
}
