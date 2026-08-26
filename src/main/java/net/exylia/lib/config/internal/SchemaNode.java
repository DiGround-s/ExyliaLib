package net.exylia.lib.config.internal;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * A record schema, analysed once and reused for every load.
 *
 * <p>Reflection is the expensive part of an annotation-driven config, so it all
 * happens here, at declaration time. After that a load is a walk over these
 * nodes, and a read by the consumer is a plain field access on a record.
 *
 * @param type       the record class this node describes
 * @param canonical  its canonical constructor, used to build instances
 * @param components its components, in constructor order
 * @param comments   documentation written above the section
 */
record SchemaNode(Class<?> type,
                  Constructor<?> canonical,
                  List<SchemaComponent> components,
                  List<String> comments) {

    /**
     * The value side of a {@code Map<String, V>} component.
     *
     * <p>A map is the one place where the owner chooses the keys, so the schema
     * can only describe what sits under each of them.
     *
     * @param type    the declared value type, erased
     * @param generic the declared value type with its arguments, so the element
     *                type of a {@code Map<String, List<String>>} is recoverable
     * @param nested  the analysed schema when the value is itself a record,
     *                otherwise {@code null}
     */
    record MapEntry(Class<?> type, java.lang.reflect.Type generic, SchemaNode nested) {
    }

    /**
     * One component of a record: a leaf value, a nested section or a map.
     *
     * @param name     the Java component name, used in error messages
     * @param key      the YAML key, from {@code @Key} or the kebab-case default
     * @param type     the declared type
     * @param generic  the generic type, needed to read the element type of lists
     * @param comments documentation written above the key
     * @param nested   the nested schema when this component is itself a record,
     *                 otherwise {@code null}
     * @param map      the value side when this component is a {@code Map},
     *                 otherwise {@code null}
     */
    record SchemaComponent(String name,
                           String key,
                           Class<?> type,
                           java.lang.reflect.Type generic,
                           List<String> comments,
                           SchemaNode nested,
                           MapEntry map) {

        /** Returns whether this component is a nested section rather than a value. */
        boolean isSection() {
            return nested != null;
        }

        /** Returns whether this component is a section whose keys the owner chooses. */
        boolean isMap() {
            return map != null;
        }
    }
}
