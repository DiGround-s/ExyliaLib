package net.exylia.lib.schedule;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One line of a timetable: when something happens, and what has to be true for
 * it to happen.
 *
 * <pre>{@code
 * Schedule friday = Schedule.at("koth_desert", LocalTime.of(20, 0))
 *         .withDays(Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY))
 *         .withMinPlayers(10);
 * }</pre>
 *
 * <h2>Two ways of saying when</h2>
 * A schedule fires either at named clock times ({@code times}) or on a repeating
 * interval inside a window ({@code every} between {@code from} and {@code to}).
 * Setting {@code every} chooses the second; leaving it unset chooses the first.
 * Both are filtered by {@code days}, and an empty day set means every day.
 *
 * <p>Nothing here is a cron string. A cron string is unreadable in a menu and
 * unwritable in a form, and every schedule any Exylia plugin has ever needed is
 * "these times, these days, if these things hold".
 *
 * <h2>The gates are checked at the moment it fires, not when it is planned</h2>
 * {@code minPlayers}, {@code maxPlayers}, {@code condition}, {@code requires}
 * and {@code cooldown} decide whether the fire is <em>kept</em>. A schedule
 * blocked by one of them is not rescheduled to try again later: the timetable
 * said twenty o'clock, and twenty o'clock has been and gone.
 *
 * <h2>Immutable</h2>
 * Like every configured thing in this library. An editor returns a new list and
 * the owner writes it, so an admin halfway through a screen has changed nothing
 * that is running.
 *
 * @param id         identity, stable across edits
 * @param name       what an admin calls it, or {@code null}
 * @param target     what it starts; the meaning is the owning plugin's, and it
 *                   is normally a configuration id
 * @param enabled    whether it may fire at all
 * @param days       which days it fires on; empty means every day
 * @param times      the clock times it fires at, sorted; ignored when
 *                   {@code every} is set
 * @param every      how often it repeats, or {@code null} for fixed times
 * @param from       the first minute of the interval window, or {@code null}
 *                   for midnight
 * @param to         the last minute of the interval window, or {@code null}
 *                   for the end of the day
 * @param minPlayers how many players must be online; zero means any
 * @param maxPlayers how many players may be online; zero means no limit
 * @param condition  an Exylia condition that must hold, or {@code null}
 * @param requires   named gates the owning plugin registered, all of which must
 *                   pass
 * @param cooldown   the shortest gap between two fires, or {@code null}
 * @since 1.70.0
 */
