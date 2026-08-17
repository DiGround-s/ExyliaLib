package net.exylia.lib.database;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the record component that identifies a row.
 *
 * <p>Implies {@link Column}, so a component carrying this does not need both.
 * Exactly one component of a record must have it: a table whose rows cannot be
 * addressed individually cannot be updated or deleted, only appended to.
 *
 * <pre>{@code
 * @Table("practice_player_stats")
 * public record PlayerStats(@Id UUID uuid, @Column int elo) { }
 * }</pre>
 *
 * @since 1.24.0
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Id {

    /**
     * The column name.
     *
     * <p>Defaults to the component name.
     */
    String value() default "";

    /**
     * How many characters the key holds, when it is text.
     *
     * <p>A primary key is always bounded — a database cannot index an unbounded
     * text column — so this has no {@code UNBOUNDED} equivalent. The default
     * fits a UUID string with room to spare.
     */
    int length() default 64;
}
