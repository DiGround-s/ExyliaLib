package net.exylia.lib.ui.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.text.internal.Shadows;
import net.exylia.lib.text.internal.TextEngine;
import net.exylia.lib.ui.UiDefinition;
import net.exylia.lib.ui.UiSounds;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The title a window is opened with carries the server's shadow.
 *
 * <p>It used to be a legacy string, which cannot carry one: a menu opened
 * unshadowed and only looked right after the first page change, which is the
 * one moment the title is sent again as a component.
 */
class SessionTitleShadowTest {

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        TextEngine.shadow(Shadows.read("#112233"));
    }

    @AfterEach
    void tearDown() {
        TextEngine.shadow(null);
        TextEngine.invalidate();
        FakeServer.reset();
    }

    @Test
    @DisplayName("a window's opening title is shadowed")
    void openingTitleIsShadowed() {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString("""
                    title: '<#8a51c4>START EVENT'
                    size: 27
                    """);
        } catch (Exception invalid) {
            throw new IllegalStateException("test yaml is not valid", invalid);
        }
        UiDefinition menu = MenuLoader.load("test:menu", config, id -> null, UiSounds.DEFAULTS);

        Component title = Session.title(menu, null, Map.of());

        assertNotNull(title.shadowColor(),
                "a title created as a legacy string loses the server's shadow");
    }
}
