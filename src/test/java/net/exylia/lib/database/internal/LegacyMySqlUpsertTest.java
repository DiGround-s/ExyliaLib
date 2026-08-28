package net.exylia.lib.database.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upsert form for a MySQL server older than 8.0.20.
 *
 * <p>The row alias {@code INSERT ... AS new} is a parse error there, exactly as
 * it is on MariaDB, and the server that reports itself is what picks between
 * the two forms — not the engine name someone wrote in a config file.
 */
class LegacyMySqlUpsertTest {

    @Table("player_stats")
    record Stats(@Id String uuid, @Column int elo) {
    }

    @Test
    @DisplayName("a pre-8.0.20 server gets VALUES(col), never the row alias")
    void legacy() {
        EntityModel<Stats> model = EntityModel.of(Stats.class);
        String sql = MySQLDialect.LEGACY.upsert(model, List.of("uuid"));

        assertFalse(sql.contains(" AS new "), sql);
        assertTrue(sql.endsWith(" ON DUPLICATE KEY UPDATE `elo` = VALUES(`elo`)"), sql);
    }

    @Test
    @DisplayName("8.0.20 and up still gets the alias the deprecation asks for")
    void modern() {
        EntityModel<Stats> model = EntityModel.of(Stats.class);
        String sql = MySQLDialect.INSTANCE.upsert(model, List.of("uuid"));

        assertTrue(sql.endsWith(" AS new ON DUPLICATE KEY UPDATE `elo` = new.`elo`"), sql);
    }
}
