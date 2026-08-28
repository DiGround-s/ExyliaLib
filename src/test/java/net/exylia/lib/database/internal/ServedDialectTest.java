package net.exylia.lib.database.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upsert follows the server, not the configured engine name.
 *
 * <p>The reported defect: a config saying {@code mysql} pointed at MariaDB.
 * Connector-j connects and every read works, so nothing looks wrong until the
 * first {@code saveAll}, which MariaDB cannot parse — {@code AS new} is MySQL
 * 8.0.20 syntax this fork never implemented.
 */
class ServedDialectTest {

    @Test
    void aMariadbBannerGetsMariadbSqlHoweverTheEngineWasWritten() {
        assertSame(MariaDBDialect.INSTANCE,
                SqlBackend.served(MySQLDialect.INSTANCE, "MySQL 11.4.2-MariaDB-ubu2404"));
    }

    @Test
    void aMysqlBannerGetsMysqlSqlHoweverTheEngineWasWritten() {
        assertSame(MySQLDialect.INSTANCE,
                SqlBackend.served(MariaDBDialect.INSTANCE, "MySQL 8.0.36"));
    }

    @Test
    void everyOtherEngineIsLeftAlone() {
        Dialect postgres = Dialect.of("postgres");
        // Not a fork of anything here, and the banner is not evidence about it.
        assertSame(postgres, SqlBackend.served(postgres, "PostgreSQL 16.2 mariadb"));
        assertSame(Dialect.of("h2"), SqlBackend.served(Dialect.of("h2"), "H2 2.2.224"));
    }

    /** The one statement the two engines disagree on, which is why any of this. */
    @Test
    void theTwoDialectsWriteTheUpsertDifferently() {
        EntityModel<?> model = EntityModel.of(Row.class);
        String mysql = MySQLDialect.INSTANCE.upsert(model, java.util.List.of("id"));
        String mariadb = MariaDBDialect.INSTANCE.upsert(model, java.util.List.of("id"));

        assertTrue(mysql.contains(" AS new ON DUPLICATE KEY UPDATE "), mysql);
        assertFalse(mariadb.contains(" AS new"), mariadb);
        assertTrue(mariadb.contains("`name` = VALUES(`name`)"), mariadb);
        assertEquals(mysql.replace(" AS new ON DUPLICATE KEY UPDATE `name` = new.`name`",
                " ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)"), mariadb);
    }

    @net.exylia.lib.database.Table("served_rows")
    record Row(@net.exylia.lib.database.Id String id,
               @net.exylia.lib.database.Column String name) {
    }
}
