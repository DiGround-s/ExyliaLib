package net.exylia.lib.util.preview.internal;

import net.exylia.lib.effect.internal.LiveBars;
import net.exylia.lib.npc.NpcAnimation;
import net.exylia.lib.npc.NpcSpec;
import net.exylia.lib.npc.Npcs;
import net.exylia.lib.npc.PlacedNpc;
import net.exylia.lib.npc.PluginNpcs;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.preview.Preview;
import net.exylia.lib.util.preview.PreviewSettings;
import net.exylia.lib.util.sequence.Sequence;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.exylia.lib.util.sequence.Sequences;
import net.exylia.lib.util.teleport.ExyliaLocation;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * One plugin's mannequins, and the players who are trying an effect on them.
 *
 * <p>A mannequin is a placed NPC. Nobody is moved and nothing is locked: the
 * player is lent a sequence for a while, and every left click on a mannequin
 * plays it there, for them alone. What they have equipped is never touched,
 * so the loan cannot be carried into a real fight.
 *
 * <p>One timer a second keeps the placed NPCs in step with the settings (a
 * reload, an admin placing one, a world that loaded late) and counts every
 * loan down on its player's own thread.
 */
@ApiStatus.Internal
public final class MannequinTrials {

    private static final String ID_PREFIX = "preview_mannequin_";
    /** How long a struck mannequin stays hidden when the effect has no length of its own. */
    private static final long VANISH_FALLBACK_MILLIS = 3000;
    private static final long VANISH_MAX_MILLIS = 10_000;

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final PluginNpcs npcs;
    private final Supplier<PreviewSettings> settings;
    private volatile @Nullable BiFunction<Player, Integer, Component> barText;

    private final Map<UUID, Trial> trials = new ConcurrentHashMap<>();
    /** Placed NPC id to the location it stands at. */
    private final Map<String, Location> placed = new ConcurrentHashMap<>();
    /** Placed NPC id to the players it is hidden from right now. */
    private final Map<String, Set<UUID>> vanished = new ConcurrentHashMap<>();
    private @Nullable TaskHandle loop;

