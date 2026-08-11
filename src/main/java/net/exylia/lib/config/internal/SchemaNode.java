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
     * One component of a record: either a leaf value or a nested section.
     *
     * @param name     the Java component name, used in error messages
     * @param key      the YAML key, from {@code @Key} or the kebab-case default
     * @param type     the declared type
     * @param generic  the generic type, needed to read the element type of lists
     * @param comments documentation written above the key
     * @param nested   the nested schema when this component is itself a record,
     *                 otherwise {@code null}
     */
    record SchemaComponent(String name,
                           String key,
                           Class<?> type,
                           java.lang.reflect.Type generic,
                           List<String> comments,
                           SchemaNode nested) {

        /** Returns whether this component is a nested section rather than a value. */
        boolean isSection() {
            return nested != null;
        }
    }
}
