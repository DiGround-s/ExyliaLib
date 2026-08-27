package net.exylia.lib.schedule;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.schedule.internal.NextFire;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.editor.Editors;
import net.exylia.lib.util.editor.ListEditor;
import net.exylia.lib.util.internal.Conditions;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One plugin's timetable.
 *
 * <pre>{@code
 * private PluginSchedules schedules;
 *
 * public void onEnable() {
 *     schedules = Schedules.of(this);
 *     // The gates only this plugin can answer.
 *     schedules.condition("event-inactive", s -> !events.isActive(s.target()));
 *     // What a fire actually does.
 *     schedules.onFire(s -> events.start(s.target()));
 * }
 *
 * // Whenever the configuration is (re)loaded:
 * schedules.set(configs.stream().flatMap(c -> c.schedules().stream()).toList());
 * }</pre>
 *
 * <h2>Nothing ticks per plugin</h2>
 * There is one library-wide timer for every schedule on the server, and it is
 * asynchronous. What it does each second is compare one {@code long} per plugin
 * against the clock, so a server with two hundred schedules costs the same as a
 * server with two until one of them is actually due.
 *
 * <h2>Gates run where they can be answered</h2>
 * The tick is off the main thread, but the moment a schedule is due the work
 * moves onto the plugin's own scheduler before a single gate is evaluated.
 * A condition asking whether an event is running, or how many players are in a
 * world, is therefore free to touch the server, and so is the handler.
 *
 * <h2>A blocked fire is not retried</h2>
 * A schedule stopped by a gate is skipped, and the next fire is the next one the
 * timetable names. Retrying would mean an event that was blocked at eight
 * o'clock starting at eight minutes past, which is not what the timetable said.
 *
 * @since 1.70.0
 */
public final class PluginSchedules {

    /**
     * How late a fire may be and still happen: two minutes.
     *
     * <p>A server that froze, or a laptop that was suspended, comes back with
     * schedules due in the past. Firing them all at once is worse than missing
     * them, and firing none of them loses the one that was due while the tick
     * was a second late.
     */
    private static final long GRACE_MILLIS = 120_000L;

    private final Plugin plugin;
    private final Debug debug;
    private final Map<String, Predicate<Schedule>> conditions = new ConcurrentHashMap<>();

    /**
     * The entries, replaced wholesale rather than mutated.
     *
     * <p>Read by the shared tick and written by whoever reloads a config, which
     * are different threads. A new immutable list per reload means the tick
     * never sees half of one.
     */
    private volatile List<Slot> slots = List.of();

    /** The soonest {@link Slot#nextFire} there is, so an idle tick is one read. */
    private volatile long earliest = Long.MAX_VALUE;

    private volatile ZoneId zone = ZoneId.systemDefault();
    private volatile Consumer<Schedule> handler = schedule -> { };

    PluginSchedules(@NotNull Plugin plugin, @NotNull ZoneId zone) {
        this.plugin = plugin;
        this.debug = Debug.of(plugin);
        this.zone = zone;
    }

    /** The plugin this timetable belongs to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    // ------------------------------------------------------------------- setup

    /**
     * Which calendar the times are written in.
     *
     * <p>Defaults to the library's own {@code timezone} setting, so a network
     * changes clocks in one file rather than in every plugin. Set this only when
     * one plugin genuinely runs on a different one.
     *
     * @param zone the zone
     * @return this timetable
     */
    public @NotNull PluginSchedules zone(@NotNull ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
        reschedule();
        return this;
    }

    /** The calendar the times are read in. */
    public @NotNull ZoneId zone() {
        return zone;
    }

    /**
     * Registers a gate only this plugin can answer.
     *
     * <pre>{@code
     * schedules.condition("event-inactive", s -> !events.isActive(s.target()));
     * }</pre>
     *
     * <p>A schedule that lists the name in its {@code requires} fires only when
     * the test passes. This is the answer to "the schedule must not start an
     * event that is already running", which no amount of configuration syntax
     * can express and which every plugin with a scheduler has had to hard-code.
     *
     * <p>Called on the plugin's own scheduler, so it may touch the server.
     *
     * @param name what a schedule writes in {@code requires}, case-insensitive
     * @param test whether the schedule may fire
     * @return this timetable
     */
    public @NotNull PluginSchedules condition(@NotNull String name,
                                              @NotNull Predicate<Schedule> test) {
        conditions.put(key(name), Objects.requireNonNull(test, "test"));
        return this;
    }

    /** The gate names this plugin has registered. */
    public @NotNull java.util.Set<String> conditionNames() {
        return java.util.Set.copyOf(conditions.keySet());
    }

