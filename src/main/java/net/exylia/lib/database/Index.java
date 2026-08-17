package net.exylia.lib.database;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An index over one or more columns, declared on the record.
 *
 * <p>{@link Indexed} covers the common case of one column. This is for the case
 * that actually matters for a leaderboard, and there are forty of them in the
 * ecosystem today:
 *
 * <pre>{@code
 * @Table("practice_player_stats")
 * @Index(columns = {"kit_id", "elo"}, descending = {"elo"})
 * @Index(columns = {"kit_id", "wins"}, descending = {"wins"})
 * public record PlayerStats(
 *         @Id String id,
 *         @Column("kit_id") String kitId,
 *         @Column int elo,
 *         @Column int wins) { }
 * }</pre>
 *
 * <h2>Why the order of the columns is the whole point</h2>
 * A leaderboard asks for "the top ten of this kit by elo, highest first". An
 * index on {@code kit_id} alone narrows to that kit and then the database sorts
 * whatever is left. An index on {@code (kit_id, elo DESC)} is already in the
 * answer's order, so the database reads ten rows and stops — however many
 * players the kit has.
 *
 * <p>Two separate single-column indexes are not the same thing and do not help:
 * a database uses one of them, not both.
 *
 * @since 1.24.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Index.Container.class)
@Documented
public @interface Index {

    /**
     * The columns, in order.
     *
     * <p>Order matters: put the ones a query filters by first and the one it
     * sorts by last. Names may be either the column name or the record
     * component name.
     */
    String[] columns();

    /**
     * Which of those columns are sorted largest first.
     *
     * <p>Named rather than positional so a reader can see which column is
     * descending without counting. A leaderboard's sort column belongs here;
     * the columns it filters on do not.
     */
    String[] descending() default {};

    /**
     * Whether no two rows may share this combination.
     *
     * <p>A real constraint, enforced by the database. Use it when the
     * combination genuinely identifies a row — one row per player per kit — so
     * that a bug writing a second one fails instead of quietly doubling it.
     */
    boolean unique() default false;

    /**
     * The index name.
     *
     * <p>Defaults to one derived from the table and columns. Set it to match an
     * index that already exists in the database, so it is recognised rather than
     * created a second time under a different name.
     */
    String name() default "";

    /** Holds repeated {@link Index} annotations. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface Container {

        /** The indexes. */
        Index[] value();
    }
}
