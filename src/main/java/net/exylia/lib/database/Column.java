package net.exylia.lib.database;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record component as a stored column.
 *
 * <p>A component without this annotation is not stored, which is what lets a
 * record carry something derived without it turning into a column nobody meant
 * to create.
 *
 * <pre>{@code
 * @Table("practice_kits")
 * public record Kit(
 *         @Id String id,
 *         @Column("display_name") String displayName,
 *         @Column(length = Column.UNBOUNDED) String description,
 *         @Column ItemStack[] contents) { }
 * }</pre>
 *
 * @since 1.24.0
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Column {

    /**
     * Asks for the widest text type the database offers.
     *
     * <p>Used by anything whose size is not knowable in advance: a serialised
     * inventory, a JSON payload, a description somebody will paste a wall of
     * text into.
     */
    int UNBOUNDED = -1;

    /**
     * The column name.
     *
     * <p>Defaults to the component name. Set it when the existing column is
     * named differently — the ecosystem's tables use both {@code displayName}
     * and {@code display_name}, and both must keep working.
     */
    String value() default "";

    /**
     * How many characters a text column holds.
     *
     * <p>Only meaningful for strings. {@link #UNBOUNDED} asks for the dialect's
     * largest text type; anything else becomes a bounded column, which is what
     * lets a database index it.
     */
    int length() default 255;

    /** Whether the column accepts null. */
    boolean nullable() default true;

    /** Whether the database enforces that no two rows share this value. */
    boolean unique() default false;
}
