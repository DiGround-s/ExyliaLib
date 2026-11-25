package net.exylia.lib.database;

import net.exylia.lib.database.internal.SqlSettings;
import org.bukkit.plugin.Plugin;

/**
 * Opens an in-memory H2 database for a test outside this package.
 *
 * <p>The installer is package-private because its settings type is internal;
 * this is the one door tests elsewhere in the library go through.
 */
public final class TestDatabases {

    private TestDatabases() {
    }

    public static void memory(Plugin plugin, String name) {
        Databases.installForTests(plugin, SqlSettings.memory("h2", name));
    }

    /**
     * Points several plugins at one in-memory database, each with a pool and a
     * cache of its own: how a test stands up several servers on the same tables.
     */
    public static void shared(Plugin library, String database, String... pluginNames) {
        java.util.Map<String, SqlSettings> settings = new java.util.LinkedHashMap<>();
        for (String name : pluginNames) settings.put(name, SqlSettings.memory("h2", database));
        Databases.installForTests(library, settings);
    }

    /** The same on a networked engine, {@code mysql} or {@code mariadb}, for a test that asks for one. */
    public static void sharedRemote(Plugin library, String engine, String host, int port, String database,
                                    String user, String password, String... pluginNames) {
        java.util.Map<String, SqlSettings> settings = new java.util.LinkedHashMap<>();
        for (String name : pluginNames) settings.put(name, SqlSettings.remote(engine, host, port, database, user, password));
        Databases.installForTests(library, settings);
    }
}