    /**
     * What a fire does.
     *
     * <p>Called on the plugin's own scheduler, once per kept fire, after every
     * gate has passed. Replacing it replaces it: there is one handler, because a
     * timetable that fires two things is two schedules.
     *
     * @param handler told which schedule fired
     * @return this timetable
     */
    public @NotNull PluginSchedules onFire(@NotNull Consumer<Schedule> handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
        return this;
    }

    // ------------------------------------------------------------------ the set

    /**
     * Replaces the whole timetable.
     *
     * <p>What a config reload calls. Everything is recomputed from the clock as
     * it is now, so a schedule that has not changed keeps the fire it was going
     * to have and one that has changed gets a new one.
     *
     * @param schedules the schedules; copied, never held
     */
    public void set(@NotNull List<Schedule> schedules) {
        Objects.requireNonNull(schedules, "schedules");
        long now = System.currentTimeMillis();
        List<Slot> next = new ArrayList<>(schedules.size());
        for (Schedule schedule : schedules) {
            if (schedule == null) {
                continue;
            }
            Slot slot = new Slot(schedule);
            slot.nextFire = schedule.enabled()
                    ? NextFire.afterMillis(schedule, now, zone)
                    : Long.MAX_VALUE;
            next.add(slot);
        }
        this.slots = List.copyOf(next);
        recomputeEarliest();
        debug.log("Timetable set: " + next.size() + " schedule(s), next at "
                + describe(earliest));
    }

    /** Forgets every schedule. */
    public void clear() {
        slots = List.of();
        earliest = Long.MAX_VALUE;
    }

