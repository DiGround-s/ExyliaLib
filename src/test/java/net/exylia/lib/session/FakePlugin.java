package net.exylia.lib.session;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/** A plugin that is nothing but a name, which is all {@link Sessions} reads. */
final class FakePlugin {

    private FakePlugin() {
    }

    static Plugin named(String name) {
        InvocationHandler handler = (proxy, method, args) ->
                method.getName().equals("getName") ? name : null;
        return (Plugin) Proxy.newProxyInstance(
                FakePlugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
    }
}
