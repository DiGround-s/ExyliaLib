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
}
