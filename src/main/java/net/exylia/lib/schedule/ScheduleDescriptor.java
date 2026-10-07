package net.exylia.lib.schedule;

import net.exylia.lib.text.Phrases;
import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
 * <h2>Only what the mode needs</h2>
 * A form that asked for every field at once, each with a paragraph explaining
 * its syntax, read as a wall of text. The main form now shows the fields of one
 * mode (set times, or a repeating timer), a box per day instead of a list of
 * day names to type, and an example beside each label instead of above the
 * form. Player bounds, the cooldown, named checks and the custom condition live
 * in a second form opened on request, because most schedules use none of them.
 *
 * @since 1.70.0
 */
final class ScheduleDescriptor implements EditorDescriptor<Schedule> {

    /** The clipboard bucket schedules share, so one screen pastes into another. */
    static final String TYPE_KEY = "exylia:schedules";

    private static final String MODE_TIMES = "times";
    private static final String MODE_REPEAT = "repeat";
    private static final Duration DEFAULT_EVERY = Duration.ofHours(1);

    private static final FormKey<String> NAME = FormKey.text("name");
    private static final FormKey<String> TARGET = FormKey.text("target");
    private static final FormKey<String> MODE = FormKey.text("mode");
    private static final FormKey<String> TIMES = FormKey.text("times");
    private static final FormKey<Duration> EVERY = FormKey.duration("every");
    private static final FormKey<String> FROM = FormKey.text("from");
    private static final FormKey<String> TO = FormKey.text("to");
    private static final FormKey<Boolean> ENABLED = FormKey.flag("enabled");
    private static final FormKey<Boolean> ADVANCED = FormKey.flag("advanced");
    private static final FormKey<Long> MIN_PLAYERS = FormKey.integer("minPlayers");
    private static final FormKey<Long> MAX_PLAYERS = FormKey.integer("maxPlayers");
    private static final FormKey<Duration> COOLDOWN = FormKey.duration("cooldown");
    private static final FormKey<String> CONDITION = FormKey.text("condition");
    private static final Map<DayOfWeek, FormKey<Boolean>> DAY_KEYS = new EnumMap<>(DayOfWeek.class);

    static {
        for (DayOfWeek day : DayOfWeek.values()) {
            DAY_KEYS.put(day, FormKey.flag("day_" + day.name().toLowerCase(Locale.ROOT)));
        }
    }

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
        lore.add(Phrases.tr("{secondary}When:"));
        lore.add(Phrases.tr(" {letters_black}▎ {letters}Time {letters_black}» {info}{0}", entry.describeTrigger()));
        lore.add(Phrases.tr(" {letters_black}▎ {letters}Days {letters_black}» {info}{0}", entry.describeDays()));

        List<String> gates = entry.describeGates();
        if (!gates.isEmpty()) {
            lore.add("");
            lore.add(Phrases.tr("{secondary}Only if:"));
            for (String gate : gates) {
                lore.add(" {letters_black}▎ {letters}" + gate);
            }
        }

        if (!entry.enabled()) {
            lore.add("");
            lore.add(Phrases.tr("{error}✘ Turned off"));
        } else if (!entry.isRunnable()) {
            lore.add("");
            lore.add(Phrases.tr("{warning}➥ Set a time before it can run"));
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
        return edit(viewer, entry, entry.every() != null ? MODE_REPEAT : MODE_TIMES);
    }

