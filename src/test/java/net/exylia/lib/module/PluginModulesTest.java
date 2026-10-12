package net.exylia.lib.module;

import net.exylia.lib.FakeServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginModulesTest {

    private final List<String> events = new ArrayList<>();
    private Plugin plugin;

    @BeforeAll
    static void installServer() {
        FakeServer.install();
    }

    @BeforeEach
    void setUp() {
        FakeServer.reset();
        plugin = FakeServer.newPlugin("ModulesPlugin");
        events.clear();
    }

    private PluginModules registry(Map<String, Boolean> switches) {
        return PluginModules.of(plugin, id -> Optional.ofNullable(switches.get(id)));
    }

    @Test
    void aModuleWhoseRequirementIsOffStaysOff() {
        PluginModules modules = registry(Map.of("sell", false, "sell-wands", true));
        modules.register(new Probe("sell"));
        modules.register(new Probe("sell-wands", "sell"));

        assertFalse(modules.isEnabled("sell-wands"));
        assertTrue(events.isEmpty(), "nothing may be built for a module that stays off: " + events);
    }

    @Test
    void aModuleWhoseRequirementRunsStarts() {
        PluginModules modules = registry(Map.of("sell", true, "sell-wands", true));
        modules.register(new Probe("sell"));
        modules.register(new Probe("sell-wands", "sell"));

        assertTrue(modules.isEnabled("sell-wands"));
        assertEquals(List.of("configs sell", "enable sell", "configs sell-wands", "enable sell-wands"), events);
    }

    @Test
    void aModuleThatFailsToEnableIsReleasedAndTheNextOneStillStarts() {
        PluginModules modules = registry(Map.of("broken", true, "fine", true));
        modules.register(new Probe("broken") {
            @Override
            protected void onEnable() {
                throw new IllegalStateException("boom");
            }
        });
        modules.register(new Probe("fine"));

        assertFalse(modules.isEnabled("broken"));
        assertTrue(modules.isEnabled("fine"));
        assertTrue(events.contains("disable broken"), "a failed enable must release what it built");
    }

    @Test
    void anIdWithNoSwitchStaysOffAndModulesDisableInReverse() {
        PluginModules modules = registry(Map.of("a", true, "b", true));
        modules.register(new Probe("a"));
        modules.register(new Probe("b"));
        modules.register(new Probe("unlisted"));
        assertFalse(modules.isEnabled("unlisted"));

        events.clear();
        modules.disableAll();
        assertEquals(List.of("disable b", "disable a"), events);
        assertFalse(modules.isEnabled("a"));
    }

    private class Probe extends PluginModule {
        private final List<String> requires;

        Probe(String id, String... requires) {
            super(id);
            this.requires = List.of(requires);
        }

        @Override
        protected void registerConfigs() {
            events.add("configs " + getId());
        }

        @Override
        protected void onEnable() {
            events.add("enable " + getId());
        }

        @Override
        protected void onDisable() {
            events.add("disable " + getId());
        }

        @Override
        protected List<String> requiredModules() {
            return requires;
        }
    }
}
