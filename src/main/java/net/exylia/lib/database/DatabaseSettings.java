package net.exylia.lib.database;

import net.exylia.lib.config.Comment;
import net.exylia.lib.database.internal.SqlSettings;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Locale;

/**
 * One consumer plugin's database settings, as its {@code database.yml}
 * describes them.
 *
 * <p>Each plugin owns its configuration and, by default, its embedded H2 file.
 * Plugins that resolve to the same datasource share one client; differing
 * settings stay isolated.
 *
 * <h2>H2 is what happens when nobody decides</h2>
 * The defaults below are a file in the consumer plugin's folder: no daemon, no credentials,
 * no network, nothing to install. A server owner who wants MySQL says so; a
 * server owner who has never thought about it still gets durable storage rather
 * than a plugin that refuses to enable. Anything unrecognisable in the file is
 * reported and falls back to the same place, because a typo in an engine name
 * must not be the reason a server does not start.
 *
 * @param type     the engine
 * @param file     where an embedded database lives, relative to the consumer plugin folder
 * @param host     the host of a networked engine
 * @param port     the port, or {@code 0} for the engine's own default
 * @param database the schema or database name
 * @param user     the user
 * @param password the password
 * @param poolSize connections at most, or {@code 0} to let the engine decide
 * @since 1.24.0
 */
@Comment("This plugin's database.")
@Comment("")
@Comment("ExyliaLib opens and owns the client. Plugins with identical resolved")
@Comment("settings share that client; different settings use separate clients.")
@Comment("")
@Comment("Leave it alone and everything is stored in a file next to this one.")
@Comment("Nothing to install, nothing to back up but the file itself.")
public record DatabaseSettings(

        @Comment("Which engine to use.")
        @Comment("One of: h2, mysql, mariadb, postgresql, mongodb.")
        @Comment("h2 is embedded and needs nothing installed; the rest need a")
        @Comment("server you already run. An unrecognised value falls back to h2")
        @Comment("and says so in the console.")
        String type,

        @Comment("Where an embedded (h2) database is stored, relative to this folder.")
        @Comment("The engine adds its own file extension. Ignored by every other type.")
        String file,

        @Comment("Host of a networked engine. Ignored by h2.")
        String host,

        @Comment("Port of a networked engine.")
        @Comment("0 uses the engine's own default: 3306 for mysql and mariadb,")
        @Comment("5432 for postgresql, 27017 for mongodb.")
        int port,

        @Comment("Database or schema name on a networked engine.")
        String database,

        @Comment("User to connect as. Ignored by h2.")
        String user,

        @Comment("Password for that user. Ignored by h2.")
        String password,

        @Comment("Connections kept in the pool at most.")
        @Comment("0 lets the engine decide, which is the right answer almost always:")
        @Comment("an embedded database wants a handful and a networked one is sized")
        @Comment("from the cores this machine has. Raise it only if the console")
        @Comment("reports connection timeouts under load - a bigger pool against a")
        @Comment("database that is already the bottleneck makes things slower, not")
        @Comment("faster.")
        int poolSize
) {

    /** The defaults: an embedded file, and nothing to configure. */
    public DatabaseSettings() {
        this("h2", "database/h2", "127.0.0.1", 0, "exylia", "exylia", "", 0);
    }

    /** The engine name, trimmed and lower cased, as a dialect takes it. */
    public @NotNull String engine() {
        return type == null ? "h2" : type.toLowerCase(Locale.ROOT).trim();
    }

    /** Whether this asks for MongoDB rather than one of the four SQL engines. */
    public boolean mongo() {
        String engine = engine();
        return engine.equals("mongo") || engine.equals("mongodb");
    }

    /**
     * Whether this asks for an engine that lives in this process rather than
     * over a socket.
     *
     * @return whether the engine is embedded
     */
    public boolean embedded() {
        return engine().equals("h2");
    }

    /**
     * These settings as the SQL layer takes them.
     *
     * <p>The pool size is applied last and only when it was actually set, so a
     * file left alone gets the dialect's own answer — which differs between an
     * embedded engine that wants four connections and a networked one sized
     * from the machine's cores.
     *
     * @param dataFolder the consumer plugin folder, which an embedded file is relative to
     * @return the SQL settings
     */
    public @NotNull SqlSettings toSql(@NotNull Path dataFolder) {
        SqlSettings settings = embedded()
                ? SqlSettings.file(engine(), dataFolder.resolve(file == null || file.isBlank()
                        ? "database/h2" : file))
                : SqlSettings.remote(engine(), host, port, database, user, password);
        return poolSize > 0 ? settings.poolSize(poolSize) : settings;
    }
}
