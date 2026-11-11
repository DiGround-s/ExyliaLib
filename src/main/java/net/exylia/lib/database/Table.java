package net.exylia.lib.database;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the table or collection a record is stored in.
 *
 * <pre>{@code
 * @Table("practice_player_stats")
 * public record PlayerStats(
 *         @Id UUID uuid,
 *         @Column int elo,
 *         @Column("kill_streak") int killStreak) { }
 * }</pre>
 *
 * <p>The name is the one already in the database. It is written out rather than
 * derived from the class name because the tables exist: deriving it would rename
 * every table in the ecosystem the moment somebody renamed a class.
 *
 * @since 1.24.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Table {

    /** The table name, exactly as it exists in the database. */
    String value();

    /**
     * Whether rows go through the Redis row cache when the plugin has one.
     *
     * <p>{@code false} for tables of large rows that are written once and read
     * rarely, such as recordings or backups: cached, every write would be held
     * in this server's memory and copied to Redis for a read that may never
     * come, and every delete would be announced to the whole network. An
     * uncached table always reads the database, and
     * {@link PluginDatabase#onRemoteChange} hears nothing about it.
     *
     * @since 1.343.0
     */
    boolean cache() default true;

    /**
     * What the table holds, which decides what a library wipe or player reset
     * does to it.
     *
     * <p>{@link DataKind#UNSPECIFIED} by default: an unclassified table is
     * never touched by a preset ("new season") or a player reset, only by a
     * wipe that names it or its whole plugin. See {@code docs/wipes.md} for how
     * to pick a kind.
     *
     * @since 1.439.0
     */
    DataKind data() default DataKind.UNSPECIFIED;

    /**
     * Names the table had before, newest first.
     *
     * <p>When the table is created and one of these exists with every column
     * the record declares, its rows are copied in, so a rename (to stop two
     * plugins sharing {@code player_stats} on one database, say) keeps every
     * row. The old table is left as it was. A table of another plugin under
     * the old name lacks this record's columns and is never read. A scoped
     * repository looks for each name under its scope.
     *
     * @since 1.581.0
     */
    String[] previous() default {};
}
