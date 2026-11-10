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

    @Test
    void switchingOffLiveTakesDependantsAlongAndTheyComeBack() {
        java.util.Map<String, Boolean> config = new java.util.HashMap<>(Map.of("sell", true, "sell-wands", true, "other", true));
        PluginModules modules = PluginModules.of(plugin, id -> Optional.ofNullable(config.get(id)));
        Probe sell = new Probe("sell");
        modules.register(sell);
        modules.register(new Probe("sell-wands", "sell"));
        modules.register(new Probe("other"));
        events.clear();

        ModuleToggle off = modules.setEnabled("sell", false);
        assertEquals(ModuleToggle.Status.DISABLED, off.status());
        assertEquals(List.of("sell-wands", "sell"), off.changed());
        assertEquals(List.of("disable sell-wands", "disable sell"), events);
        assertTrue(modules.isEnabled("other"));
        assertFalse(sell.listening(), "a module switched off loses its listeners");
        config.put("sell", false);
        assertEquals(List.of(), modules.pendingRestart(), "a dependant kept off by its requirement is not pending");

        assertEquals(ModuleToggle.Status.BLOCKED, modules.setEnabled("sell-wands", true).status());
        assertEquals(List.of("sell"), modules.setEnabled("sell-wands", true).missing());

        events.clear();
        ModuleToggle on = modules.setEnabled("sell", true);
        assertEquals(ModuleToggle.Status.ENABLED, on.status());
        assertEquals(List.of("sell", "sell-wands"), on.changed());
        assertEquals(List.of("configs sell", "enable sell", "configs sell-wands", "enable sell-wands"), events);
        assertTrue(sell.listening());
        assertEquals(List.of("sell", "sell-wands"), modules.pendingRestart(), "the config still says off, so a restart stops both");

        assertEquals(ModuleToggle.Status.UNCHANGED, modules.setEnabled("sell", true).status());
        assertEquals(ModuleToggle.Status.UNKNOWN, modules.setEnabled("nope", true).status());
    }

    @Test
    void aDependantSwitchedOffByHandStaysOffWhenItsRequirementReturns() {
        PluginModules modules = registry(Map.of("sell", true, "sell-wands", true));
        modules.register(new Probe("sell"));
        modules.register(new Probe("sell-wands", "sell"));

        modules.setEnabled("sell-wands", false);
        modules.setEnabled("sell", false);
        assertEquals(List.of("sell"), modules.setEnabled("sell", true).changed());
        assertFalse(modules.isEnabled("sell-wands"));
    }

    @Test
    void commandsOfAModuleThatIsOffAreNamed() throws NoSuchMethodException {
        PluginModules modules = registry(Map.of("homes", false));
        modules.register(new Probe("homes") {
            @Override
            protected List<Object> commandHandlers() {
                return List.of(new HomesCommand());
            }
        });
        assertEquals(1, modules.allCommandHandlers().size());
        java.lang.reflect.Method home = HomesCommand.class.getDeclaredMethod("home");
        assertEquals(Optional.of("homes"), modules.offModuleOf(home));
        modules.setEnabled("homes", true);
        assertEquals(Optional.empty(), modules.offModuleOf(home));
    }

    static final class HomesCommand {
        void home() {
        }
    }

    private class Probe extends PluginModule implements org.bukkit.event.Listener {
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
            // FakeServer's registerEvents does not reach HandlerList, which is what the registry reads.
            org.bukkit.event.player.PlayerJoinEvent.getHandlerList().register(new org.bukkit.plugin.RegisteredListener(
                    this, (listener, event) -> { }, org.bukkit.event.EventPriority.NORMAL, plugin(), false));
        }

        boolean listening() {
            return org.bukkit.event.HandlerList.getRegisteredListeners(plugin()).stream()
                    .anyMatch(registered -> registered.getListener() == this);
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
