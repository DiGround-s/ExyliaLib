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
 * <h2>The layout is ExyliaCommons'</h2>
 * A block per engine under {@code database:}, with the same key names commons
 * wrote, so a server that already runs commons plugins keeps its file: the
 * credentials it has for MySQL stay where they are and keep working. Only the
 * keys ExyliaLib actually honours are declared; the ones commons wrote but this
 * library does not implement ({@code write-behind}, {@code cache},
 * {@code server-id}) are pruned on load and reported, because a setting that no
 * longer does what it says is worse than no setting at all. {@code redis} is
 * honoured; see {@link net.exylia.lib.redis.RedisSettings}.
 *
 * <h2>H2 is what happens when nobody decides</h2>
 * The defaults below are a file in the consumer plugin's folder: no daemon, no
 * credentials, no network, nothing to install. A server owner who wants MySQL
 * says so; a server owner who has never thought about it still gets durable
 * storage rather than a plugin that refuses to enable. Anything unrecognisable
 * in the file is reported and falls back to the same place, because a typo in
 * an engine name must not be the reason a server does not start.
 *
 * @param database the whole {@code database:} block
 * @since 1.24.0
 */
@Comment("This plugin's database.")
@Comment("")
@Comment("ExyliaLib opens and owns the client. Plugins with identical resolved")
@Comment("settings share that client; different settings use separate clients.")
@Comment("")
@Comment("Leave it alone and everything is stored in a file next to this one.")
@Comment("Nothing to install, nothing to back up but the file itself.")
public record DatabaseSettings(Database database) {

    /** The defaults: an embedded file, and nothing to configure. */
    public DatabaseSettings() {
        this(new Database());
    }

    /**
     * The {@code database:} block.
     *
     * @param type     the engine to use
     * @param settings tuning that applies whichever engine is chosen
     * @param h2       where the embedded engine keeps its file
     * @param mysql    a MySQL server
     * @param mariadb  a MariaDB server
     * @param postgresql a PostgreSQL server
     * @param mongodb  a MongoDB server
     * @param redis    the shared cache, off unless asked for
     */
    public record Database(

            @Comment("Which engine to use.")
            @Comment("One of: h2, mysql, mariadb, postgresql, mongodb.")
            @Comment("h2 is embedded and needs nothing installed; the rest need a")
            @Comment("server you already run. Only the block below matching this")
            @Comment("value is read. An unrecognised value falls back to h2 and")
            @Comment("says so in the console.")
            String type,

            Settings settings,
            H2 h2,
            Server mysql,
            Server mariadb,
            Server postgresql,
            Mongo mongodb,
            net.exylia.lib.redis.RedisSettings redis
    ) {

        /** The defaults: h2, no Redis, and every server block at its own default. */
        public Database() {
            this("h2", new Settings(), new H2(),
                    new Server(3306), new Server(3306), new Server(5432), new Mongo(),
                    new net.exylia.lib.redis.RedisSettings());
        }
    }

    /**
     * Tuning that applies to whichever engine is chosen.
     *
     * @param poolSize connections at most, or {@code 0} to let the engine decide
     */
    @Comment("Tuning that applies whichever engine is in use.")
    public record Settings(

            @Comment("Connections kept in the pool at most.")
            @Comment("0 lets the engine decide, which is the right answer almost always:")
            @Comment("an embedded database wants a handful and a networked one is sized")
            @Comment("from the cores this machine has. Raise it only if the console")
            @Comment("reports connection timeouts under load - a bigger pool against a")
            @Comment("database that is already the bottleneck makes things slower, not")
            @Comment("faster.")
            int poolSize
    ) {

        /** The defaults: let the engine size its own pool. */
        public Settings() {
            this(0);
        }
    }

    /**
     * The embedded engine, which is a file and nothing else.
     *
     * @param file       where the file lives, relative to the consumer plugin folder
     * @param autoServer whether a second process may open the same file
     */
    @Comment("The embedded engine. Used when type is h2.")
    public record H2(

            @Comment("Where the database file is stored, relative to this folder.")
            @Comment("The engine adds its own file extension.")
            String file,

            @Comment("Let more than one process open this file at once.")
            @Comment("")
            @Comment("An H2 file belongs to one JVM: the second one to open it")
            @Comment("is refused with \"The file is locked\". Turn this on and the")
            @Comment("first server to start also serves the file to the others,")
            @Comment("which is what makes two plugins on separate servers, or a")
            @Comment("server plus a database viewer, able to share it.")
            @Comment("")
            @Comment("Every process has to reach the first one over TCP, so this")
            @Comment("is for servers on one machine. For anything else, run a")
            @Comment("real database and set type to mysql or postgresql.")
            boolean autoServer
    ) {

        /** The defaults: a file under the plugin's own folder, opened by this server alone. */
        public H2() {
            this("database/h2", false);
        }
    }