    /**
     * The main form, showing only the fields of one mode.
     *
     * <p>A dialog cannot hide a field when another one changes, so picking the
     * other mode and submitting reopens the form in that mode, carrying over
     * everything already typed. Ticking the advanced box opens the second form
     * once this one is accepted.
     */
    private CompletionStage<Optional<Schedule>> edit(Player viewer, Schedule entry, String mode) {
        EditorForm form = EditorForm.of(plugin, viewer, Phrases.tr("{primary}&lEDIT SCHEDULE"))
                .text(NAME, Phrases.tr("Name {muted}(optional)"), entry.name());

        // Only asked for where the screen does not already know it. A schedules
        // screen opened from one event's setup is about that event, and a field
        // holding its id is a field an admin can only get wrong.
        if (defaultTarget == null) {
            form.text(TARGET, Phrases.tr("Starts {muted}(id)"), entry.target());
        }

        form.choice(MODE, Phrases.tr("When it runs"), mode, List.of(
                new FormField.Option(MODE_TIMES, Phrases.tr("At set times")),
                new FormField.Option(MODE_REPEAT, Phrases.tr("On a repeating timer"))));

        if (MODE_REPEAT.equals(mode)) {
            form.field(EVERY, FormField.duration(EVERY, Phrases.tr("Every {muted}(e.g. 2h, 90m)"))
                            .defaultValue(entry.every() == null ? DEFAULT_EVERY : entry.every()))
                    .text(FROM, Phrases.tr("From {muted}(blank = 00:00)"), writeTime(entry.from()))
                    .text(TO, Phrases.tr("Until {muted}(blank = 23:59)"), writeTime(entry.to()));
        } else {
            form.text(TIMES, Phrases.tr("Times {muted}(24h, e.g. 20:00, 22:30)"), writeTimes(entry.times()));
        }

        // Every box ticked and none ticked both mean every day, so a new
        // schedule starts with all seven ticked rather than an empty week
        // that silently means the same.
        for (DayOfWeek day : DayOfWeek.values()) {
            form.flag(DAY_KEYS.get(day), dayName(day), entry.days().isEmpty() || entry.days().contains(day));
        }

        form.flag(ENABLED, Phrases.tr("Enabled"), entry.enabled())
                .flag(ADVANCED, Phrases.tr("Edit conditions next {muted}(players, cooldown, checks)"), false);

        return form.ask(values -> new Step(build(entry, values, mode),
                        values.getOr(MODE, mode), values.getOr(ADVANCED, Boolean.FALSE)))
                .thenCompose(answer -> {
                    if (answer.isEmpty()) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    Step step = answer.get();
                    if (!step.mode().equals(mode)) {
                        return edit(viewer, step.schedule(), step.mode());
                    }
                    Schedule built = clean(step.schedule(), mode);
                    return step.advanced()
                            ? advanced(viewer, built)
                            : CompletableFuture.completedFuture(Optional.of(built));
                });
    }

    /**
     * The second form: everything that decides whether a due fire is kept.
     *
     * <p>Cancelling it keeps what the first form changed. The admin accepted
     * those already, and losing them to a closed window would be a surprise.
     */
    private CompletionStage<Optional<Schedule>> advanced(Player viewer, Schedule entry) {
        List<String> gates = conditionNames == null ? List.of()
                : conditionNames.get().stream().sorted().toList();

        EditorForm form = EditorForm.of(plugin, viewer, Phrases.tr("{primary}&lSCHEDULE CONDITIONS"))
                .integer(MIN_PLAYERS, Phrases.tr("Minimum players online"), entry.minPlayers())
                .integer(MAX_PLAYERS, Phrases.tr("Maximum players online {muted}(0 = no limit)"), entry.maxPlayers())
                .field(COOLDOWN, FormField.duration(COOLDOWN, Phrases.tr("Wait between runs {muted}(e.g. 1h)"))
                        .defaultValue(entry.cooldown() == null ? Duration.ZERO : entry.cooldown())
                        .optional());
        for (int index = 0; index < gates.size(); index++) {
            form.flag(gateKey(index), Phrases.tr("Only if {0}", describeGate(gates.get(index))),
                    entry.requires().contains(gates.get(index)));
        }
        form.text(CONDITION, Phrases.tr("Custom condition {muted}(e.g. %server_tps% >= 18)"), entry.condition(), 2);

        return form.ask(values -> withConditions(entry, values, gates))
                .thenApply(answer -> answer.or(() -> Optional.of(entry)));
    }

