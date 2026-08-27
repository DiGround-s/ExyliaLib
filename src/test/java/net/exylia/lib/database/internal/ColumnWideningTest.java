package net.exylia.lib.database.internal;

import net.exylia.lib.database.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a live column is too narrow for what the record now writes.
 *
 * <p>The decision itself, away from a database. It has to say yes to the one
 * case it exists for — a column declared {@code VARCHAR(64)} back when an icon
 * was a material name, against a record that now stores a whole serialised item
 * — and no to everything else, because the only wrong answer that cannot be
 * taken back is narrowing a column somebody else's rows are in.
 */
class ColumnWideningTest {

    private static SqlSchema.Stored varchar(int size) {
        return new SqlSchema.Stored("icon", Types.VARCHAR, size);
    }

    @Test
    @DisplayName("a VARCHAR(64) against an unbounded column is widened")
    void widensBoundedIntoUnbounded() {
        assertTrue(SqlSchema.needsWidening(varchar(64), Column.UNBOUNDED));
    }

    @Test
    @DisplayName("a VARCHAR(64) against a wider declared width is widened")
    void widensBoundedIntoWider() {
        assertTrue(SqlSchema.needsWidening(varchar(64), 255));
    }

    @Test
    @DisplayName("a TEXT column is never narrowed into a declared VARCHAR(64)")
    void neverNarrowsUnboundedText() {
        // Three ways an engine reports its unbounded text, and none of them may
        // be touched: MySQL says LONGVARCHAR, Postgres reports a VARCHAR of the
        // whole int range, and H2 reports one of a billion characters.
        assertFalse(SqlSchema.needsWidening(new SqlSchema.Stored("icon", Types.LONGVARCHAR, 65535), 64));
        assertFalse(SqlSchema.needsWidening(new SqlSchema.Stored("icon", Types.CLOB, 2147483647), 64));
        assertFalse(SqlSchema.needsWidening(varchar(Integer.MAX_VALUE), 64));
        assertFalse(SqlSchema.needsWidening(varchar(1_000_000_000), Column.UNBOUNDED));
        assertFalse(SqlSchema.needsWidening(varchar(Integer.MAX_VALUE), Column.UNBOUNDED));
    }

    @Test
    @DisplayName("a column stored wider than the record declares is left alone")
    void neverNarrowsBoundedText() {
        // It may be another plugin's view of the same table, and shrinking it
        // truncates rows that are already there.
        assertFalse(SqlSchema.needsWidening(varchar(255), 64));
    }

    @Test
    @DisplayName("equal widths emit no statement")
    void equalWidthsDoNothing() {
        assertFalse(SqlSchema.needsWidening(varchar(64), 64));
    }

    @Test
    @DisplayName("a non-textual column is never retyped")
    void leavesNumbersAlone() {
        // Precision is not a width. A BIGINT reports a COLUMN_SIZE of 19 and
        // would otherwise read as a column 236 characters too narrow.
        assertFalse(SqlSchema.needsWidening(new SqlSchema.Stored("uses", Types.BIGINT, 19), 255));
        assertFalse(SqlSchema.needsWidening(new SqlSchema.Stored("balance", Types.DECIMAL, 38),
                Column.UNBOUNDED));
    }

    @Test
    @DisplayName("the declared width is read off the dialect's own type")
    void widthComesFromTheType() {
        // Off the type and not off the annotation, because the two disagree: a
        // UUID is VARCHAR(36) on every engine whatever length was asked for, so
        // reading the annotation would "widen" a correct column into a smaller
        // one.
        assertEquals(64, SqlSchema.widthOf("VARCHAR(64)"));
        assertEquals(36, SqlSchema.widthOf("VARCHAR(36)"));
        assertEquals(Column.UNBOUNDED, SqlSchema.widthOf("LONGTEXT"));
        assertEquals(Column.UNBOUNDED, SqlSchema.widthOf("TEXT"));
        // Not text, and not a width either.
        assertEquals(Column.UNBOUNDED, SqlSchema.widthOf("DECIMAL(38,10)"));
    }
}
