package net.exylia.lib.economy;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionTest {

    private static Plugin named(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> method.getName().equals("getName") ? name : null);
    }

    @Test
    @DisplayName("a plugin's reason drops the Exylia prefix, any other keeps its name")
    void namedAfterPlugin() {
        assertEquals("rewards:reward", Transaction.of(named("ExyliaRewards"), "reward").reason());
        assertEquals("exylia:reward", Transaction.of(named("Exylia"), "reward").reason());
        assertEquals("jobs:reward", Transaction.of(named("Jobs"), "reward").reason());
    }
}
