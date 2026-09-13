package net.exylia.lib.config.internal;

import net.exylia.lib.config.internal.DefaultsMerge.Change;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.reload.Reloads;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Default changes waiting for a server owner's decision, across every plugin.
 *
 * <p>Filled by config files and bundled files as they load, and emptied by
 * {@code /exylialib updates}. Nothing is kept here that the files do not
 * already say: what is pending is recomputed from them on every load, and a
 * decision is written straight into them, so a restart can neither lose nor
 * repeat one.
 */
public final class DefaultUpdates {

    /** Who may review changes and is told about them on join. */
    public static final String PERMISSION = "exylialib.updates";

    /**
     * One change as the command shows it.
     *
     * @param id     what the command is given to decide on it
     * @param plugin the owning plugin's name
     * @param file   the file, relative to that plugin's data folder
     * @param change what changed
     */
    public record Pending(int id, @NotNull String plugin, @NotNull String file, @NotNull Change change) {
    }

    /**
     * What a decision did.
     *
     * @param applied  changes written to their file
     * @param kept     changes marked reviewed with the owner's value left alone
     * @param stale    changes that were no longer pending when decided
     * @param reloaded plugins reloaded so an applied change is live
     * @param manual   plugins with an applied change and no reload to run
     */
    public record Decision(int applied, int kept, int stale,
                           @NotNull List<String> reloaded, @NotNull List<String> manual) {
    }

    private record Tracked(Plugin plugin, String file, Path disk, Path reviewed, String shipped,
                           @Nullable Runnable reloadFile, List<Pending> pending) {
    }

    private static final Map<String, Tracked> TRACKED = new ConcurrentHashMap<>();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Set<UUID> NOTIFIED = ConcurrentHashMap.newKeySet();

    private DefaultUpdates() {
    }

    /**
     * Replaces what one file has pending.
     *
     * @param plugin     the owning plugin
     * @param file       the file, relative to its data folder
     * @param disk       where the file is
     * @param reviewed   where its reviewed defaults are kept
     * @param shipped    the defaults shipped now, as YAML
     * @param reloadFile what reloads just this file, or {@code null}
     * @param changes    what is pending for it
     */
    public static void track(@NotNull Plugin plugin, @NotNull String file, @NotNull Path disk,
                             @NotNull Path reviewed, @NotNull String shipped,
                             @Nullable Runnable reloadFile, @NotNull List<Change> changes) {
        String key = key(plugin.getName(), file);
        Tracked previous = TRACKED.get(key);
        if (changes.isEmpty()) {
            TRACKED.remove(key);
            return;
        }
        List<Pending> pending = new ArrayList<>(changes.size());
        for (Change change : changes) {
            pending.add(new Pending(idFor(previous, change), plugin.getName(), file, change));
        }
        // Somebody told about three changes has not been told about a fourth.
        if (previous == null || !previous.pending().stream().map(Pending::id).collect(Collectors.toSet())
                .containsAll(pending.stream().map(Pending::id).toList())) {
            NOTIFIED.clear();
        }
        TRACKED.put(key, new Tracked(plugin, file, disk, reviewed, shipped, reloadFile, List.copyOf(pending)));
    }

    /** Drops what a file has pending, for a file that was replaced outright. */
    public static void forget(@NotNull Plugin plugin, @NotNull String file) {
        TRACKED.remove(key(plugin.getName(), file));
    }

    /** Drops everything a plugin has pending, when it disables. */
    public static void release(@NotNull String pluginName) {
        TRACKED.values().removeIf(tracked -> tracked.plugin().getName().equals(pluginName));
    }

    /** Drops everything, when the library disables. */
    public static void releaseAll() {
        TRACKED.clear();
        NOTIFIED.clear();
    }

    /** Every pending change, grouped by plugin and file. */
    public static @NotNull List<Pending> pending() {
        return TRACKED.values().stream()
                .flatMap(tracked -> tracked.pending().stream())
                .sorted(Comparator.comparing(Pending::plugin)
                        .thenComparing(Pending::file)
                        .thenComparingInt(Pending::id))
                .toList();
    }

    /**
     * Whether a viewer should be told about pending changes now.
     *
     * <p>Once per batch: a player who rejoins is not told again, and everyone
     * is told again when something new becomes pending.
     */
    public static boolean shouldNotify(@NotNull UUID viewer) {
        return !TRACKED.isEmpty() && NOTIFIED.add(viewer);
    }

