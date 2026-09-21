package net.exylia.lib.util.showcase.internal;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.exylia.lib.util.showcase.ShowcaseAct;
import net.exylia.lib.util.showcase.ShowcaseSettings;
import net.exylia.lib.util.showcase.ShowcaseStage;
import net.exylia.lib.util.showcase.ShowcaseTurn;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * One place and what it is showing. Ticked only from its own region, so its
 * fields need no lock; {@link #stop} comes from a command or a reload and only
 * touches the volatile turn.
 */
public final class LiveStage {

    private final Location where;
    private final Supplier<ShowcaseSettings> settings;
    private final Supplier<Predicate<Player>> sees;
    private final ShowcaseAct act;

    private @Nullable TaskHandle loop;
    private volatile @Nullable ShowcaseTurn turn;
    private volatile boolean stopped;
    private @Nullable String last;
    private long restUntil;

    public LiveStage(@NotNull Location where, @NotNull Supplier<ShowcaseSettings> settings,
                     @NotNull Supplier<Predicate<Player>> sees, @NotNull ShowcaseAct act) {
        this.where = where.clone();
        this.settings = settings;
        this.sees = sees;
        this.act = act;
    }

    public void loop(@NotNull TaskHandle loop) {
        this.loop = loop;
    }

    /**
     * Starts a turn when the last one and its rest are over and somebody is
     * watching.
     *
     * @param now the wall clock, in milliseconds
     * @return whether a turn started
     */
    public boolean tick(long now) {
        if (stopped || now < restUntil) {
            return false;
        }
        ShowcaseSettings config = settings.get();
        List<Player> watching = watchers(config.radius());
        if (watching.isEmpty()) {
            return false;
        }
        ShowcaseTurn before = turn;
        ShowcaseTurn next = act.play(new View(config, watching));
        if (next == null) {
            return false;
        }
        // The one before is taken away only now, so the bodies it left standing
        // through the rest are there until the next ones arrive.
        if (before != null) {
            before.cancel();
        }
        turn = next;
        restUntil = now + next.durationMillis() + config.pauseMillis();
        return true;
    }

    public void stop() {
        stopped = true;
        TaskHandle running = loop;
        if (running != null) {
            running.cancel();
        }
        ShowcaseTurn showing = turn;
        turn = null;
        if (showing != null) {
            showing.cancel();
        }
    }

    private List<Player> watchers(double radius) {
        World world = where.getWorld();
        if (world == null) {
            return List.of();
        }
        Predicate<Player> allowed = sees.get();
        double limit = radius * radius;
        List<Player> found = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(where) <= limit && allowed.test(player)) {
                found.add(player);
            }
        }
        return List.copyOf(found);
    }

    /** What the act sees of one turn. */
    private final class View implements ShowcaseStage {

        private final ShowcaseSettings config;
        private final List<Player> watching;

        private View(ShowcaseSettings config, List<Player> watching) {
            this.config = config;
            this.watching = watching;
        }

        @Override
        public @NotNull Location where() {
            return where.clone();
        }

        @Override
        public @NotNull List<Player> watching() {
            return watching;
        }

        @Override
        public @NotNull SequenceTarget audience() {
            Set<Player> cast = Set.copyOf(watching);
            return SequenceTarget.at(where.clone())
                    .within(config.radius())
                    .visibleTo((observer, source) -> cast.contains(observer));
        }

        @Override
        public @NotNull ShowcaseSettings settings() {
            return config;
        }

        @Override
        public @NotNull Player someone() {
            return watching.get(ThreadLocalRandom.current().nextInt(watching.size()));
        }

        @Override
        public @NotNull Player someoneBut(@NotNull Player other) {
            if (watching.size() < 2) {
                return other;
            }
            List<Player> rest = new ArrayList<>(watching);
            rest.remove(other);
            return rest.get(ThreadLocalRandom.current().nextInt(rest.size()));
        }

        @Override
        public <T> @Nullable T pick(@NotNull Collection<? extends T> pool,
                                    @NotNull Function<? super T, String> id) {
            Set<String> only = config.only().stream()
                    .map(entry -> entry.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            List<T> allowed = new ArrayList<>();
            for (T each : pool) {
                if (only.isEmpty() || only.contains(id.apply(each).toLowerCase(Locale.ROOT))) {
                    allowed.add(each);
                }
            }
            List<T> fresh = allowed.stream().filter(each -> !id.apply(each).equals(last)).toList();
            List<T> from = fresh.isEmpty() ? allowed : fresh;
            if (from.isEmpty()) {
                return null;
            }
            T chosen = from.get(ThreadLocalRandom.current().nextInt(from.size()));
            last = id.apply(chosen);
            return chosen;
        }
    }
}
