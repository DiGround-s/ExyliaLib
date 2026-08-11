package net.exylia.lib.config.internal;

import net.exylia.lib.config.ConfigIssue;
import org.bukkit.configuration.ConfigurationSection;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Translates between a YAML tree and a schema record.
 *
 * <p>Reading never throws. A value that cannot be understood becomes a reported
 * issue and the default is used, because a server owner's typo should cost them
 * one warning line, not their whole plugin.
 */
final class Binder {

    private Binder() {
    }

    /**
     * Builds a record from a configuration section.
     *
     * @param section  the section holding this record's keys
     * @param schema   the analysed schema
     * @param defaults an instance holding the fallback for every component
     * @param path     dotted path of this section, for error messages
     * @param file     file name, for error messages
     * @param issues   collects anything wrong that is found
     * @return a fully populated record, never {@code null}
     */
    @SuppressWarnings("unchecked")
    static <T> T read(ConfigurationSection section, SchemaNode schema, T defaults,
                      String path, String file, List<ConfigIssue> issues) {
        List<SchemaNode.SchemaComponent> components = schema.components();
        Object[] arguments = new Object[components.size()];
        Set<String> known = new HashSet<>(components.size() * 2);

        for (int i = 0; i < components.size(); i++) {
            SchemaNode.SchemaComponent component = components.get(i);
            String childPath = path.isEmpty() ? component.key() : path + "." + component.key();
            Object fallback = valueOf(defaults, component, i);
            known.add(component.key());

            if (component.isSection()) {
                ConfigurationSection child = section == null ? null : section.getConfigurationSection(component.key());
                if (child == null && section != null && section.contains(component.key())) {
                    issues.add(ConfigIssue.invalidValue(file, childPath, "a group of settings",
                            section.get(component.key()), "the defaults"));
                }
                arguments[i] = read(child, component.nested(), fallback, childPath, file, issues);
                continue;
            }

            if (section == null || !section.contains(component.key())) {
                issues.add(new ConfigIssue(ConfigIssue.Type.MISSING_KEY, childPath,
                        "was missing and has been added with its default value", file));
                arguments[i] = fallback;
                continue;
            }

            Object raw = section.get(component.key());
            Coercions.Result result = Coercions.coerce(raw, component.type(), component.generic());
            if (result.failed()) {
                issues.add(ConfigIssue.invalidValue(file, childPath, result.expected(), raw, fallback));
                arguments[i] = fallback;
            } else {
                arguments[i] = result.value();
            }
        }

        reportUnknownKeys(section, known, path, file, issues);

        try {
            return (T) schema.canonical().newInstance(arguments);
        } catch (InstantiationException | IllegalAccessException exception) {
            throw new IllegalStateException("Could not build " + schema.type().getSimpleName(), exception);
        } catch (InvocationTargetException exception) {
            // A compact constructor rejected the values, typically a range check.
            throw new IllegalStateException(
                    "The constructor of " + schema.type().getSimpleName() + " rejected the values in "
                            + file + ".yml: " + exception.getCause().getMessage(), exception.getCause());
        }
    }

    private static void reportUnknownKeys(ConfigurationSection section, Set<String> known,
                                          String path, String file, List<ConfigIssue> issues) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            if (known.contains(key)) {
                continue;
            }
            // The version marker belongs to the library, not to the schema.
            if (path.isEmpty() && key.equals("config-version")) {
                continue;
            }
            String childPath = path.isEmpty() ? key : path + "." + key;
            issues.add(new ConfigIssue(ConfigIssue.Type.UNKNOWN_KEY, childPath,
                    "is not a setting this plugin uses; it was left untouched", file));
        }
    }

    /**
     * Writes a record into a configuration section, adding comments.
     *
     * <p>Only keys the schema owns are touched, so anything else a user put in
     * the file survives.
     */
    static void write(ConfigurationSection section, SchemaNode schema, Object values, String path) {
        writeSection(section, schema, values, path);
    }

    /** Returns the comment lines that belong at the top of the file. */
    static List<String> header(SchemaNode schema) {
        return schema.comments();
    }

    private static void writeSection(ConfigurationSection section, SchemaNode schema, Object values, String path) {
        // The root record's comments become the file header, which only
        // FileConfiguration can carry; ConfigFileImpl applies it.
        if (!path.isEmpty() && !schema.comments().isEmpty()) {
            section.setComments(path, spaced(schema.comments()));
        }

        List<SchemaNode.SchemaComponent> components = schema.components();
        for (int i = 0; i < components.size(); i++) {
            SchemaNode.SchemaComponent component = components.get(i);
            String childPath = path.isEmpty() ? component.key() : path + "." + component.key();
            Object value = valueOf(values, component, i);

            if (component.isSection()) {
                if (!section.isConfigurationSection(childPath)) {
                    section.createSection(childPath);
                }
                writeSection(section, component.nested(), value, childPath);
            } else {
                section.set(childPath, serialise(value));
            }

            if (!component.comments().isEmpty()) {
                // The first entry of a section already has the section's own
                // comment or the file header above it; another blank line there
                // just adds noise, and inside a section it would be indented.
                section.setComments(childPath, i == 0 ? component.comments() : spaced(component.comments()));
            }
        }
    }

    /**
     * Puts a blank line above a comment block.
     *
     * <p>A wall of keys with no spacing is hard to scan. Bukkit's writer renders
     * a {@code null} entry as a blank line and an empty string as a bare
     * {@code #}, so the separator has to be {@code null}.
     */
    private static List<String> spaced(List<String> comments) {
        List<String> result = new ArrayList<>(comments.size() + 1);
        result.add(null);
        result.addAll(comments);
        return result;
    }

    /** Converts values Bukkit's YAML writer would not render cleanly. */
    private static Object serialise(Object value) {
        if (value instanceof Enum<?> constant) {
            return constant.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
        if (value instanceof List<?> list) {
            List<Object> converted = new ArrayList<>(list.size());
            for (Object item : list) {
                converted.add(serialise(item));
            }
            return converted;
        }
        return value;
    }

    /**
     * Reads component {@code index} from a record instance.
     *
     * <p>Uses the accessor rather than the field so it works with records whose
     * accessors are overridden, and so it does not need field access at all.
     */
    private static Object valueOf(Object record, SchemaNode.SchemaComponent component, int index) {
        try {
            var accessor = record.getClass().getRecordComponents()[index].getAccessor();
            accessor.setAccessible(true);
            return accessor.invoke(record);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException(
                    "Could not read component " + component.name() + " of " + record.getClass().getSimpleName(),
                    exception);
        }
    }
}
