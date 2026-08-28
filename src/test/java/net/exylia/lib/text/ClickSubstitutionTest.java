package net.exylia.lib.text;

import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.internal.Registry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The command behind a click gets the value, not the placeholder.
 *
 * <p>Adventure 5 replaced {@code ClickEvent.value()} with a payload and the
 * {@code clickEvent(action, String)} factory with a payload overload. The same
 * jar runs on servers with either, so this reads and rebuilds a click without
 * being compiled against one of them — and this test is what says the path
 * still works on the one it is compiled against.
 */
class ClickSubstitutionTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Registry.clear();
        plugin = FakeServer.newPlugin("ExyliaLib");
    }

    @Test
    @DisplayName("a value lands in the command a click runs")
    void substitutesIntoTheCommand() {
        Component built = Text.from(plugin, "<click:run_command:'/duel accept %player%'>Accept</click>")
                .with("%player%", "DiGround_")
                .build();

        assertEquals("/duel accept DiGround_", commandOf(built));
    }

    @Test
    @DisplayName("a click nothing substitutes into is left alone")
    void leavesAnUntouchedClickAlone() {
        Component built = Text.from(plugin, "<click:suggest_command:'/duel'>Duel</click>")
                .with("%player%", "DiGround_")
                .build();

        assertEquals("/duel", commandOf(built));
    }

    /** Reads the click of the first child that has one. */
    private static String commandOf(Component component) {
        ClickEvent click = component.clickEvent();
        if (click != null) {
            assertNotNull(click.action());
            return valueOf(click);
        }
        for (Component child : component.children()) {
            String found = commandOf(child);
            if (found != null) return found;
        }
        return null;
    }

    /** Whichever Adventure is on the test classpath. */
    private static String valueOf(ClickEvent click) {
        try {
            Object payload = ClickEvent.class.getMethod("payload").invoke(click);
            return (String) payload.getClass().getMethod("value").invoke(payload);
        } catch (NoSuchMethodException adventureFour) {
            try {
                return (String) ClickEvent.class.getMethod("value").invoke(click);
            } catch (ReflectiveOperationException unreadable) {
                throw new AssertionError(unreadable);
            }
        } catch (ReflectiveOperationException unreadable) {
            throw new AssertionError(unreadable);
        }
    }
}
