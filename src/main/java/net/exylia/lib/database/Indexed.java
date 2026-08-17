package net.exylia.lib.database;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Asks the database to index a column.
 *
 * <p>Put it on anything a query filters, sorts or counts by. Without an index a
 * database answers by reading every row, which is invisible on a test server
 * with forty rows and is the whole cost on a live one with four hundred
 * thousand.
 *
 * <pre>{@code
 * @Table("practice_match_history")
 * public record Match(
 *         @Id long id,
 *         @Indexed @Column("winner_uuid") UUID winner,   // looked up per player
 *         @Indexed @Column("played_at") long playedAt,   // sorted, and purged by age
 *         @Column String kit) { }
 * }</pre>
 *
 * <p>Declaring it does not make a query correct, and leaving it off does not
 * make one fail — it makes it slow. {@code Repositories} reports a query that
 * filters or sorts on a column nobody indexed, because that is the mistake
 * nobody notices until the table is large.
 *
 * @since 1.24.0
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Indexed {
}