    /**
     * A networked SQL engine.
     *
     * <p>One record for the three of them: MySQL, MariaDB and PostgreSQL differ
     * in dialect, not in what an operator has to type. Only the default port
     * changes, and that comes from the block it is declared in.
     *
     * @param host     the host name
     * @param port     the port, or {@code 0} for the engine's own default
     * @param database the schema or database name
     * @param username the user to connect as
     * @param password that user's password
     */
    @Comment("A networked SQL engine. Used when type names this block.")
    public record Server(

            @Comment("Host of the server.")
            String host,

            @Comment("Port of the server. 0 uses the engine's own default:")
            @Comment("3306 for mysql and mariadb, 5432 for postgresql.")
            int port,

            @Comment("Database or schema name.")
            String database,

            @Comment("User to connect as.")
            String username,

            @Comment("Password for that user.")
            String password
    ) {

        /** The defaults, for a block whose engine listens on the given port. */
        public Server(int defaultPort) {
            this("localhost", defaultPort, "minecraft", "root", "");
        }

        /** The defaults, for the config module, which cannot pass a port. */
        public Server() {
            this(0);
        }
    }

    /**
     * A MongoDB server.
     *
     * @param host             the host name
     * @param port             the port, or {@code 0} for {@code 27017}
     * @param database         the database name
     * @param username         the user to connect as
     * @param password         that user's password
     * @param connectionString a full URI, which wins over every field above
     */
    @Comment("A MongoDB server. Used when type is mongodb.")
    public record Mongo(

            @Comment("Host of the server.")
            String host,

            @Comment("Port of the server. 0 uses 27017.")
            int port,

            @Comment("Database name. Mongo has no default, so this one is used.")
            String database,

            @Comment("User to connect as. Leave empty for an unauthenticated server.")
            String username,

            @Comment("Password for that user.")
            String password,

            @Comment("A full mongodb:// or mongodb+srv:// URI.")
            @Comment("When set, everything above is ignored: paste here what a")
            @Comment("hosted provider like Atlas gives you and nothing else needs")
            @Comment("touching.")
            String connectionString
    ) {

        /** The defaults: a local server, no credentials, no URI. */
        public Mongo() {
            this("localhost", 27017, "minecraft", "", "", "");
        }
    }

    /** The engine name, trimmed and lower cased, as a dialect takes it. */
    public @NotNull String engine() {
        String type = database == null ? null : database.type();
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
     * <p>Only the block matching {@link Database#type()} is read. The pool size
     * is applied last and only when it was actually set, so a file left alone
     * gets the dialect's own answer — which differs between an embedded engine
     * that wants four connections and a networked one sized from the machine's
     * cores.
     *
     * @param dataFolder the consumer plugin folder, which an embedded file is relative to
     * @return the SQL settings
     */
    public @NotNull SqlSettings toSql(@NotNull Path dataFolder) {
        Database block = database == null ? new Database() : database;
        SqlSettings settings = mongo()
                ? mongoSettings(block.mongodb())
                : sqlSettings(block, dataFolder);

        int poolSize = block.settings() == null ? 0 : block.settings().poolSize();
        return poolSize > 0 ? settings.poolSize(poolSize) : settings;
    }

    private @NotNull SqlSettings sqlSettings(@NotNull Database block, @NotNull Path dataFolder) {
        if (embedded()) {
            H2 h2 = block.h2() == null ? new H2() : block.h2();
            String file = h2.file() == null || h2.file().isBlank() ? "database/h2" : h2.file();
            SqlSettings settings = SqlSettings.file("h2", dataFolder.resolve(file));
            // Carried as a URL parameter, which is the only place H2 accepts it:
            // the mode is decided when the connection is opened, not afterwards.
            return h2.autoServer() ? settings.property("AUTO_SERVER", "TRUE") : settings;
        }
        Server server = switch (engine()) {
            case "mariadb" -> block.mariadb();
            case "postgres", "postgresql", "pgsql" -> block.postgresql();
            default -> block.mysql();
        };
        Server values = server == null ? new Server() : server;
        return SqlSettings.remote(engine(), text(values.host(), "localhost"), values.port(),
                text(values.database(), "minecraft"), text(values.username(), "root"),
                text(values.password(), ""));
    }

    private @NotNull SqlSettings mongoSettings(Mongo block) {
        Mongo values = block == null ? new Mongo() : block;
        SqlSettings settings = SqlSettings.remote("mongodb", text(values.host(), "localhost"),
                values.port(), text(values.database(), "minecraft"),
                text(values.username(), ""), text(values.password(), ""));
        String uri = values.connectionString();
        // Carried as a property rather than in place of the host, because
        // MongoBackend looks for it there and a URI is what a hosted provider
        // hands over instead of the fields above.
        return uri == null || uri.isBlank() ? settings : settings.property("connection-string", uri);
    }

    private static @NotNull String text(String value, @NotNull String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
