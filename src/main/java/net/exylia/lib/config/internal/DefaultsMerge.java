package net.exylia.lib.config.internal;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares a file an owner may edit with the defaults a plugin ships, key by key.
 *
 * <p>Three trees take part: what is on disk, the defaults the owner last
 * reviewed, and the defaults shipped now. A file cannot say whether a value
 * equal to its default was chosen or never looked at, so the two kinds of
 * change are kept apart:
 * <ul>
 *   <li><b>added</b> — a key the reviewed defaults never had. Nobody can have
 *       chosen something that did not exist, so it is written to disk at once;</li>
 *   <li><b>pending</b> — a key still at its reviewed default whose shipped
 *       default changed or disappeared. Only the owner knows whether they want
 *       it, so it waits for {@link #apply} or {@link #keep};</li>
 *   <li>anything else differs from its reviewed default, which makes it the
 *       owner's, and is never touched.</li>
 * </ul>
 *
 * <p>A key the owner deleted counts as theirs too, so a default they removed is
 * not added back. With nothing reviewed yet — a server that predates this — every
 * value already there is the owner's, and a key missing from disk is offered as
 * an added change rather than written: it may be one the owner deleted. The
 * offer is remembered under {@link #OFFERED} until somebody decides.
 */
public final class DefaultsMerge {

    /** A separator no key uses, so a key with a dot in it stays one key. */
    public static final char SEPARATOR = '\u0001';

    /** Where reviewed defaults remember keys offered to a file nobody had reviewed. */
    public static final String OFFERED = "pending-additions";

    /** Top-level keys that version or track a file rather than configure anything. */
    private static final Set<String> BOOKKEEPING =
            Set.of("config-version", "menu-version", "defaults-version", OFFERED);

    /** What kind of change a shipped default is. */
    public enum Kind {
        ADDED, CHANGED, REMOVED
    }

    /**
     * One difference between the reviewed defaults and the shipped ones.
     *
     * @param path    the keys leading to the value
     * @param kind    what happened to it
     * @param current the value on disk, {@code null} when absent
     * @param shipped the value shipped now, {@code null} when removed
     */
    public record Change(@NotNull List<String> path, @NotNull Kind kind,
                         @Nullable Object current, @Nullable Object shipped) {

        public Change {
            path = List.copyOf(path);
        }

        /** The path as an owner writes it. */
        public @NotNull String dotted() {
            return String.join(".", path);
        }
    }

    /**
     * The outcome of a merge.
     *
     * @param added    keys written to disk
     * @param pending  keys waiting for a decision
     * @param reviewed the reviewed defaults to keep from now on
     */
    public record Result(@NotNull List<Change> added, @NotNull List<Change> pending,
                         @NotNull YamlConfiguration reviewed) {
    }

    private DefaultsMerge() {
    }

    /** A YAML tree that keeps dotted keys whole and never folds long lines. */
    public static @NotNull YamlConfiguration yaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator(SEPARATOR);
        yaml.options().width(Integer.MAX_VALUE);
        return yaml;
    }

    /**
     * Adds what is new to {@code disk} and reports what waits for the owner.
     *
     * @param disk     the owner's file, changed in place when keys are added
     * @param reviewed the defaults last reviewed, or {@code null} when none were
     * @param shipped  the defaults shipped now
     * @return what was added, what is pending, and the reviewed defaults to store
     */
    public static @NotNull Result merge(@NotNull ConfigurationSection disk,
                                        @Nullable ConfigurationSection reviewed,
                                        @NotNull ConfigurationSection shipped) {
        List<Change> added = new ArrayList<>();
        List<Change> pending = new ArrayList<>();
        YamlConfiguration next = yaml();
        Set<String> offered = reviewed == null ? Set.of() : Set.copyOf(reviewed.getStringList(OFFERED));
        List<String> stillOffered = new ArrayList<>();
        walk(disk, reviewed, shipped, next, new ArrayList<>(), added, pending, offered, stillOffered);
        if (!stillOffered.isEmpty()) {
            next.set(OFFERED, stillOffered);
        }
        return new Result(List.copyOf(added), List.copyOf(pending), next);
    }

    /** Writes a pending change to disk and records it as reviewed. */
    public static void apply(@NotNull ConfigurationSection disk, @NotNull ConfigurationSection reviewed,
                             @NotNull Change change) {
        put(disk, joined(change.path()), change.shipped());
        keep(reviewed, change);
    }

    /** Records a pending change as reviewed, leaving the owner's value as it is. */
    public static void keep(@NotNull ConfigurationSection reviewed, @NotNull Change change) {
        put(reviewed, joined(change.path()), change.shipped());
        List<String> offered = new ArrayList<>(reviewed.getStringList(OFFERED));
        if (offered.remove(change.dotted())) {
            reviewed.set(OFFERED, offered.isEmpty() ? null : offered);
        }
    }

    /** Whether two YAML values hold the same thing, sections compared by content. */
    public static boolean same(@Nullable Object first, @Nullable Object second) {
        return Objects.equals(plain(first), plain(second));
    }

    private static void walk(@Nullable ConfigurationSection disk, @Nullable ConfigurationSection reviewed,
                             ConfigurationSection shipped, ConfigurationSection next, List<String> path,
                             List<Change> added, List<Change> pending,
                             Set<String> offered, List<String> stillOffered) {
        Set<String> keys = new LinkedHashSet<>(shipped.getKeys(false));
        if (reviewed != null) {
            keys.addAll(reviewed.getKeys(false));
        }

        for (String key : keys) {
            List<String> childPath = new ArrayList<>(path);
            childPath.add(key);
            String dotted = String.join(".", childPath);
            Object now = shipped.get(key);
            if (path.isEmpty() && BOOKKEEPING.contains(key)) {
                // Written by the library, not chosen by anyone: never offered.
                if (now != null) {
                    put(next, key, now);
                }
                continue;
            }
            Object before = reviewed == null ? null : reviewed.get(key);
            Object current = disk == null ? null : disk.get(key);
            boolean known = reviewed != null && reviewed.contains(key);

            if (now instanceof ConfigurationSection nowSection
                    && current instanceof ConfigurationSection currentSection
                    && (!known || before instanceof ConfigurationSection)) {
                walk(currentSection, known ? (ConfigurationSection) before : null, nowSection,
                        next.createSection(key), childPath, added, pending, offered, stillOffered);
                continue;
            }

            boolean absent = current == null && disk != null && !disk.contains(key);
            if (known && absent && offered.contains(dotted)) {
                if (now != null) {
                    pending.add(new Change(childPath, Kind.ADDED, null, plain(now)));
                    stillOffered.add(dotted);
                    put(next, key, now);
                }
                continue;
            }

            if (!known) {
                if (now == null) {
                    continue;
                }
                if (absent && reviewed == null) {
                    // Nothing says whether the owner deleted this or never had it,
                    // so it is offered rather than written.
                    pending.add(new Change(childPath, Kind.ADDED, null, plain(now)));
                    stillOffered.add(dotted);
                } else if (absent) {
                    put(disk, key, now);
                    copyComments(shipped, disk, key);
                    added.add(new Change(childPath, Kind.ADDED, null, plain(now)));
                }
                put(next, key, now);
                continue;
            }

            if (same(current, before)) {
                if (same(now, before)) {
                    put(next, key, before);
                } else {
                    pending.add(new Change(childPath, now == null ? Kind.REMOVED : Kind.CHANGED,
                            plain(current), plain(now)));
                    put(next, key, before);
                }
                continue;
            }

            // The owner's value. What ships now becomes the reviewed default,
            // so a value that later matches it is followed from then on.
            if (now != null) {
                put(next, key, now);
            }
        }
    }

    private static void copyComments(ConfigurationSection from, ConfigurationSection to, String key) {
        to.setComments(key, from.getComments(key));
        to.setInlineComments(key, from.getInlineComments(key));
        if (from.get(key) instanceof ConfigurationSection child && to.get(key) instanceof ConfigurationSection target) {
            for (String nested : child.getKeys(false)) {
                copyComments(child, target, nested);
            }
        }
    }

    /**
     * Sets a value, turning a map into a real section.
     *
     * <p>{@code set} stores a map as an opaque value, which reads back as no
     * section at all; only {@code createSection} builds the nested keys.
     */
    private static void put(ConfigurationSection section, String path, @Nullable Object value) {
        Object plain = plain(value);
        if (plain instanceof Map<?, ?> map) {
            section.set(path, null);
            section.createSection(path, map);
        } else {
            section.set(path, plain);
        }
    }

    private static String joined(List<String> path) {
        return String.join(String.valueOf(SEPARATOR), path);
    }

    /** A section as nested maps, so it can be compared and written anywhere. */
    private static @Nullable Object plain(@Nullable Object value) {
        if (value instanceof ConfigurationSection section) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                map.put(key, plain(section.get(key)));
            }
            return map;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), plain(entry)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(entry -> copy.add(plain(entry)));
            return copy;
        }
        return value;
    }
}
