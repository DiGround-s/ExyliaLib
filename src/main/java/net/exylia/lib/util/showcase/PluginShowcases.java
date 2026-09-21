package net.exylia.lib.util.showcase;

import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.showcase.internal.LiveStage;
import net.exylia.lib.util.teleport.ExyliaLocation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One plugin's showcases.
 *
 * <pre>{@code
 * PluginShowcases showcases = Showcases.of(this)
 *         .start(() -> config.get().showcase(), this::saveShowcases, this::playOne);
 * }</pre>
 *
 * <p>Nothing plays until {@link #start}. After that the list of places is read
 * from the settings again on every {@link #rebuild}, which a reload calls.
 *
 * @since 1.188.0
 */
public final class PluginShowcases {

    /** How far from an admin a showcase can be and still be the one they mean to remove. */
    private static final double REMOVE_REACH = 5;

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final List<LiveStage> stages = new ArrayList<>();
    private final Listener worlds = new Listener() {
        @EventHandler
        public void onWorldLoad(WorldLoadEvent event) {
            String world = event.getWorld().getName();
            Supplier<ShowcaseSettings> read = settings;
            if (read != null && read.get().locations().stream().anyMatch(entry -> ShowcaseSettings.isIn(entry, world))) {
                rebuild();
            }
        }
    };

    private volatile @Nullable Supplier<ShowcaseSettings> settings;
    private volatile @Nullable Consumer<List<String>> save;
    private volatile @Nullable ShowcaseAct act;
    private volatile @NotNull Predicate<Player> sees = player -> true;

    PluginShowcases(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
    }

    /**
     * Who may watch. Everyone, unless this says otherwise.
     *
     * <p>Read on every turn, so a player who turns cosmetics off stops being
     * cast and stops seeing the next one.
     *
     * @param sees whether a player sees showcases
     * @return this
     */
    public @NotNull PluginShowcases visibleTo(@NotNull Predicate<Player> sees) {
        this.sees = sees;
        return this;
    }

    /**
     * Starts a showcase at every place the settings name.
     *
     * <p>Calling it again replaces all three and starts over.
     *
     * @param settings where the places are and how they pace themselves, read
     *                 afresh on every rebuild and every turn
     * @param save     writes the list of places when an admin changes it; it is
     *                 expected to update what {@code settings} returns
     * @param act      what one turn does
     * @return this
     */
    public @NotNull PluginShowcases start(@NotNull Supplier<ShowcaseSettings> settings,
                                          @NotNull Consumer<List<String>> save,
                                          @NotNull ShowcaseAct act) {
        boolean first = this.settings == null;
        this.settings = settings;
        this.save = save;
        this.act = act;
        if (first) {
            Bukkit.getPluginManager().registerEvents(worlds, plugin);
        }
        rebuild();
        return this;
    }

    /**
     * Stops every showcase and starts one for every place the settings name.
     *
     * <p>Rebuilt whole: the list can have changed in any direction, and there
     * are only ever a handful of places. A place whose world is not loaded yet
     * starts when that world loads.
     */
    public synchronized void rebuild() {
        stopStages();
        Supplier<ShowcaseSettings> read = settings;
        ShowcaseAct playing = act;
        if (read == null || playing == null) {
            return;
        }
        for (Location where : read.get().places()) {
            LiveStage stage = new LiveStage(where, read, () -> sees, playing);
            stage.loop(tasks.runAtLocationTimer(where, 20, 20, () -> stage.tick(System.currentTimeMillis())));
            stages.add(stage);
        }
    }

    /**
     * Adds a showcase where somebody stands, facing the way they face.
     *
     * @param where the place
     */
    public void add(@NotNull Location where) {
        change(list -> list.add(ExyliaLocation.of(where).toString()));
    }

    /**
     * Removes the showcase closest to a spot, if one is within a few steps.
     *
     * @param where where the admin stands
     * @return where the removed one stood, or {@code null} when none was close
     */
    public @Nullable Location removeNear(@NotNull Location where) {
        String nearest = null;
        Location found = null;
        double best = REMOVE_REACH * REMOVE_REACH;
        for (String entry : all()) {
            Location at = ShowcaseSettings.parse(entry);
            if (at == null || !at.getWorld().equals(where.getWorld())) {
                continue;
            }
            double distance = at.distanceSquared(where);
            if (distance <= best) {
                best = distance;
                nearest = entry;
                found = at;
            }
        }
        if (nearest == null) {
            return null;
        }
        String gone = nearest;
        change(list -> list.remove(gone));
        return found;
    }

    /**
     * Removes every showcase.
     *
     * @return how many there were
     */
    public int clear() {
        int had = all().size();
        if (had > 0) {
            change(List::clear);
        }
        return had;
    }

    /** Every place, as it is written in the settings. */
    public @NotNull List<String> all() {
        Supplier<ShowcaseSettings> read = settings;
        return read == null ? List.of() : read.get().locations();
    }

    /** How many showcases are standing, including ones between turns. */
    public synchronized int active() {
        return stages.size();
    }

    /**
     * Stops every showcase and takes away what they show, until the next
     * {@link #start}. The library calls it when the plugin is disabled.
     */
    public synchronized void stop() {
        HandlerList.unregisterAll(worlds);
        stopStages();
        settings = null;
        save = null;
        act = null;
    }

    private void stopStages() {
        for (LiveStage stage : stages) {
            stage.stop();
        }
        stages.clear();
    }

    private void change(Consumer<List<String>> edit) {
        Consumer<List<String>> write = save;
        if (write == null) {
            throw new IllegalStateException("start() the showcases before changing where they stand");
        }
        List<String> list = new ArrayList<>(all());
        edit.accept(list);
        write.accept(List.copyOf(list));
        rebuild();
    }
}
