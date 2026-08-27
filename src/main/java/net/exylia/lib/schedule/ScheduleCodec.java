package net.exylia.lib.schedule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.lib.input.InputParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Schedule lists as they are stored, and as they used to be written by hand.
 *
 * <pre>{@code
 * String stored = ScheduleCodec.encode(schedules);   // into the column
 * List<Schedule> schedules = ScheduleCodec.decode(stored);
 * }</pre>
 *
 * <p>A JSON array, the same shape every other list this library stores uses, so
 * a schedule list lives in a {@code TEXT} column next to the rewards and the
 * commands of the same row rather than in a file of its own:
 *
 * <pre>{@code
 * [{"id":"…","name":"Friday night","target":"koth_desert","days":["FRIDAY"],
 *   "times":["20:00"],"minPlayers":10,"requires":["event-inactive"]}]
 * }</pre>
 *
 * <p>Only what carries meaning is written. A schedule that fires every day at
 * one time with no gates is four keys, and an empty list stores as {@code null}
 * rather than {@code []} — the same rule the reward and command codecs follow,
 * for the same reason: a column that suddenly held {@code []} would read back
 * the same and would not compare the same.
 *
 * <h2>The YAML shape is read too</h2>
 * Every plugin that had a scheduler before this module wrote the same block by
 * hand — {@code name}, {@code time}, {@code days}, {@code min-players} — into
 * either a config file or a settings map. {@link #fromMapList} reads it, so
 * migrating is a read through this class rather than an admin retyping their
 * timetable.
 *
 * @since 1.70.0
 */
public final class ScheduleCodec {

    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String TARGET = "target";
    private static final String ENABLED = "enabled";
    private static final String DAYS = "days";
    private static final String TIMES = "times";
    private static final String EVERY = "every";
    private static final String FROM = "from";
    private static final String TO = "to";
    private static final String MIN_PLAYERS = "minPlayers";
    private static final String MAX_PLAYERS = "maxPlayers";
    private static final String CONDITION = "condition";
    private static final String REQUIRES = "requires";
    private static final String COOLDOWN = "cooldown";

    private ScheduleCodec() {
        throw new AssertionError("No instances.");
    }

    // --------------------------------------------------------------- encoding

    /**
     * Writes a schedule list the way a column expects it.
     *
     * @param schedules the schedules
     * @return the JSON array, or {@code null} for an empty list
     */
    public static @Nullable String encode(@NotNull List<Schedule> schedules) {
        if (schedules.isEmpty()) {
            return null;
        }
        JsonArray array = new JsonArray();
        for (Schedule schedule : schedules) {
            array.add(toJson(schedule));
        }
        return array.toString();
    }

    private static JsonObject toJson(Schedule schedule) {
        JsonObject json = new JsonObject();
        json.addProperty(ID, schedule.id());
        addIfPresent(json, NAME, schedule.name());
        addIfPresent(json, TARGET, schedule.target());
        // Written only when it is off: on is what a row means by existing, and
        // the key would otherwise be on every row of every server.
        if (!schedule.enabled()) {
            json.addProperty(ENABLED, false);
        }
        if (!schedule.days().isEmpty()) {
            JsonArray days = new JsonArray();
            for (DayOfWeek day : DayOfWeek.values()) {
                if (schedule.days().contains(day)) {
                    days.add(day.name());
                }
            }
            json.add(DAYS, days);
        }
        if (!schedule.times().isEmpty()) {
            JsonArray times = new JsonArray();
            for (LocalTime time : schedule.times()) {
                times.add(Schedule.TIME.format(time));
            }
            json.add(TIMES, times);
        }
        if (schedule.every() != null) {
            json.addProperty(EVERY, Schedule.writeDuration(schedule.every()));
        }
        if (schedule.from() != null) {
            json.addProperty(FROM, Schedule.TIME.format(schedule.from()));
        }
        if (schedule.to() != null) {
            json.addProperty(TO, Schedule.TIME.format(schedule.to()));
        }
        if (schedule.minPlayers() > 0) {
            json.addProperty(MIN_PLAYERS, schedule.minPlayers());
        }
        if (schedule.maxPlayers() > 0) {
            json.addProperty(MAX_PLAYERS, schedule.maxPlayers());
        }
        addIfPresent(json, CONDITION, schedule.condition());
        if (!schedule.requires().isEmpty()) {
            JsonArray requires = new JsonArray();
            schedule.requires().forEach(requires::add);
            json.add(REQUIRES, requires);
        }
        if (schedule.cooldown() != null) {
            json.addProperty(COOLDOWN, Schedule.writeDuration(schedule.cooldown()));
        }
        return json;
    }

    private static void addIfPresent(JsonObject json, String key, @Nullable String value) {
        if (value != null) {
            json.addProperty(key, value);
        }
    }

    // --------------------------------------------------------------- decoding

    /**
     * Reads a stored schedule list, ignoring whatever it cannot understand.
     *
     * @param stored the column value, possibly {@code null}
     * @return the schedules, never {@code null}
     */
    public static @NotNull List<Schedule> decode(@Nullable String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(stored);
        } catch (RuntimeException malformed) {
            return List.of();
        }
        if (!root.isJsonArray()) {
            return List.of();
        }
        List<Schedule> schedules = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (element.isJsonObject()) {
                schedules.add(fromJson(element.getAsJsonObject()));
            }
        }
        return List.copyOf(schedules);
    }

    private static Schedule fromJson(JsonObject json) {
        String id = string(json, ID);
        return new Schedule(
                id != null ? id : UUID.randomUUID().toString(),
                string(json, NAME),
                string(json, TARGET),
                flag(json, ENABLED),
                days(strings(json, DAYS)),
                times(strings(json, TIMES)),
                duration(string(json, EVERY)),
                time(string(json, FROM)),
                time(string(json, TO)),
                integer(json, MIN_PLAYERS),
                integer(json, MAX_PLAYERS),
                string(json, CONDITION),
                strings(json, REQUIRES),
                duration(string(json, COOLDOWN)));
    }

    // ------------------------------------------------------------ the old YAML

    /**
     * Reads the hand-written block form that came before this module.
     *
     * <pre>{@code
     * - name: 'Friday night'
     *   target: 'koth_desert'      # or the id of the config the block is under
     *   time: '20:00'              # or times: ['20:00', '22:30']
     *   days: [FRIDAY, SATURDAY]   # or ['*'] for every day
     *   min-players: 10            # also read as min-players-online
     * }</pre>
     *
     * <p>Both spellings of the player key are accepted because both are in
     * production: one plugin wrote {@code min-players} and another wrote
     * {@code min-players-online}, and an admin should not lose their timetable
     * over which of the two their plugin happened to pick.
     *
     * @param blocks        the raw list, possibly {@code null}
     * @param defaultTarget what a block with no {@code target} of its own fires
     * @return the schedules, never {@code null}
     */
    public static @NotNull List<Schedule> fromMapList(@Nullable List<? extends Map<?, ?>> blocks,
                                                      @Nullable String defaultTarget) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<Schedule> schedules = new ArrayList<>(blocks.size());
        for (Map<?, ?> block : blocks) {
            if (block == null) {
                continue;
            }
            List<String> written = new ArrayList<>(text(block.get("times")));
            written.addAll(text(block.get("time")));
            String target = single(block.get("target"));
            schedules.add(new Schedule(
                    UUID.randomUUID().toString(),
                    single(block.get("name")),
                    target != null ? target : defaultTarget,
                    !Boolean.FALSE.equals(block.get("enabled")),
                    days(text(block.get("days"))),
                    times(written),
                    duration(single(block.get("every"))),
                    time(single(block.get("from"))),
                    time(single(block.get("to"))),
                    count(block, "min-players", "min-players-online"),
                    count(block, "max-players", "max-players-online"),
                    single(block.get("condition")),
                    text(block.get("requires")),
                    duration(single(block.get("cooldown")))));
        }
        return List.copyOf(schedules);
    }

    private static int count(Map<?, ?> block, String key, String alias) {
        Object value = block.get(key);
        if (!(value instanceof Number)) {
            value = block.get(alias);
        }
        return value instanceof Number number ? number.intValue() : 0;
    }

    // ----------------------------------------------------------------- reading

    private static Set<DayOfWeek> days(List<String> written) {
        if (written.isEmpty()) {
            return Set.of();
        }
        Set<DayOfWeek> days = new LinkedHashSet<>();
        for (String one : written) {
            if ("*".equals(one.trim())) {
                return Set.of();
            }
            DayOfWeek day = Schedule.parseDay(one);
            if (day != null) {
                days.add(day);
            }
        }
        return days.isEmpty() ? Set.of() : Set.copyOf(days);
    }

    private static List<LocalTime> times(List<String> written) {
        List<LocalTime> times = new ArrayList<>(written.size());
        for (String one : written) {
            LocalTime time = time(one);
            if (time != null) {
                times.add(time);
            }
        }
        return times;
    }

    private static @Nullable LocalTime time(@Nullable String written) {
        if (written == null || written.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(written.trim(), Schedule.TIME);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Reads a duration the way every other Exylia field does.
     *
     * <p>Through the input module's own parser rather than a second one here,
     * so {@code 90s}, {@code 1h30m} and a bare {@code 90} mean in a schedule
     * exactly what they mean in the box that asked for them.
     */
    private static @Nullable Duration duration(@Nullable String written) {
        if (written == null || written.isBlank()) {
            return null;
        }
        return InputParser.duration().parse(written.trim()).value();
    }

    private static @Nullable String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || !element.isJsonPrimitive() ? null : element.getAsString();
    }

    private static boolean flag(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return true;
        }
        return element.getAsBoolean();
    }

    private static int integer(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static List<String> strings(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement one : element.getAsJsonArray()) {
            if (one.isJsonPrimitive()) {
                values.add(one.getAsString());
            }
        }
        return values;
    }

    private static List<String> text(@Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>(list.size());
            for (Object one : list) {
                if (one != null) {
                    values.add(one.toString());
                }
            }
            return values;
        }
        return List.of(value.toString());
    }

    private static @Nullable String single(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String written = value.toString().trim();
        return written.isEmpty() ? null : written;
    }
}