public record Schedule(
        @NotNull String id,
        @Nullable String name,
        @Nullable String target,
        boolean enabled,
        @NotNull Set<DayOfWeek> days,
        @NotNull List<LocalTime> times,
        @Nullable Duration every,
        @Nullable LocalTime from,
        @Nullable LocalTime to,
        int minPlayers,
        int maxPlayers,
        @Nullable String condition,
        @NotNull List<String> requires,
        @Nullable Duration cooldown) {

    /** How a time is written in configuration, and read back. */
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** What a schedule with nothing configured fires at. */
    private static final LocalTime DEFAULT_TIME = LocalTime.of(20, 0);

    /**
     * Normalises everything a form or a stored row can get wrong.
     *
     * <p>Blank text becomes {@code null}, a non-positive duration becomes
     * {@code null}, negative counts become zero, and the times are sorted and
     * deduplicated so two orderings of the same schedule are the same schedule.
     */
    public Schedule {
        Objects.requireNonNull(id, "id");
        name = blankToNull(name);
        target = blankToNull(target);
        condition = blankToNull(condition);
        days = days == null ? Set.of() : Set.copyOf(days);
        times = times == null ? List.of() : times.stream().distinct().sorted().toList();
        requires = requires == null ? List.of() : requires.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(one -> !one.isEmpty())
                .distinct()
                .toList();
        every = positive(every);
        cooldown = positive(cooldown);
        minPlayers = Math.max(0, minPlayers);
        maxPlayers = Math.max(0, maxPlayers);
    }

    // ------------------------------------------------------------------ making

    /**
     * A schedule that fires at fixed clock times, every day.
     *
     * @param target what it starts
     * @param times  when it fires
     * @return the schedule
     */
    public static @NotNull Schedule at(@Nullable String target, @NotNull LocalTime... times) {
        return new Schedule(UUID.randomUUID().toString(), null, target, true,
                Set.of(), List.of(times), null, null, null, 0, 0, null, List.of(), null);
    }

    /**
     * A schedule that repeats on an interval, every day, all day.
     *
     * @param target what it starts
     * @param every  how often
     * @return the schedule
     */
    public static @NotNull Schedule every(@Nullable String target, @NotNull Duration every) {
        return new Schedule(UUID.randomUUID().toString(), null, target, true,
                Set.of(), List.of(), every, null, null, 0, 0, null, List.of(), null);
    }

    /**
     * A blank schedule, for an editor's add button.
     *
     * <p>Given one time rather than none: a row that fires at no time at all is
     * a row an admin has to notice is broken, and eight o'clock is what most of
     * them were going to type.
     *
     * @param target what it starts
     * @return the schedule
     */
    public static @NotNull Schedule blank(@Nullable String target) {
        return at(target, DEFAULT_TIME);
    }

    // ----------------------------------------------------------------- reading

    /**
     * The name to show, falling back to what it fires at.
     *
     * @return something a human reads, never blank
     */
    public @NotNull String displayName() {
        if (name != null) {
            return name;
        }
        return isRunnable() ? describeTrigger() : "(not set)";
    }

    /** Whether this schedule says when it fires at all. */
    public boolean isRunnable() {
        return every != null || !times.isEmpty();
    }

    /** Whether it repeats on an interval rather than at fixed times. */
    public boolean isInterval() {
        return every != null;
    }

    /**
     * Whether it may fire on a day.
     *
     * @param day the day
     * @return {@code true} when the day is listed, or when none are
     */
    public boolean matchesDay(@NotNull DayOfWeek day) {
        return days.isEmpty() || days.contains(day);
    }

    /** The first minute of the interval window; midnight when unset. */
    public @NotNull LocalTime windowStart() {
        return from == null ? LocalTime.MIN : from;
    }

    /** The last minute of the interval window; the end of the day when unset. */
    public @NotNull LocalTime windowEnd() {
        return to == null ? LocalTime.MAX : to;
    }

    /** Whether an online count is inside this schedule's bounds. */
    public boolean allowsPlayerCount(int online) {
        return online >= minPlayers && (maxPlayers <= 0 || online <= maxPlayers);
    }

    // --------------------------------------------------------------- describing

    /**
     * When it fires, in one line an admin can read.
     *
     * @return {@code "20:00, 22:30"}, or {@code "every 2h, 10:00-23:00"}
     */
    public @NotNull String describeTrigger() {
        if (every != null) {
            String window = "";
            if (from != null || to != null) {
                window = ", " + TIME.format(windowStart()) + "-" + TIME.format(windowEnd());
            }
            return "every " + writeDuration(every) + window;
        }
        if (times.isEmpty()) {
            return "never";
        }
        List<String> written = new ArrayList<>(times.size());
        for (LocalTime time : times) {
            written.add(TIME.format(time));
        }
        return String.join(", ", written);
    }

    /**
     * Which days it fires on, in one line.
     *
     * @return {@code "Every day"}, or {@code "Mon, Fri, Sat"} in week order
     */
    public @NotNull String describeDays() {
        if (days.isEmpty()) {
            return "Every day";
        }
        List<String> written = new ArrayList<>(days.size());
        for (DayOfWeek day : DayOfWeek.values()) {
            if (days.contains(day)) {
                String full = day.name();
                written.add(full.charAt(0) + full.substring(1, 3).toLowerCase(Locale.ROOT));
            }
        }
        return String.join(", ", written);
    }

    /**
     * Every gate this schedule has, as lines, for a menu.
     *
     * @return the lines, empty when it fires unconditionally
     */
    public @NotNull List<String> describeGates() {
        List<String> lines = new ArrayList<>();
        if (minPlayers > 0) {
            lines.add(minPlayers + "+ players online");
        }
        if (maxPlayers > 0) {
            lines.add("at most " + maxPlayers + " online");
        }
        if (cooldown != null) {
            lines.add("at least " + writeDuration(cooldown) + " since the last one");
        }
        if (condition != null) {
            lines.add(condition);
        }
        for (String required : requires) {
            lines.add(required);
        }
        return List.copyOf(lines);
    }

    // ----------------------------------------------------------------- copying

    /** The same schedule under a new identity, for duplicating a row. */
    public @NotNull Schedule copy() {
        return new Schedule(UUID.randomUUID().toString(), name, target, enabled, days, times,
                every, from, to, minPlayers, maxPlayers, condition, requires, cooldown);
    }

    /** This schedule, pointed at something else. */
    public @NotNull Schedule withTarget(@Nullable String target) {
        return new Schedule(id, name, target, enabled, days, times, every, from, to,
                minPlayers, maxPlayers, condition, requires, cooldown);
    }

    /** This schedule, turned on or off. */
    public @NotNull Schedule withEnabled(boolean enabled) {
        return new Schedule(id, name, target, enabled, days, times, every, from, to,
                minPlayers, maxPlayers, condition, requires, cooldown);
    }

    /** This schedule, on different days. */
    public @NotNull Schedule withDays(@NotNull Set<DayOfWeek> days) {
        return new Schedule(id, name, target, enabled, days, times, every, from, to,
                minPlayers, maxPlayers, condition, requires, cooldown);
    }

    /** This schedule, at different times. */
    public @NotNull Schedule withTimes(@NotNull List<LocalTime> times) {
        return new Schedule(id, name, target, enabled, days, times, every, from, to,
                minPlayers, maxPlayers, condition, requires, cooldown);
    }

    /** This schedule, needing a different number of players. */
    public @NotNull Schedule withMinPlayers(int minPlayers) {
        return new Schedule(id, name, target, enabled, days, times, every, from, to,
                minPlayers, maxPlayers, condition, requires, cooldown);
    }

    /** This schedule, with a different condition. */
    public @NotNull Schedule withCondition(@Nullable String condition) {
        return new Schedule(id, name, target, enabled, days, times, every, from, to,
                minPlayers, maxPlayers, condition, requires, cooldown);
    }

    /** This schedule, with different named gates. */
    public @NotNull Schedule withRequires(@NotNull List<String> requires) {
        return new Schedule(id, name, target, enabled, days, times, every, from, to,
                minPlayers, maxPlayers, condition, requires, cooldown);
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Reads a comma-separated list of times, ignoring what it cannot read.
     *
     * <p>What a form field holds: {@code "20:00, 22:30"}.
     *
     * @param written the text, possibly {@code null}
     * @return the times, sorted and deduplicated
     */
    public static @NotNull List<LocalTime> parseTimes(@Nullable String written) {
        if (written == null || written.isBlank()) {
            return List.of();
        }
        Set<LocalTime> parsed = new LinkedHashSet<>();
        for (String part : written.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                parsed.add(LocalTime.parse(trimmed, TIME));
            } catch (RuntimeException unreadable) {
                // Dropped rather than thrown: one mistyped time in a list of
                // four must not cost the other three.
            }
        }
        return parsed.stream().sorted().toList();
    }

    /**
     * Reads a comma-separated list of days, ignoring what it cannot read.
     *
     * <p>Accepts full names and three-letter abbreviations, in any case, and
     * treats {@code *} as "every day".
     *
     * @param written the text, possibly {@code null}
     * @return the days, empty for every day
     */
    public static @NotNull Set<DayOfWeek> parseDays(@Nullable String written) {
        if (written == null || written.isBlank() || written.trim().equals("*")) {
            return Set.of();
        }
        Set<DayOfWeek> parsed = new LinkedHashSet<>();
        for (String part : written.split(",")) {
            DayOfWeek day = parseDay(part.trim());
            if (day != null) {
                parsed.add(day);
            }
        }
        return parsed.isEmpty() ? Set.of() : Set.copyOf(parsed);
    }

    /**
     * Reads one day name.
     *
     * @param written the name, full or three-letter
     * @return the day, or {@code null} when it is not one
     */
    public static @Nullable DayOfWeek parseDay(@Nullable String written) {
        if (written == null || written.isBlank()) {
            return null;
        }
        String upper = written.trim().toUpperCase(Locale.ROOT);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.name().equals(upper) || day.name().startsWith(upper) && upper.length() >= 3) {
                return day;
            }
        }
        return null;
    }

    /**
     * Writes a duration the way {@code every} and {@code cooldown} are stored.
     *
     * @param duration the duration
     * @return {@code "2h30m"}, never blank
     */
    public static @NotNull String writeDuration(@NotNull Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds == 0) {
            return "0s";
        }
        StringBuilder written = new StringBuilder();
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long rest = seconds % 60;
        if (days > 0) {
            written.append(days).append('d');
        }
        if (hours > 0) {
            written.append(hours).append('h');
        }
        if (minutes > 0) {
            written.append(minutes).append('m');
        }
        if (rest > 0) {
            written.append(rest).append('s');
        }
        return written.toString();
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static @Nullable Duration positive(@Nullable Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative() ? null : duration;
    }
}