    public MannequinTrials(@NotNull Plugin plugin, @NotNull TaskScheduler tasks,
                           @NotNull Supplier<PreviewSettings> settings) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.npcs = Npcs.of(plugin);
        this.settings = settings;
    }

    public void bar(@Nullable BiFunction<Player, Integer, Component> bar) {
        this.barText = bar;
    }

    /** Starts the timer, once. */
    public synchronized void start() {
        if (loop != null) return;
        loop = tasks.runTimer(1, 20, this::tick);
    }

    /** Lends a player a sequence, replacing whatever they were trying. */
    public @NotNull Preview lend(@NotNull Player viewer, @NotNull Sequence sequence) {
        start();
        PreviewSettings.Mannequin config = settings.get().mannequin();
        Trial trial = new Trial(viewer, sequence, System.currentTimeMillis() + config.trialSeconds() * 1000L);
        Trial old = trials.put(viewer.getUniqueId(), trial);
        if (old != null) old.close();
        LiveBars.show(viewer, trial.bar, barText(viewer, config.trialSeconds()), 1f, "purple", "progress");
        return trial;
    }

    /** Ends every loan and takes every mannequin down. */
    public synchronized void shutdown() {
        if (loop != null) loop.cancel();
        loop = null;
        List.copyOf(trials.values()).forEach(Trial::end);
        placed.keySet().forEach(npcs::unplace);
        placed.clear();
        vanished.clear();
    }

    public int endAll() {
        List<Trial> all = List.copyOf(trials.values());
        all.forEach(Trial::end);
        return all.size();
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        sync();
        for (Trial trial : trials.values()) {
            Player viewer = trial.viewer;
            if (!viewer.isOnline()) {
                trial.end();
                continue;
            }
            tasks.runAtEntity(viewer, trial::check, trial::end);
        }
    }

    /** Places the mannequins the settings name and takes down the ones they no longer do. */
    private synchronized void sync() {
        Map<String, Location> wanted = new HashMap<>();
        for (String entry : settings.get().mannequin().locations()) {
            Location at = parse(entry);
            if (at != null) wanted.put(ID_PREFIX + Integer.toHexString(entry.hashCode()), at);
        }
        for (String id : List.copyOf(placed.keySet())) {
            if (!wanted.containsKey(id)) {
                npcs.unplace(id);
                placed.remove(id);
                vanished.remove(id);
            }
        }
        wanted.forEach((id, at) -> {
            if (placed.containsKey(id)) return;
            PlacedNpc npc = npcs.place(id, NpcSpec.builder().build(), at);
            Set<UUID> hidden = vanished.computeIfAbsent(id, key -> ConcurrentHashMap.newKeySet());
            npc.visibleIf(player -> !hidden.contains(player.getUniqueId()));
            npc.onClick((player, click) -> {
                if (click.isLeft()) strike(player, npc, at, hidden);
            });
            placed.put(id, at);
        });
    }

    private void strike(Player player, PlacedNpc npc, Location at, Set<UUID> hidden) {
        Trial trial = trials.get(player.getUniqueId());
        if (trial == null || trial.isFinished()) return;
        npc.play(NpcAnimation.parse("hurt"), player);
        // No target: a [POTION] or a victim's title would land on the striker,
        // and a lent effect must never hand out anything usable.
        Sequences.of(plugin).play(trial.sequence,
                SequenceTarget.at(at.clone().add(0, 1, 0)).by(player).onlyTo(player)
                        .scale(settings.get().mannequin().effectScale()));
        if (settings.get().mannequin().vanishOnHit()) {
            long length = trial.sequence.durationMillis();
            long millis = length <= 0 || length > VANISH_MAX_MILLIS ? VANISH_FALLBACK_MILLIS : length;
            UUID id = player.getUniqueId();
            hidden.add(id);
            tasks.runLater(Math.max(1, millis / 50), () -> hidden.remove(id));
        }
    }

    private List<Location> mannequins() {
        return new ArrayList<>(placed.values());
    }

    private static @Nullable Location parse(String entry) {
        try {
            Location at = ExyliaLocation.fromString(entry).toBukkitLocation();
            return at == null || at.getWorld() == null ? null : at;
        } catch (RuntimeException notALocation) {
            return null;
        }
    }

    private Component barText(Player viewer, int secondsLeft) {
        BiFunction<Player, Integer, Component> custom = barText;
        if (custom != null) return custom.apply(viewer, secondsLeft);
        return Text.component("{primary}&lPREVIEW {letters_black}» {letters}Hit a mannequin {letters_black}• {info}"
                + secondsLeft + "s ⌚");
    }

    /** One player's loan. */
    private final class Trial implements Preview {

        private final Player viewer;
        private final Sequence sequence;
        private final long endsAt;
        private final UUID bar = UUID.randomUUID();
        private volatile boolean arrived;
        private volatile boolean finished;

        private Trial(Player viewer, Sequence sequence, long endsAt) {
            this.viewer = viewer;
            this.sequence = sequence;
            this.endsAt = endsAt;
        }

        /** On the viewer's own thread: the countdown, and whether they walked away. */
        private void check() {
            if (finished) return;
            PreviewSettings.Mannequin config = settings.get().mannequin();
            long left = endsAt - System.currentTimeMillis();
            if (left <= 0) {
                end();
                return;
            }
            boolean near = isNear(config.radius());
            if (near) arrived = true;
            else if (arrived) {
                end();
                return;
            }
            refresh((int) Math.ceil(left / 1000.0));
        }

        private boolean isNear(double radius) {
            Location here = viewer.getLocation();
            double reach = radius * radius;
            for (Location at : mannequins()) {
                if (at.getWorld().equals(here.getWorld()) && at.distanceSquared(here) <= reach) return true;
            }
            return false;
        }

        private void refresh(int secondsLeft) {
            int total = settings.get().mannequin().trialSeconds();
            LiveBars.update(viewer, bar, barText(viewer, secondsLeft),
                    Math.max(0f, Math.min(1f, secondsLeft / (float) total)), "purple", "progress", true);
        }

        @Override
        public @NotNull Player viewer() {
            return viewer;
        }

        @Override
        public void end() {
            if (finished) return;
            trials.remove(viewer.getUniqueId(), this);
            close();
        }

        /** Takes the bar away without touching the map, for a loan another one replaced. */
        private void close() {
            finished = true;
            LiveBars.hide(viewer, bar);
        }

        @Override
        public boolean isFinished() {
            return finished;
        }
    }
}
