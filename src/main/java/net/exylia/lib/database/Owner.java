package net.exylia.lib.database;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the component holding the player a row belongs to.
 *
 * <p>What a player reset deletes by: every row whose owner is that player, in
 * every table that has one. The component is a {@code UUID} or its text form,
 * and it may be the {@link Id} itself. One per record. A table without it is
 * left alone by player resets, which is the right answer for rows that belong
 * to nobody (a warp, a kit) and the safe one for a table whose player is only
 * inside a composite key.
 *
 * <pre>{@code
 * @Table(value = "homes", data = DataKind.PROGRESS)
 * public record Home(@Id String id, @Owner @Indexed @Column UUID player, @Column String name) { }
 * }</pre>
 *
 * <p>Index the column: a player reset is one {@code DELETE ... WHERE owner = ?}
 * per table, and on a large table without an index that is a full scan.
 *
 * @since 1.439.0
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Owner {
}
