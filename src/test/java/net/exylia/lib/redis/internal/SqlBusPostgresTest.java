package net.exylia.lib.redis.internal;

import net.exylia.lib.database.internal.SqlSettings;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * {@link SqlBusTest} on a real PostgreSQL, where publishes also wake readers
 * through {@code NOTIFY}. Opt-in: {@code EXYLIA_BUS_POSTGRES=host:port/database:user:password}.
 */
@EnabledIfEnvironmentVariable(named = "EXYLIA_BUS_POSTGRES", matches = ".+")
class SqlBusPostgresTest extends SqlBusTest {

    private static final String[] TARGET = parse(System.getenv("EXYLIA_BUS_POSTGRES"));

    private static String[] parse(String value) {
        if (value == null) {
            return new String[0];
        }
        // host:port/database:user:password
        String[] hostRest = value.split(":", 2);
        String[] portRest = hostRest[1].split("/", 2);
        String[] databaseRest = portRest[1].split(":", 3);
        return new String[]{hostRest[0], portRest[0], databaseRest[0], databaseRest[1], databaseRest[2]};
    }

    @Override
    SqlSettings settings() {
        return SqlSettings.remote("postgresql", TARGET[0], Integer.parseInt(TARGET[1]), TARGET[2], TARGET[3],
                TARGET[4]);
    }

    @Override
    Connection raw() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://" + TARGET[0] + ':' + TARGET[1] + '/' + TARGET[2],
                TARGET[3], TARGET[4]);
    }

    @Override
    void reset() throws Exception {
        try (Connection connection = raw(); Statement statement = connection.createStatement()) {
            for (String table : new String[]{SqlBus.MESSAGES, SqlBus.KEYS, SqlBus.STREAMS, SqlBus.ENTRIES,
                    SqlBus.GROUPS, SqlBus.PENDING}) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }
}
