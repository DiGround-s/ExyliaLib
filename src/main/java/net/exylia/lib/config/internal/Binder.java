package net.exylia.lib.config.internal;

import net.exylia.lib.config.ConfigIssue;
import net.exylia.lib.config.Sparse;
import org.bukkit.configuration.ConfigurationSection;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                // A section that does nothing was left out of the file on
                // purpose. Reporting its keys missing would write the whole
                // empty block back on the next load, which is the noise this
                // exists to remove.
                if (child == null && fallback instanceof Sparse sparse && sparse.isEmpty()) {
                    arguments[i] = fallback;
                    continue;
                }
                arguments[i] = read(child, component.nested(), fallback, childPath, file, issues);
                continue;
            }

            if (component.isMap()) {
                arguments[i] = readMap(section, component, fallback, childPath, file, issues);
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

        pruneUnknownKeys(section, known, path, file, issues);

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

    /**
     * Reads a {@code Map} component: one entry per key the owner wrote.
     *
     * <p>Absent means "never configured", so the defaults are written back the
     * way a missing leaf is. Present and empty means the owner emptied it on
     * purpose, and an empty map is what they get — putting the examples back
     * would make the block impossible to clear.
     *
     * <p>Insertion order is kept so the file round-trips in the order it was
     * written, rather than being reshuffled on every save.
     */
    @SuppressWarnings("unchecked")
    private static Object readMap(ConfigurationSection section, SchemaNode.SchemaComponent component,
                                  Object fallback, String path, String file, List<ConfigIssue> issues) {
        if (section == null || !section.contains(component.key())) {
            issues.add(new ConfigIssue(ConfigIssue.Type.MISSING_KEY, path,
                    "was missing and has been added with its default entries", file));
            return fallback == null ? Map.of() : fallback;
        }

        ConfigurationSection entries = section.getConfigurationSection(component.key());
        if (entries == null) {
            issues.add(ConfigIssue.invalidValue(file, path, "a group of settings",
                    section.get(component.key()), "the defaults"));
            return fallback == null ? Map.of() : fallback;
        }

        SchemaNode.MapEntry value = component.map();
        Map<String, Object> read = new LinkedHashMap<>();
        Map<String, Object> defaults = fallback instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        for (String key : entries.getKeys(false)) {
            String childPath = path + "." + key;

            if (value.nested() != null) {
                ConfigurationSection child = entries.getConfigurationSection(key);
                if (child == null) {
                    issues.add(ConfigIssue.invalidValue(file, childPath, "a group of settings",
                            entries.get(key), "skipped"));
                    continue;
                }
                // An entry the owner added has no default of its own, so the
                // record's own defaults fill whatever it left out.
                Object entryDefaults = defaults.get(key);
                if (entryDefaults == null) {
                    entryDefaults = blank(value.nested());
                }
                read.put(key, read(child, value.nested(), entryDefaults, childPath, file, issues));
                continue;
            }

            Coercions.Result result = Coercions.coerce(entries.get(key), value.type(), value.generic());
            if (result.failed()) {
                issues.add(ConfigIssue.invalidValue(file, childPath, result.expected(),
                        entries.get(key), "skipped"));
                continue;
            }
            read.put(key, result.value());
        }

        return Collections.unmodifiableMap(read);
    }

    /**
     * A record built from its own no-argument constructor.
     *
     * <p>Used as the fallback for a map entry the owner invented: it has no
     * default anywhere else, and every key it omits still needs a value.
     */
    private static Object blank(SchemaNode schema) {
        try {
            var defaults = schema.type().getDeclaredConstructor();
            defaults.setAccessible(true);
            return defaults.newInstance();
        } catch (ReflectiveOperationException exception) {
            // SchemaCache proves this constructor exists before a file is read.
            throw new IllegalStateException(
                    "Could not build the defaults of " + schema.type().getSimpleName(), exception);
        }
    }

    /**
     * Removes keys the schema does not own.
     *
     * <p>A config outlives the code that wrote it: fields get renamed, features
     * get cut, and the old keys sit in the file forever, warning on every boot.
     * Commons deleted them outright; this does the same, reporting each removal
     * so the log says exactly what left and from where.
     *
     * <p>Only a key with no owner is touched, and only after migrations have
     * run, so a migration can still read the old layout before it goes.
     */
    private static void pruneUnknownKeys(ConfigurationSection section, Set<String> known,
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
            section.set(key, null);
            issues.add(new ConfigIssue(ConfigIssue.Type.UNKNOWN_KEY, childPath,
                    "is not a setting this plugin uses; it has been removed", file));
        }
    }

    /**
     * Writes a record into a configuration section, adding comments.
     *
     * <p>Only keys the schema owns are touched, so anything else a user put in
     * the file survives.
     */
    static void write(ConfigurationSection section, SchemaNode schema, Object values,
                      Object defaults, String path) {
        writeSection(section, schema, values, defaults, path);
    }

    /** Returns the comment lines that belong at the top of the file. */
    static List<String> header(SchemaNode schema) {
        return schema.comments();
    }

    private static void writeSection(ConfigurationSection section, SchemaNode schema, Object values,
                                     Object defaults, String path) {
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
            Object fallback = defaults == null ? null : valueOf(defaults, component, i);

            if (component.isSection()) {
                // Nothing to say, so nothing is written — and an empty block a
                // previous version wrote goes away, rather than sitting in the
                // file forever with a comment above every key it does not use.
                //
                // Only when the default is empty too. A section that ships with
                // something in it is read back as that default the moment it is
                // absent, so leaving it out is not "off", it is "reset": an
                // owner who cleared a title got it back on the next boot.
                if (value instanceof Sparse sparse && sparse.isEmpty()
                        && fallback instanceof Sparse blank && blank.isEmpty()) {
                    section.set(childPath, null);
                    continue;
                }
                if (!section.isConfigurationSection(childPath)) {
                    section.createSection(childPath);
                }
                writeSection(section, component.nested(), value, fallback, childPath);
            } else if (component.isMap()) {
                writeMap(section, component, value, childPath);
            } else {
                section.set(childPath, serialise(value));
            }

            if (!component.comments().isEmpty()) {
                // The first entry of a section already has the section's own
                // comment or the file header above it; another blank line there
                // just adds noise, and inside a section it would be indented.
                //
                // Blank lines only between the top-level groups, too. They are
                // what makes a long file scannable; inside a block of seven
                // keys they double its height and make it harder to read, not
                // easier.
                boolean separated = i > 0 && path.isEmpty();
                section.setComments(childPath,
                        separated ? spaced(component.comments()) : component.comments());
            }
        }
    }

    /**
     * Writes a {@code Map} component back out, one key per entry.
     *
     * <p>The block is replaced rather than merged: the snapshot is the whole
     * truth about what the map holds, so an entry removed in code has to leave
     * the file too. An empty map writes an empty block, which is what keeps the
     * key visible for an owner who wants to add entries back.
     */
    private static void writeMap(ConfigurationSection section, SchemaNode.SchemaComponent component,
                                 Object value, String path) {
        section.set(path, null);
        ConfigurationSection entries = section.createSection(path);
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }

        SchemaNode.MapEntry declared = component.map();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (declared.nested() != null) {
                entries.createSection(key);
                // An entry the owner named has no default of its own, so the
                // record's own defaults are what its sparse sections compare
                // against — the same fallback readMap gives it.
                writeSection(entries, declared.nested(), entry.getValue(),
                        blank(declared.nested()), key);
            } else {
                entries.set(key, serialise(entry.getValue()));
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
        if (value != null && value.getClass().isRecord()) {
            // A record component is a section and never reaches this method; a
            // record inside a list does, and handing it to Bukkit writes it as
            // a Java object under a global YAML tag. The file that produces
            // cannot be read back at all — the loader refuses global tags — so
            // the record is written as the block of keys it describes.
            return recordToMap(value);
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
     * A record as the block of keys it describes, keyed as the schema would.
     *
     * <p>Reflection rather than a {@link SchemaNode}: a record that only ever
     * appears inside a list is not a schema node, and analysing it would demand
     * the no-argument constructor that only a nested section needs.
     */
    private static Map<String, Object> recordToMap(Object record) {
        java.lang.reflect.RecordComponent[] components = record.getClass().getRecordComponents();
        Map<String, Object> written = new LinkedHashMap<>(components.length * 2);
        for (java.lang.reflect.RecordComponent component : components) {
            try {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                written.put(SchemaCache.keyOf(component), serialise(accessor.invoke(record)));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not read " + component.getName() + " of "
                        + record.getClass().getSimpleName(), exception);
            }
        }
        return written;
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
