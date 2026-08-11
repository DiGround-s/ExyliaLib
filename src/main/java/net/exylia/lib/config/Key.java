package net.exylia.lib.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the YAML key a record component maps to.
 *
 * <p>Without it, the key is derived from the component name in kebab-case, which
 * is what you want almost always:
 *
 * <pre>{@code
 * record Settings(int poolSize) { }   // -> pool-size
 * }</pre>
 *
 * Use this only when the file has to keep a name the convention would not
 * produce, typically to stay compatible with an existing file:
 *
 * <pre>{@code
 * record Settings(@Key("pool_size") int poolSize) { }   // -> pool_size
 * }</pre>
 *
 * <p>To rename a key that is already live in players' files, do not just change
 * this annotation: the old key would be treated as unknown and the new one as
 * missing. Add a {@link Migration#rename} step so existing files are carried
 * over.
 *
 * @since 1.1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Key {

    /**
     * The YAML key for this component, relative to its section.
     *
     * <p>Must not contain a dot: nesting comes from nested records, not from
     * dotted keys.
     *
     * @return the literal key to read and write
     */
    String value();
}
