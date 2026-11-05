package net.exylia.lib.database;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tables found in any case, rows keyed lower case, values read whatever the driver hands back. */
class ForeignDatabaseTest {

    @Test
    void readsTablesRowsAndQueries() throws SQLException {
        try (ForeignDatabase db = new ForeignDatabase(DriverManager.getConnection("jdbc:h2:mem:foreign"))) {
            db.connection().createStatement().execute("CREATE TABLE Jobs (Player VARCHAR(36), Lvl INT)");
            db.connection().createStatement().execute("INSERT INTO Jobs VALUES ('a', 3), ('b', 12)");
            String table = db.table("jobs");
            assertEquals("JOBS", table);
            assertEquals(List.of("player", "lvl"), db.tables().get(table));
            assertNull(db.table("missing"));
            assertTrue(db.rows(null).isEmpty());
            assertEquals(2, db.rows(table).size());
            List<Map<String, Object>> high = db.query("SELECT * FROM jobs WHERE lvl > ?", 10);
            assertEquals("b", ForeignDatabase.text(high.getFirst(), "player"));
            assertThrows(IllegalArgumentException.class, () -> db.rows("jobs; DROP TABLE jobs"));
        }
    }

    @Test
    void valuesReadWhateverTheDriverHandsBack() {
        Map<String, Object> row = new HashMap<>();
        row.put("n", 4.9);
        row.put("t", " 7.5 ");
        row.put("b", true);
        row.put("x", "nope");
        row.put("raw", "hi".getBytes(StandardCharsets.UTF_8));
        assertEquals(4, ForeignDatabase.number(row, "n", -1));
        assertEquals(7, ForeignDatabase.number(row, "t", -1));
        assertEquals(1, ForeignDatabase.number(row, "b", -1));
        assertEquals(-1, ForeignDatabase.number(row, "x", -1));
        assertEquals(-1, ForeignDatabase.number(row, "absent", -1));
        assertEquals(7.5, ForeignDatabase.decimal(row, "t", 0), 1e-9);
        assertEquals("hi", ForeignDatabase.text(row, "raw"));
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), ForeignDatabase.bytes(row, "raw"));
        assertNull(ForeignDatabase.bytes(row, "n"));
    }

    @Test
    void aMissingSqliteFileIsRefusedNotCreated() {
        assertThrows(SQLException.class, () -> ForeignDatabase.sqlite(Path.of("build", "no-such.db")));
    }
}
