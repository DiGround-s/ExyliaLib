package net.exylia.lib.config;

import net.exylia.lib.config.internal.BundledResources;
import net.exylia.lib.config.internal.DefaultsMerge;
import net.exylia.lib.debug.Debug;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lays a language's {@code content/<catalogue>.yml} over a catalogue read from a server's file.
 *
 * <p>The behaviour behind {@link Languages#translate} and {@link Languages#translationProblems}.
 */
final class ContentOverlay {

    /** What a translation must keep: {@code %placeholders%} and {@code {tokens}}, icons included. */
    private static final Pattern TOKEN = Pattern.compile("%[A-Za-z0-9_]+%|\\{[^{}\\s]+}");

    private ContentOverlay() {
    }

    static void apply(Plugin plugin, Class<?> anchor, String code, String resource, ConfigurationSection yaml) {
        try {
            Path relative = BundledResources.relative(resource);
            ConfigurationSection table = scope(table(plugin, anchor, code, relative), relative);
            if (table == null) return;
            List<ConfigurationSection> shipped = shipped(plugin, anchor, relative);
            for (String key : table.getKeys(true)) {
                if (table.isConfigurationSection(key)) continue;
                Object current = at(yaml, key);
                Object replaced = current == null ? null : shippedValue(shipped, key, current);
                if (replaced == null) continue;   // Not there, or the owner's own text.
                String wrong = mismatch(replaced, table.get(key));
                if (wrong != null) {
                    Debug.of(plugin).warn("lang/" + code + "/" + name(relative) + " " + key + " is not used: " + wrong);
                } else {
                    put(yaml, key, table.get(key));
                }
            }
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            Debug.of(plugin).warn("Could not read the " + code + " translations of " + resource
                    + ", so its text stays as the server has it: " + failure.getMessage());
        }
    }

    static List<String> problems(Class<?> anchor, String code, String resource) {
        Path relative = BundledResources.relative(resource);
        List<String> problems = new ArrayList<>();
        try {
            ConfigurationSection table = scope(packaged(anchor, "lang/" + code + "/" + name(relative)), relative);
            if (table == null) return problems;
            ConfigurationSection shipped = packaged(anchor, slashed(relative));
            for (String key : table.getKeys(true)) {
                if (table.isConfigurationSection(key)) continue;
                Object english = shipped == null ? null : at(shipped, key);
                String wrong = english == null ? resource + " has no such text" : mismatch(english, table.get(key));
                if (wrong != null) problems.add(code + " " + name(relative) + " " + key + ": " + wrong);
            }
        } catch (IOException | InvalidConfigurationException failure) {
            problems.add(code + " " + name(relative) + ": not valid YAML (" + failure.getMessage() + ")");
        }
        return problems;
    }

    /** {@code content/jobs.yml} for {@code jobs/explorer.yml}, {@code content/kits.yml} for {@code kits.yml}. */
    private static String name(Path relative) {
        return "content/" + relative.getName(0).toString().replaceFirst("\\.ya?ml$", "") + ".yml";
    }

    /** The part of a table about one file: under {@code explorer} for {@code jobs/explorer.yml}, all of it for {@code kits.yml}. */
    private static @Nullable ConfigurationSection scope(@Nullable ConfigurationSection table, Path relative) {
        if (table == null || relative.getNameCount() == 1) return table;
        String inside = slashed(relative.subpath(1, relative.getNameCount())).replaceFirst("\\.ya?ml$", "");
        return table.getConfigurationSection(inside.replace('/', '.'));
    }

    /** The jar's table, with what a file of the same name in the data folder says on top, as phrases do. */
    private static @Nullable YamlConfiguration table(Plugin plugin, Class<?> anchor, String code, Path relative)
            throws IOException, InvalidConfigurationException {
        String path = "lang/" + code + "/" + name(relative);
        YamlConfiguration table = packaged(anchor, path);
        File own = new File(plugin.getDataFolder(), path);
        if (!own.isFile()) return table;
        if (table == null) table = new YamlConfiguration();
        YamlConfiguration override = YamlConfiguration.loadConfiguration(own);
        for (String key : override.getKeys(true)) {
            if (!override.isConfigurationSection(key)) table.set(key, override.get(key));
        }
        return table;
    }

    /**
     * What the plugin shipped for a file: the jar's copy, and what it shipped earlier if the file is kept
     * up to date, so a default reworded in a later version does not read as the owner's edit.
     */
    private static List<ConfigurationSection> shipped(Plugin plugin, Class<?> anchor, Path relative)
            throws IOException, InvalidConfigurationException {
        List<ConfigurationSection> shipped = new ArrayList<>(2);
        YamlConfiguration jar = packaged(anchor, slashed(relative));
        if (jar != null) shipped.add(jar);
        File reviewed = plugin.getDataFolder().toPath().resolve(BundledFiles.REVIEWED).resolve(relative).toFile();
        if (reviewed.isFile()) shipped.add(YamlConfiguration.loadConfiguration(reviewed));
        return shipped;
    }

    private static @Nullable Object shippedValue(List<ConfigurationSection> shipped, String key, Object current) {
        for (ConfigurationSection defaults : shipped) {
            Object value = at(defaults, key);
            if (value != null && DefaultsMerge.same(current, value)) return value;
        }
        return null;
    }

    private static @Nullable YamlConfiguration packaged(Class<?> anchor, String name)
            throws IOException, InvalidConfigurationException {
        try (InputStream in = BundledResources.open(anchor.getClassLoader(), name)) {
            if (in == null) return null;
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return yaml;
        }
    }

    /** Why a translation cannot stand in for this text, or {@code null} when it can. */
    private static @Nullable String mismatch(Object english, @Nullable Object translated) {
        if (translated == null || lines(translated).isBlank()) return "it is empty";
        if ((english instanceof List<?>) != (translated instanceof List<?>)) {
            return english instanceof List<?> ? "it must be a list of lines" : "it must be one line";
        }
        Set<String> expected = tokens(lines(english));
        Set<String> found = tokens(lines(translated));
        return expected.equals(found) ? null : "it must keep " + expected + " but has " + found;
    }

    private static String lines(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).reduce("", (a, b) -> a + "\n" + b)
                : String.valueOf(value);
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) tokens.add(matcher.group());
        return tokens;
    }

    /** The value at a dotted path, stepping through sections, maps and, by number, lists. */
    private static @Nullable Object at(Object root, String path) {
        Object here = root;
        for (String step : path.split("\\.")) {
            here = child(here, step);
            if (here == null) return null;
        }
        return here;
    }

    private static @Nullable Object child(Object parent, String step) {
        if (parent instanceof ConfigurationSection section) return section.get(step);
        if (parent instanceof Map<?, ?> map) {
            // Maps inside lists keep the keys YAML parsed: 10, not "10".
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (step.equals(String.valueOf(entry.getKey()))) return entry.getValue();
            }
            return null;
        }
        if (parent instanceof List<?> list && step.matches("\\d{1,9}")) {
            int index = Integer.parseInt(step);
            return index < list.size() ? list.get(index) : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void put(ConfigurationSection root, String path, Object value) {
        int cut = path.lastIndexOf('.');
        String step = path.substring(cut + 1);
        Object parent = cut < 0 ? root : at(root, path.substring(0, cut));
        if (parent instanceof ConfigurationSection section) {
            section.set(step, value);
        } else if (parent instanceof List<?> list) {
            ((List<Object>) list).set(Integer.parseInt(step), value);
        } else if (parent instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (step.equals(String.valueOf(entry.getKey()))) {
                    ((Map.Entry<Object, Object>) entry).setValue(value);
                    return;
                }
            }
        }
    }

    private static String slashed(Path relative) {
        return relative.toString().replace('\\', '/');
    }
}
