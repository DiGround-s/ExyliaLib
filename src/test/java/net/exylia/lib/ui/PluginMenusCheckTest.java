package net.exylia.lib.ui;

import net.exylia.lib.FakeServer;
import net.exylia.lib.action.Actions;
import net.exylia.lib.ui.internal.MenuRuntime;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A menu file is judged as load would judge it, and nothing is registered for asking. */
class PluginMenusCheckTest {

    private Plugin plugin;

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

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    private static final String SHOP = """
            title: Shop
            size: %d
            items:
              stone:
                slot: 13
                material: STONE
                actions:
                  - '%s'
            """;

    @Test
    void aCleanMenuHasNoProblemsAndIsNotRegistered() throws Exception {
        PluginMenus menus = Menus.of(plugin, "practice");
        assertEquals(List.of(), menus.check(yaml(SHOP.formatted(27, "close"))));
        assertTrue(menus.definitions().isEmpty(), "checking must not register anything");
    }

    @Test
    void aSizeThatIsNoRowCountAndAMissingActionAreBothProblems() throws Exception {
        PluginMenus menus = Menus.of(plugin, "practice");
        List<String> structural = menus.check(yaml(SHOP.formatted(7, "close")));
        assertEquals(1, structural.size(), structural::toString);

        List<String> action = menus.check(yaml(SHOP.formatted(27, "practice:no_such_action")));
        assertEquals(1, action.size(), action::toString);
        assertTrue(action.get(0).contains("no_such_action"), action::toString);
    }

    @Test
    void anExplicitNamespaceIsTheOneRemembered() {
        Menus.of(plugin, "practice");
        Menus.of(plugin);
        assertEquals("practice", MenuRuntime.namespaceOf("Practice"));
    }
}
