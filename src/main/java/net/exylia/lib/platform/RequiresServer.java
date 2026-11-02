package net.exylia.lib.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that names server API newer than the oldest supported server.
 *
 * <p>Exylia plugins compile against a newer API than the oldest server they
 * run on, and switch newer features off at runtime. Code that names a newer
 * type, field or method lives in its own class carrying this annotation, and
 * that class is only loaded after a {@link Features} probe said yes. A class
 * that loads everywhere never names newer API, not in a field, a signature or
 * a lambda.
 *
 * <p>The annotation is what ExyliaLib's {@code tools/compat/check-compat.sh}
 * reads: annotated classes and their nested classes are skipped, and every
 * other reference to API the oldest server lacks is reported.
 *
 * <pre>{@code
 * @RequiresServer("1.21.4")
 * final class ModernModels {
 *     static void apply(ItemMeta meta, NamespacedKey key) {
 *         meta.setItemModel(key);
 *     }
 * }
 *
 * if (Features.itemModel()) ModernModels.apply(meta, key);
 * }</pre>
 *
 * @since 1.448.0
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface RequiresServer {

    /** The oldest Minecraft version that has everything the class names, such as {@code "1.21.4"}. */
    String value();
}
