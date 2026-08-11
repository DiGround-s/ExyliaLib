package net.exylia.lib.config.internal;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analyses schema records, once per class.
 *
 * <p>Walking annotations is far more expensive than reading a field, so it is
 * done at declaration time and cached. A reload reuses the analysis; only the
 * file is read again.
 */
public final class SchemaCache {

    private static final Map<Class<?>, SchemaNode> NODES = new ConcurrentHashMap<>();
    private static final Map<String, List<Class<?>>> BY_PLUGIN = new ConcurrentHashMap<>();

    private SchemaCache() {
    }

    /**
     * Returns the analysed schema for a record type.
     *
     * @param type       the record class
     * @param pluginName the plugin that declared it, so it can be released later
     * @return the cached analysis
     */
    public static SchemaNode of(Class<?> type, String pluginName) {
        BY_PLUGIN.computeIfAbsent(pluginName, ignored -> new ArrayList<>()).add(type);
        return analyse(type);
    }

    private static SchemaNode analyse(Class<?> type) {
        SchemaNode cached = NODES.get(type);
        if (cached != null) {
            return cached;
        }
        // Not computeIfAbsent: nested records recurse into this method, and
        // ConcurrentHashMap forbids reentrant updates on the same map.
        SchemaNode built = build(type);
        NODES.putIfAbsent(type, built);
        return built;
    }

    private static SchemaNode build(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(
                    "Config schema " + type.getSimpleName() + " must be a record. "
                            + "Records give the config immutable values and a canonical constructor to build them from.");
        }

        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            parameterTypes[i] = components[i].getType();
        }

        Constructor<?> canonical;
        try {
            canonical = type.getDeclaredConstructor(parameterTypes);
            canonical.setAccessible(true);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(
                    "Could not find the canonical constructor of " + type.getName(), exception);
        }

        requireDefaultsConstructor(type);

        List<SchemaNode.SchemaComponent> analysed = new ArrayList<>(components.length);
        for (RecordComponent component : components) {
            analysed.add(analyseComponent(component));
        }

        return new SchemaNode(type, canonical, List.copyOf(analysed), commentsOf(type.getAnnotationsByType(Comment.class)));
    }

    private static SchemaNode.SchemaComponent analyseComponent(RecordComponent component) {
        Key key = component.getAnnotation(Key.class);
        String yamlKey = key != null ? key.value() : toKebabCase(component.getName());

        if (yamlKey.indexOf('.') >= 0) {
            throw new IllegalArgumentException(
                    "Key \"" + yamlKey + "\" on " + component.getDeclaringRecord().getSimpleName() + "."
                            + component.getName() + " must not contain a dot. "
                            + "Nesting comes from nested records, not from dotted keys.");
        }

        Class<?> type = component.getType();
        SchemaNode nested = type.isRecord() ? analyse(type) : null;

        return new SchemaNode.SchemaComponent(
                component.getName(),
                yamlKey,
                type,
                component.getGenericType(),
                commentsOf(component.getAnnotationsByType(Comment.class)),
                nested);
    }

    /**
     * Checks up front that the schema can produce its own defaults.
     *
     * <p>Failing here, at declaration time, points at the record with a fix. The
     * alternative is an obscure failure later, when a file is missing a key.
     */
    private static void requireDefaultsConstructor(Class<?> type) {
        try {
            Constructor<?> defaults = type.getDeclaredConstructor();
            defaults.setAccessible(true);
        } catch (NoSuchMethodException exception) {
            throw new IllegalArgumentException(
                    "Config schema " + type.getSimpleName() + " needs a no-argument constructor holding the defaults, "
                            + "for example:\n\n"
                            + "    public " + type.getSimpleName() + "() {\n"
                            + "        this(/* default values */);\n"
                            + "    }\n\n"
                            + "Those defaults are what gets written to the file the first time it is generated.");
        }
    }

    private static List<String> commentsOf(Comment[] comments) {
        if (comments.length == 0) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(comments.length);
        for (Comment comment : comments) {
            lines.add(comment.value());
        }
        return List.copyOf(lines);
    }

    /**
     * Converts a Java component name to the YAML convention, so
     * {@code poolSize} becomes {@code pool-size}.
     */
    static String toKebabCase(String name) {
        StringBuilder result = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (Character.isUpperCase(character)) {
                if (i > 0) {
                    result.append('-');
                }
                result.append(Character.toLowerCase(character));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    /** Drops the schemas declared by a plugin that is being disabled. */
    public static void release(String pluginName) {
        List<Class<?>> declared = BY_PLUGIN.remove(pluginName);
        if (declared != null) {
            declared.forEach(NODES::remove);
        }
    }

    /** Drops every cached schema. */
    public static void releaseAll() {
        NODES.clear();
        BY_PLUGIN.clear();
    }
}