    /**
     * Turns the main form's answers back into a schedule.
     *
     * <p>Fields of the other mode are not on the form and keep their old
     * values until {@link #clean} drops them, so switching modes and back
     * loses nothing typed.
     */
    private Schedule build(Schedule entry, FormValues values, String mode) {
        boolean repeat = MODE_REPEAT.equals(mode);
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (values.getOr(DAY_KEYS.get(day), Boolean.TRUE)) {
                days.add(day);
            }
        }
        String target = defaultTarget != null ? defaultTarget : values.getOr(TARGET, "");
        return new Schedule(
                entry.id(),
                values.getOr(NAME, ""),
                target,
                values.getOr(ENABLED, Boolean.TRUE),
                days.size() == DayOfWeek.values().length ? Set.of() : days,
                repeat ? entry.times() : Schedule.parseTimes(values.getOr(TIMES, "")),
                repeat ? values.getOr(EVERY, Duration.ZERO) : entry.every(),
                repeat ? time(values.getOr(FROM, "")) : entry.from(),
                repeat ? time(values.getOr(TO, "")) : entry.to(),
                entry.minPlayers(),
                entry.maxPlayers(),
                entry.condition(),
                entry.requires(),
                entry.cooldown());
    }

    /** Drops the fields of the mode that was not picked. */
    private static Schedule clean(Schedule entry, String mode) {
        boolean repeat = MODE_REPEAT.equals(mode);
        return new Schedule(entry.id(), entry.name(), entry.target(), entry.enabled(), entry.days(),
                repeat ? List.of() : entry.times(),
                repeat ? entry.every() : null,
                repeat ? entry.from() : null,
                repeat ? entry.to() : null,
                entry.minPlayers(), entry.maxPlayers(), entry.condition(), entry.requires(), entry.cooldown());
    }

    private static Schedule withConditions(Schedule entry, FormValues values, List<String> gates) {
        // A gate the plugin no longer registers is kept rather than dropped: it
        // has no box to untick, and it still fails closed the way it did.
        List<String> requires = new ArrayList<>();
        for (String kept : entry.requires()) {
            if (!gates.contains(kept)) {
                requires.add(kept);
            }
        }
        for (int index = 0; index < gates.size(); index++) {
            if (values.getOr(gateKey(index), Boolean.FALSE)) {
                requires.add(gates.get(index));
            }
        }
        return new Schedule(entry.id(), entry.name(), entry.target(), entry.enabled(), entry.days(),
                entry.times(), entry.every(), entry.from(), entry.to(),
                (int) Math.max(0L, values.getOr(MIN_PLAYERS, 0L)),
                (int) Math.max(0L, values.getOr(MAX_PLAYERS, 0L)),
                values.getOr(CONDITION, ""),
                requires,
                values.getOr(COOLDOWN, Duration.ZERO));
    }

    private static FormKey<Boolean> gateKey(int index) {
        return FormKey.flag("gate" + index);
    }

    /** {@code event-inactive} as {@code event inactive}. */
    private static String describeGate(String name) {
        return name.replace('-', ' ').replace('_', ' ');
    }

    private static String dayName(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> Phrases.tr("Monday");
            case TUESDAY -> Phrases.tr("Tuesday");
            case WEDNESDAY -> Phrases.tr("Wednesday");
            case THURSDAY -> Phrases.tr("Thursday");
            case FRIDAY -> Phrases.tr("Friday");
            case SATURDAY -> Phrases.tr("Saturday");
            case SUNDAY -> Phrases.tr("Sunday");
        };
    }

    /** What the main form answered: the schedule, and where to go next. */
    private record Step(Schedule schedule, String mode, boolean advanced) {
    }

    private static String writeTimes(List<LocalTime> times) {
        List<String> written = new ArrayList<>(times.size());
        for (LocalTime time : times) {
            written.add(Schedule.TIME.format(time));
        }
        return String.join(", ", written);
    }

    private static String writeTime(@Nullable LocalTime time) {
        return time == null ? "" : Schedule.TIME.format(time);
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
}
