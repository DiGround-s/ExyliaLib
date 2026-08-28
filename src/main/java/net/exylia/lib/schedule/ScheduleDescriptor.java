package net.exylia.lib.schedule;

import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * How a schedule draws and edits itself on screen.
 *
 * <p>Handed to the list editor by {@link PluginSchedules#editor}.
 *
 * <h2>One form, not a screen of toggles</h2>
 * The screen this replaces drew seven coloured wool blocks for the days of the
 * week, an anvil for the time and a pair of arrows for the player count, and
 * every one of them was a click that reopened the menu. This is one dialog with
 * every field already filled in, which is the same edit in one trip.
 *
 * @since 1.70.0
 */
final class ScheduleDescriptor implements EditorDescriptor<Schedule> {

    /** The clipboard bucket schedules share, so one screen pastes into another. */
    static final String TYPE_KEY = "exylia:schedules";

    private static final FormKey<String> NAME = FormKey.text("name");
    private static final FormKey<String> TARGET = FormKey.text("target");
    private static final FormKey<String> TIMES = FormKey.text("times");
    private static final FormKey<String> DAYS = FormKey.text("days");
    private static final FormKey<Duration> EVERY = FormKey.duration("every");
    private static final FormKey<String> WINDOW = FormKey.text("window");
    private static final FormKey<Long> MIN_PLAYERS = FormKey.integer("minPlayers");
    private static final FormKey<Long> MAX_PLAYERS = FormKey.integer("maxPlayers");
    private static final FormKey<Duration> COOLDOWN = FormKey.duration("cooldown");
    private static final FormKey<String> CONDITION = FormKey.text("condition");
    private static final FormKey<String> REQUIRES = FormKey.text("requires");
    private static final FormKey<Boolean> ENABLED = FormKey.flag("enabled");

    private final Plugin plugin;
    private final String defaultTarget;
    private final Supplier<Set<String>> conditionNames;

    ScheduleDescriptor(Plugin plugin, @Nullable String defaultTarget,
                       Supplier<Set<String>> conditionNames) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.defaultTarget = defaultTarget;
        this.conditionNames = conditionNames;
    }

    @Override
    public @NotNull String label(@NotNull Schedule entry) {
        return "{primary}&l" + entry.displayName().toUpperCase(Locale.ROOT);
    }

    @Override
    public @NotNull String icon(@NotNull Schedule entry) {
        if (!entry.isRunnable()) {
            return "BARRIER";
        }
        return entry.enabled() ? "CLOCK" : "GRAY_DYE";
    }

    @Override
    public @NotNull List<String> lore(@NotNull Schedule entry) {
        List<String> lore = new ArrayList<>();
        lore.add("{secondary}When:");
        lore.add(" {letters_black}▎ {letters}Time {letters_black}» {info}" + entry.describeTrigger());
        lore.add(" {letters_black}▎ {letters}Days {letters_black}» {info}" + entry.describeDays());

        List<String> gates = entry.describeGates();
        if (!gates.isEmpty()) {
            lore.add("");
            lore.add("{secondary}Only if:");
            for (String gate : gates) {
                lore.add(" {letters_black}▎ {letters}" + gate);
            }
        }

        if (!entry.enabled()) {
            lore.add("");
            lore.add("{error}✘ Turned off");
        } else if (!entry.isRunnable()) {
            lore.add("");
            lore.add("{warning}➥ Set a time before it can run");
        }
        return List.copyOf(lore);
    }

    @Override
    public @NotNull Schedule create() {
        return Schedule.blank(defaultTarget);
    }

    @Override
    public @NotNull Schedule copy(@NotNull Schedule entry) {
        return entry.copy();
    }

    @Override
    public @NotNull String typeKey() {
        return TYPE_KEY;
    }

    @Override
    public boolean isComplete(@NotNull Schedule entry) {
        return entry.isRunnable();
    }

    @Override
    public @NotNull CompletionStage<Optional<Schedule>> edit(@NotNull Player viewer,
                                                             @NotNull Schedule entry) {
        EditorForm form = EditorForm.of(plugin, viewer, "{primary}&lEDIT SCHEDULE")
                .text(NAME, "Name", entry.name(), 2)
                .hint("What this line is called in the list. Blank shows the times.");

        // Only asked for where the screen does not already know it. A schedules
        // screen opened from one event's setup is about that event, and a field
        // holding its id is a field an admin can only get wrong.
        if (defaultTarget == null) {
            form.text(TARGET, "Starts", entry.target(), 2)
                    .hint("The id of the thing this starts.");
        }

        form.text(TIMES, "Times", writeTimes(entry.times()))
                .hint("24-hour, comma separated. 20:00, 22:30")
                .text(DAYS, "Days", writeDays(entry))
                .hint("Blank or * for every day. MON, FRI, SAT")
                .field(EVERY, net.exylia.lib.input.FormField.duration(EVERY, "Repeat every")
                        .defaultValue(entry.every() == null ? Duration.ZERO : entry.every())
                        .optional())
                .hint("Set this to repeat instead of using fixed times. 2h, 90m")
                .text(WINDOW, "Repeat between", writeWindow(entry))
                .hint("Only used when repeating. 10:00-23:00")
                .integer(MIN_PLAYERS, "Minimum players online", entry.minPlayers())
                .integer(MAX_PLAYERS, "Maximum players online (0 = no limit)", entry.maxPlayers())
                .field(COOLDOWN, net.exylia.lib.input.FormField.duration(COOLDOWN, "Wait at least")
                        .defaultValue(entry.cooldown() == null ? Duration.ZERO : entry.cooldown())
                        .optional())
                .hint("The shortest gap between two runs of this line.")
                .text(CONDITION, "Condition", entry.condition(), 2)
                .hint("A comparison, such as %server_tps% >= 18. Blank for none.")
                .text(REQUIRES, "Requires", String.join(", ", entry.requires()), 2)
                .hint(describeConditions())
                .flag(ENABLED, "Enabled", entry.enabled());

        return form.ask(values -> build(entry, values));
    }

    /**
     * Turns the answers back into a schedule.
     *
     * <p>Every read goes through {@code getOr}: a field left blank is absent
     * rather than empty, and a schedule with no condition is the normal case
     * rather than an error.
     */
    private Schedule build(Schedule entry, FormValues values) {
        LocalTime[] window = parseWindow(values.getOr(WINDOW, ""));
        String target = defaultTarget != null ? defaultTarget : values.getOr(TARGET, "");
        return new Schedule(
                entry.id(),
                values.getOr(NAME, ""),
                target,
                values.getOr(ENABLED, Boolean.TRUE),
                Schedule.parseDays(values.getOr(DAYS, "")),
                Schedule.parseTimes(values.getOr(TIMES, "")),
                values.getOr(EVERY, Duration.ZERO),
                window[0],
                window[1],
                (int) Math.max(0L, values.getOr(MIN_PLAYERS, 0L)),
                (int) Math.max(0L, values.getOr(MAX_PLAYERS, 0L)),
                values.getOr(CONDITION, ""),
                splitNames(values.getOr(REQUIRES, "")),
                values.getOr(COOLDOWN, Duration.ZERO));
    }

    private String describeConditions() {
        Set<String> names = conditionNames == null ? Set.of() : conditionNames.get();
        if (names.isEmpty()) {
            return "Named checks this plugin offers. It offers none.";
        }
        return "Comma separated. Available: " + String.join(", ", names.stream().sorted().toList());
    }

    private static String writeTimes(List<LocalTime> times) {
        List<String> written = new ArrayList<>(times.size());
        for (LocalTime time : times) {
            written.add(Schedule.TIME.format(time));
        }
        return String.join(", ", written);
    }

    private static String writeDays(Schedule entry) {
        if (entry.days().isEmpty()) {
            return "";
        }
        List<String> written = new ArrayList<>();
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            if (entry.days().contains(day)) {
                written.add(day.name());
            }
        }
        return String.join(", ", written);
    }

    private static String writeWindow(Schedule entry) {
        if (entry.from() == null && entry.to() == null) {
            return "";
        }
        return Schedule.TIME.format(entry.windowStart()) + "-" + Schedule.TIME.format(entry.windowEnd());
    }

    /**
     * Reads {@code 10:00-23:00} into its two ends.
     *
     * <p>Either end may be missing, and an unreadable window is no window at
     * all rather than half of one: a repeat that ran from ten o'clock to a time
     * nobody could read would look like it was ignoring the field.
     */
    private static LocalTime[] parseWindow(@Nullable String written) {
        if (written == null || written.isBlank()) {
            return new LocalTime[] {null, null};
        }
        String[] ends = written.split("-", 2);
        LocalTime from = ends.length > 0 ? time(ends[0]) : null;
        LocalTime to = ends.length > 1 ? time(ends[1]) : null;
        return new LocalTime[] {from, to};
    }

    private static @Nullable LocalTime time(String written) {
        String trimmed = written.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(trimmed, Schedule.TIME);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static List<String> splitNames(@Nullable String written) {
        if (written == null || written.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String part : written.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return List.copyOf(names);
    }
}
