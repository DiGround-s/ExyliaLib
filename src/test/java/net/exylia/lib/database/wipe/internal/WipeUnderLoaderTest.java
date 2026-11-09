package net.exylia.lib.database.wipe.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Column;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Owner;
import net.exylia.lib.database.Table;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Table discovery for a plugin started by the licence loader, whose classes live only in memory. */
class WipeUnderLoaderTest {

    @Table(value = "loader_homes", data = DataKind.PROGRESS)
    record Home(@Id String id, @Owner @Column UUID player) {
    }

    @Table(value = "loader_warps", data = DataKind.SETUP)
    record Warp(@Id String id, @Column String world) {
    }

    record NotATable(String id) {
    }

    private LoaderInMemory payload;

    @BeforeEach
    void setUp() throws IOException {
        FakeServer.install();
        FakeServer.reset();
        LoaderInMemory.pendingLoadMap.clear();
        LoaderInMemory.resources.clear();
        payload = new LoaderInMemory();
        LoaderInMemory.resources.put("plugin.yml", "name: Premium\n".getBytes(StandardCharsets.UTF_8));
        payload.pend(Home.class);
        payload.pend(NotATable.class);
        payload.pend(Warp.class);
    }

    @Test
    void findsTheRecordsThePayloadHolds() throws Exception {
        // Warp was already loaded by the plugin; Home is still waiting as bytes.
        Class<?> warp = Class.forName(Warp.class.getName(), false, payload);
        assertNull(warp.getProtectionDomain().getCodeSource().getLocation());

        List<Class<?>> found = WipeRuntime.tableRecords(loader(payload));

        assertEquals(Set.of(Home.class.getName(), Warp.class.getName()),
                found.stream().map(Class::getName).collect(Collectors.toSet()), "got " + found);
        for (Class<?> type : found) assertSame(payload, type.getClassLoader());
    }

    /** The loader plugin: a jar plugin that hands out the class loader of its real classes. */
    private static Plugin loader(ClassLoader payload) {
        Plugin plugin = FakeServer.newPlugin("Premium");
        return (Plugin) Proxy.newProxyInstance(WipeUnderLoaderTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class, Bootstrap.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getClazzLoader")) return payload;
                    try {
                        return method.invoke(plugin, args);
                    } catch (InvocationTargetException thrown) {
                        throw thrown.getCause();
                    }
                });
    }

    /** What Lukittu's {@code ILukittuLoader} exposes. */
    public interface Bootstrap {
        ClassLoader getClazzLoader();
    }

    /**
     * The shape of Lukittu's class loader: class bytes wait in a static table until first asked for, defined
     * classes sit in a cache, files in a third table, and nothing has a location. Its parent cannot see the
     * plugin's classes.
     */
    private static final class LoaderInMemory extends SecureClassLoader {

        private static final Map<String, byte[]> pendingLoadMap = new ConcurrentHashMap<>();
        private static final Map<String, byte[]> resources = new ConcurrentHashMap<>();
        private final Map<String, Class<?>> cacheMap = new ConcurrentHashMap<>();

        LoaderInMemory() {
            super(WipeUnderLoaderTest.class.getClassLoader());
        }

        void pend(Class<?> type) throws IOException {
            String file = type.getName().replace('.', '/') + ".class";
            try (InputStream in = type.getClassLoader().getResourceAsStream(file)) {
                pendingLoadMap.put(type.getName(), in.readAllBytes());
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (cacheMap.containsKey(name) || pendingLoadMap.containsKey(name)) return findClass(name);
                return super.loadClass(name, resolve);
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            Class<?> cached = cacheMap.get(name);
            if (cached != null) return cached;
            byte[] pending = pendingLoadMap.remove(name);
            if (pending == null) throw new ClassNotFoundException(name);
            Class<?> defined = defineClass(name, pending, 0, pending.length);
            cacheMap.put(name, defined);
            return defined;
        }
    }
}
