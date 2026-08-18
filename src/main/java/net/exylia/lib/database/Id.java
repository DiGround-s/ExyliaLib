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

    /**
     * Whether the database hands out the key instead of the caller.
     *
     * <p>Off by default, and that default is the right one for most tables: a
     * row keyed by a player's {@code UUID} already has an identity before it is
     * written, and asking the database to invent a second one would only add a
     * number nothing refers to.
     *
     * <p>Turn it on for a row that has no natural key — a design in a shared
     * library, an entry in an audit log — where the identity only exists once
     * the row does. The component must then be {@code long}, {@code Long},
     * {@code int} or {@code Integer}, and a record whose key is generated is
     * written with {@link net.exylia.lib.database.Repository#insert(Object)}
     * rather than {@code save}:
     *
     * <pre>{@code
     * @Table("shield_design_library")
     * public record Design(@Id(generated = true) long id,
     *                      @Column("owner_uuid") UUID owner,
     *                      @Column("design_json") String json) { }
     *
     * // The zero is a placeholder; the database picks the real one.
     * long id = designs.insert(new Design(0L, owner, json)).join();
     * }</pre>
     *
     * <p>The placeholder value is not stored. On insert the key column is left
     * out of the statement entirely, so the engine's own counter fills it —
     * which is also why two servers writing to one database cannot collide.
     *
     * @since 1.32.0
     */
    boolean generated() default false;
}
