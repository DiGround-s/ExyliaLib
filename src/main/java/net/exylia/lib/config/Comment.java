package net.exylia.lib.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documentation written above a key, or at the top of the file, when the YAML is
 * generated.
 *
 * <p>This is the only reason to open the generated file, so treat it as the
 * user-facing manual: explain what changing the value does, and give the unit
 * and the accepted range.
 *
 * <pre>{@code
 * @Comment("Database connection pool size.")
 * @Comment("Rule of thumb: cores x 2. More connections is not faster.")
 * record Settings(int poolSize) { }
 * }</pre>
 *
 * <p>Placed on a record type, the comment goes above that section; on a record
 * component, above that key. Repeating the annotation adds more lines, so each
 * line stays readable in source.
 *
 * <p>Comments are rewritten from the schema every time the file is saved, which
 * means fixing a typo here fixes it in every existing file on the next start.
 * The flip side: comments a user adds by hand to a key ExyliaLib owns are not
 * preserved.
 *
 * @since 1.1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.TYPE})
@java.lang.annotation.Repeatable(Comment.Comments.class)
public @interface Comment {

    /**
     * One line of documentation, written without the leading {@code #}.
     *
     * @return the comment line
     */
    String value();

    /**
     * Container for repeated {@link Comment} annotations. Never used directly.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.TYPE})
    @interface Comments {

        /**
         * The comment lines, in order.
         *
         * @return the repeated annotations
         */
        Comment[] value();
    }
}