    /**
     * Applies or keeps every pending change the filter selects.
     *
     * <p>Each file is read again first, so a change the owner made by hand
     * since it was listed counts as stale rather than being overwritten.
     * Applied changes are then made live: the file reloads itself, and the
     * plugin's declared {@link Reloads} run.
     *
     * @param which which changes
     * @param apply {@code true} to write the new default, {@code false} to keep the owner's value
     * @return what happened
     */
    public static @NotNull Decision decide(@NotNull Predicate<Pending> which, boolean apply) {
        int applied = 0;
        int kept = 0;
        int stale = 0;
        Map<String, Plugin> touched = new LinkedHashMap<>();
        List<Runnable> fileReloads = new ArrayList<>();

        for (Tracked tracked : List.copyOf(TRACKED.values())) {
            List<Pending> chosen = tracked.pending().stream().filter(which).toList();
            if (chosen.isEmpty()) {
                continue;
            }
            int decided = decideIn(tracked, chosen, apply);
            stale += chosen.size() - decided;
            if (!apply) {
                kept += decided;
                continue;
            }
            applied += decided;
            if (decided > 0) {
                touched.put(tracked.plugin().getName(), tracked.plugin());
                if (tracked.reloadFile() != null) {
                    fileReloads.add(tracked.reloadFile());
                }
            }
        }

        fileReloads.forEach(Runnable::run);
        List<String> reloaded = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        touched.forEach((name, plugin) -> {
            Reloads reloads = Reloads.declared(name);
            if (reloads != null) {
                reloads.run();
                reloaded.add(name);
            } else if (fileReloads.isEmpty()) {
                manual.add(name);
            }
        });
        return new Decision(applied, kept, stale, List.copyOf(reloaded), List.copyOf(manual));
    }

    /** Decides changes in one file, returning how many were still pending. */
    private static int decideIn(Tracked tracked, List<Pending> chosen, boolean apply) {
        Debug debug = Debug.of(tracked.plugin());
        try {
            YamlConfiguration disk = DefaultsMerge.yaml();
            disk.load(tracked.disk().toFile());
            YamlConfiguration shipped = DefaultsMerge.yaml();
            shipped.loadFromString(tracked.shipped());
            YamlConfiguration reviewed = null;
            if (Files.exists(tracked.reviewed())) {
                reviewed = DefaultsMerge.yaml();
                reviewed.load(tracked.reviewed().toFile());
            }

            DefaultsMerge.Result fresh = DefaultsMerge.merge(disk, reviewed, shipped);
            YamlConfiguration next = fresh.reviewed();
            boolean diskChanged = !fresh.added().isEmpty();
            int decided = 0;
            for (Pending pending : chosen) {
                Change now = fresh.pending().stream()
                        .filter(change -> change.path().equals(pending.change().path())
                                && DefaultsMerge.same(change.shipped(), pending.change().shipped()))
                        .findFirst()
                        .orElse(null);
                if (now == null) {
                    continue;
                }
                if (apply) {
                    DefaultsMerge.apply(disk, next, now);
                    diskChanged = true;
                } else {
                    DefaultsMerge.keep(next, now);
                }
                decided++;
            }

            if (diskChanged) {
                BundledResources.write(tracked.disk(), disk.saveToString());
            }
            BundledResources.write(tracked.reviewed(), next.saveToString());
            track(tracked.plugin(), tracked.file(), tracked.disk(), tracked.reviewed(), tracked.shipped(),
                    tracked.reloadFile(), DefaultsMerge.merge(disk, next, shipped).pending());
            return decided;
        } catch (IOException | InvalidConfigurationException failure) {
            debug.warn("Could not read " + tracked.file() + " to decide on its defaults: " + failure.getMessage());
            return 0;
        }
    }

    private static int idFor(@Nullable Tracked previous, Change change) {
        // The same change keeps its id across a reload, so a link clicked a
        // moment later still points at it.
        if (previous != null) {
            for (Pending pending : previous.pending()) {
                if (pending.change().path().equals(change.path())
                        && DefaultsMerge.same(pending.change().shipped(), change.shipped())) {
                    return pending.id();
                }
            }
        }
        return IDS.incrementAndGet();
    }

    private static String key(String plugin, String file) {
        return plugin + '/' + file;
    }
}
