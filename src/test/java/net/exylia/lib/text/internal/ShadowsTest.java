package net.exylia.lib.text.internal;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a shadow can be drawn is a question for the server's own component,
 * not for whichever {@code ShadowColor} class happens to be resolvable.
 */
class ShadowsTest {

    /** A component from an Adventure older than shadows: no {@code shadowColor()} on it. */
    interface OldComponent {
        String content();
    }

    @Test
    @DisplayName("a component that can carry a shadow is supported")
    void modernComponent() {
        assertTrue(Shadows.knowsShadows(Component.class));
    }

    @Test
    @DisplayName("a component without shadowColor() is not, even with ShadowColor on the class path")
    void componentPredatingShadows() {
        assertFalse(Shadows.knowsShadows(OldComponent.class));
    }
}