    /** The schedules as they stand, in the order they were set. */
    public @NotNull List<Schedule> all() {
        List<Schedule> schedules = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            schedules.add(slot.schedule);
        }
        return List.copyOf(schedules);
    }

    /** How many schedules there are. */
    public int size() {
        return slots.size();
    }

    // ----------------------------------------------------------------- reading

    /**
     * When anything fires next.
     *
     * @return the moment, or nothing when nothing is scheduled
     */
    public @NotNull Optional<Instant> nextFire() {
        long at = earliest;
        return at == Long.MAX_VALUE ? Optional.empty() : Optional.of(Instant.ofEpochMilli(at));
    }

    /**
     * When a target fires next.
     *
     * @param target what to look for; {@code null} matches schedules with no
     *               target of their own
     * @return the moment, or nothing when that target is not scheduled
     */
    public @NotNull Optional<Instant> nextFireOf(@Nullable String target) {
        long soonest = Long.MAX_VALUE;
        for (Slot slot : slots) {
            if (Objects.equals(slot.schedule.target(), target) && slot.nextFire < soonest) {
                soonest = slot.nextFire;
            }
        }
        return soonest == Long.MAX_VALUE
                ? Optional.empty()
                : Optional.of(Instant.ofEpochMilli(soonest));
    }

    /**
     * How long until a target fires, in milliseconds.
     *
     * <p>What a placeholder and a countdown hologram want, without either of
     * them doing clock arithmetic of its own.
     *
     * @param target what to look for
     * @return the milliseconds, or nothing when that target is not scheduled
     */
    public @NotNull OptionalLong millisUntilNext(@Nullable String target) {
        return nextFireOf(target)
                .map(at -> OptionalLong.of(Math.max(0L, at.toEpochMilli() - System.currentTimeMillis())))
                .orElseGet(OptionalLong::empty);
    }

    /** Whether anything at all is scheduled for a target. */
    public boolean isScheduled(@Nullable String target) {
        return nextFireOf(target).isPresent();
    }

    // ------------------------------------------------------------------ firing

    /**
     * Fires a schedule now, as though the clock had reached it.
     *
     * <p>For a command that starts something early. Every gate is still checked,
     * because "start it now" is a request about the time and not about the
     * conditions.
     *
     * @param schedule what to fire
     * @return whether it was kept
     */
    public boolean fireNow(@NotNull Schedule schedule) {
        return keep(schedule, System.currentTimeMillis(), null);
    }

    // ---------------------------------------------------------------- the tick

    /**
     * One pass of the shared timer.
     *
     * <p>Package-private and hot: this is called once a second for every plugin
     * on the server, so the path where nothing is due is one volatile read and
     * one comparison.
     *
     * @param now the clock, as epoch milliseconds
     */
    void tick(long now) {
        if (now < earliest) {
            return;
        }
        List<Slot> current = slots;
        long soonest = Long.MAX_VALUE;
        for (Slot slot : current) {
            long due = slot.nextFire;
            if (due <= now) {
                // Advanced before the fire is dispatched, not after: the
                // dispatch hops threads, and a slot still holding a past due
                // time would fire again on the next tick.
                slot.nextFire = slot.schedule.enabled()
                        ? NextFire.afterMillis(slot.schedule, now, zone)
                        : Long.MAX_VALUE;
                if (now - due <= GRACE_MILLIS) {
                    dispatch(slot, now);
                } else {
                    debug.log("Schedule '" + slot.schedule.displayName() + "' was "
                            + (now - due) / 1000 + "s late and was skipped");
                }
            }
            if (slot.nextFire < soonest) {
                soonest = slot.nextFire;
            }
        }
        earliest = soonest;
    }

    private void dispatch(Slot slot, long now) {
        // Onto the plugin's own scheduler, so a gate and a handler may touch the
        // server. Also what makes a fire stop existing the moment the plugin is
        // disabled: the task module cancels what it owns.
        Tasks.of(plugin).run(() -> keep(slot.schedule, now, slot));
    }

    private boolean keep(Schedule schedule, long now, @Nullable Slot slot) {
        if (!schedule.enabled()) {
            return false;
        }
        if (slot != null && schedule.cooldown() != null
                && now - slot.lastRun < schedule.cooldown().toMillis()) {
            debug.log(skipped(schedule, "it ran less than "
                    + Schedule.writeDuration(schedule.cooldown()) + " ago"));
            return false;
        }
        int online = Bukkit.getOnlinePlayers().size();
        if (!schedule.allowsPlayerCount(online)) {
            debug.log(skipped(schedule, online + " players are online"));
            return false;
        }
        if (schedule.condition() != null
                && !Conditions.holds(schedule.condition(), null,
                        (subject, problem) -> debug.warn("Schedule '" + schedule.displayName()
                                + "': " + problem))) {
            debug.log(skipped(schedule, "its condition did not hold"));
            return false;
        }
        for (String required : schedule.requires()) {
            Predicate<Schedule> gate = conditions.get(key(required));
            if (gate == null) {
                // Refused, not ignored. Every other unreadable value in this
                // library fails open, because withholding a reward is invisible
                // and handing one out is loud. A schedule is the other way
                // round: firing a gate nobody could answer starts an event the
                // admin asked not to start, which is the loud failure here.
                debug.warn("Schedule '" + schedule.displayName() + "' requires \"" + required
                        + "\", which " + plugin.getName() + " never registered; it will not fire");
                return false;
            }
            if (!gate.test(schedule)) {
                debug.log(skipped(schedule, "\"" + required + "\" did not pass"));
                return false;
            }
        }
        if (slot != null) {
            slot.lastRun = now;
        }
        try {
            handler.accept(schedule);
        } catch (RuntimeException failure) {
            debug.error("A schedule handler failed on '" + schedule.displayName() + "'", failure);
        }
        return true;
    }

    private static String skipped(Schedule schedule, String why) {
        return "Schedule '" + schedule.displayName() + "' skipped: " + why;
    }

    // ------------------------------------------------------------------ editor

    /**
     * A screen for editing a list of schedules.
     *
     * <pre>{@code
     * schedules.editor(config.schedules(), config.id())
     *          .title("{primary}&lSCHEDULES")
     *          .onSave(edited -> configs.save(config.withSchedules(edited)))
     *          .open(player);
     * }</pre>
     *
     * <p>The list editor, over schedules: pagination, add, edit, delete, copy
     * and paste, none of which is written again here. A row is edited as one
     * form with every field prefilled.
     *
     * @param schedules     what is being edited; copied, never held
     * @param defaultTarget what a new row fires, normally the id of the thing
     *                      the screen belongs to; {@code null} makes the target
     *                      a field the admin fills in
     * @return the editor, ready to open
     */
    public @NotNull ListEditor<Schedule> editor(@NotNull List<Schedule> schedules,
                                                @Nullable String defaultTarget) {
        return Editors.of(plugin).list(
                new ScheduleDescriptor(plugin, defaultTarget, this::conditionNames),
                Schedule.class, schedules);
    }

    // ------------------------------------------------------------------ private

    private void reschedule() {
        set(all());
    }

    private void recomputeEarliest() {
        long soonest = Long.MAX_VALUE;
        for (Slot slot : slots) {
            if (slot.nextFire < soonest) {
                soonest = slot.nextFire;
            }
        }
        earliest = soonest;
    }

    private String describe(long at) {
        return at == Long.MAX_VALUE ? "never" : Instant.ofEpochMilli(at).atZone(zone).toString();
    }

    private static String key(String name) {
        return name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String toString() {
        return "PluginSchedules[" + plugin.getName() + ", " + slots.size() + " scheduled]";
    }

    /**
     * One schedule and the two numbers the runtime keeps about it.
     *
     * <p>Mutable and unsynchronised on purpose: both fields are {@code long}s
     * written by the tick and read by the tick, and the volatile on the list is
     * what publishes the slot itself.
     */
    private static final class Slot {

        private final Schedule schedule;
        private volatile long nextFire = Long.MAX_VALUE;
        private volatile long lastRun;

        private Slot(Schedule schedule) {
            this.schedule = schedule;
        }
    }
}
