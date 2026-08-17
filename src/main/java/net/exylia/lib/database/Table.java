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
}
