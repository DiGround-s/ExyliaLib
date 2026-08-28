package net.exylia.lib.schedule;

import net.exylia.lib.schedule.internal.NextFire;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two things a timetable has to get right: when it says something happens,
 * and that a file written before this module still says the same thing.
 */
class ScheduleTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Madrid");

    private static ZonedDateTime at(String local) {
        return LocalDateTime.parse(local).atZone(ZONE);
    }

    private static ZonedDateTime next(Schedule schedule, String from) {
        Optional<ZonedDateTime> when = NextFire.after(schedule, at(from));
        assertTrue(when.isPresent(), "expected a next fire");
        return when.get();
    }

    @Test
    @DisplayName("a fixed time later today is today")
    void fixedTimeToday() {
        Schedule schedule = Schedule.at("koth", LocalTime.of(20, 0));
        assertEquals(at("2026-08-27T20:00"), next(schedule, "2026-08-27T13:00"));
    }

    @Test
    @DisplayName("a fixed time already past is tomorrow")
    void fixedTimeTomorrow() {
        Schedule schedule = Schedule.at("koth", LocalTime.of(20, 0));
        assertEquals(at("2026-08-28T20:00"), next(schedule, "2026-08-27T20:00"));
    }

    @Test
    @DisplayName("the earliest of several times wins")
    void earliestTimeWins() {
        Schedule schedule = Schedule.at("koth", LocalTime.of(22, 30), LocalTime.of(20, 0));
        assertEquals(at("2026-08-27T22:30"), next(schedule, "2026-08-27T21:00"));
    }

    @Test
    @DisplayName("days skip forward to the next one that is listed")
    void daysSkipForward() {
        // Thursday the 27th; the schedule only runs at the weekend.
        Schedule schedule = Schedule.at("koth", LocalTime.of(20, 0))
                .withDays(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        assertEquals(at("2026-08-29T20:00"), next(schedule, "2026-08-27T13:00"));
    }

    @Test
    @DisplayName("an interval stays anchored to its window rather than to now")
    void intervalAnchoredToWindow() {
        Schedule schedule = new Schedule("id", null, "koth", true, Set.of(), List.of(),
                Duration.ofHours(2), LocalTime.of(10, 0), LocalTime.of(23, 0),
                0, 0, null, List.of(), null);
        // 10:00, 12:00, 14:00 … so a reload at 13:07 does not move it to 15:07.
        assertEquals(at("2026-08-27T14:00"), next(schedule, "2026-08-27T13:07"));
    }

    @Test
    @DisplayName("an interval past its window opens again the next day")
    void intervalRollsOver() {
        Schedule schedule = new Schedule("id", null, "koth", true, Set.of(), List.of(),
                Duration.ofHours(2), LocalTime.of(10, 0), LocalTime.of(23, 0),
                0, 0, null, List.of(), null);
        assertEquals(at("2026-08-28T10:00"), next(schedule, "2026-08-27T23:30"));
    }

    @Test
    @DisplayName("a schedule with no time never fires")
    void noTimeNeverFires() {
        Schedule schedule = new Schedule("id", null, "koth", true, Set.of(), List.of(),
                null, null, null, 0, 0, null, List.of(), null);
        assertFalse(schedule.isRunnable());
        assertTrue(NextFire.after(schedule, at("2026-08-27T13:00")).isEmpty());
    }

    @Test
    @DisplayName("a window written backwards fires nothing rather than everything")
    void backwardsWindow() {
        Schedule schedule = new Schedule("id", null, "koth", true, Set.of(), List.of(),
                Duration.ofHours(1), LocalTime.of(23, 0), LocalTime.of(2, 0),
                0, 0, null, List.of(), null);
        assertTrue(NextFire.after(schedule, at("2026-08-27T13:00")).isEmpty());
    }

    @Test
    @DisplayName("a schedule survives a round trip through the codec")
    void codecRoundTrip() {
        Schedule schedule = new Schedule("id", "Friday night", "koth_desert", true,
                Set.of(DayOfWeek.FRIDAY), List.of(LocalTime.of(20, 0), LocalTime.of(22, 30)),
                null, null, null, 10, 40, "%server_tps% >= 18",
                List.of("event-inactive"), Duration.ofMinutes(30));

        List<Schedule> back = ScheduleCodec.decode(ScheduleCodec.encode(List.of(schedule)));

        assertEquals(1, back.size());
        assertEquals(schedule, back.get(0));
    }

    @Test
    @DisplayName("an empty list stores as nothing, not as an empty array")
    void emptyStoresAsNull() {
        assertEquals(null, ScheduleCodec.encode(List.of()));
        assertTrue(ScheduleCodec.decode(null).isEmpty());
        assertTrue(ScheduleCodec.decode("not json").isEmpty());
    }

    @Test
    @DisplayName("the hand-written YAML block still reads the same")
    void readsLegacyBlocks() {
        List<Map<String, Object>> blocks = List.of(
                Map.of("name", "Nightly", "time", "20:00",
                        "days", List.of("FRIDAY", "SATURDAY"), "min-players", 10),
                // The other plugin's spelling of the same key, and its wildcard.
                Map.of("target", "RANDOM-EVENT", "time", "18:30",
                        "days", List.of("*"), "min-players-online", 4));

        List<Schedule> schedules = ScheduleCodec.fromMapList(blocks, "koth_desert");

        assertEquals(2, schedules.size());
        Schedule first = schedules.get(0);
        assertEquals("Nightly", first.name());
        assertEquals("koth_desert", first.target());
        assertEquals(List.of(LocalTime.of(20, 0)), first.times());
        assertEquals(Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), first.days());
        assertEquals(10, first.minPlayers());

        Schedule second = schedules.get(1);
        assertEquals("RANDOM-EVENT", second.target());
        assertTrue(second.days().isEmpty(), "* means every day");
        assertEquals(4, second.minPlayers());
    }

    @Test
    @DisplayName("blank fields normalise to nothing rather than to empty text")
    void blanksNormalise() {
        Schedule schedule = new Schedule("id", "  ", "  ", true, Set.of(),
                List.of(LocalTime.of(20, 0)), Duration.ZERO, null, null, -5, -5, "   ",
                List.of("", "  event-inactive  ", "event-inactive"), Duration.ZERO);

        assertEquals(null, schedule.name());
        assertEquals(null, schedule.target());
        assertEquals(null, schedule.condition());
        assertEquals(null, schedule.every());
        assertEquals(null, schedule.cooldown());
        assertEquals(0, schedule.minPlayers());
        assertEquals(List.of("event-inactive"), schedule.requires());
    }

    @Test
    @DisplayName("player bounds read the way an admin writes them")
    void playerBounds() {
        Schedule any = Schedule.at("koth", LocalTime.NOON);
        assertTrue(any.allowsPlayerCount(0));

        Schedule busy = any.withMinPlayers(10);
        assertFalse(busy.allowsPlayerCount(9));
        assertTrue(busy.allowsPlayerCount(10));
    }

    @Test
    @DisplayName("times and days are read from the text a form holds")
    void parsesFormText() {
        assertEquals(List.of(LocalTime.of(20, 0), LocalTime.of(22, 30)),
                Schedule.parseTimes("22:30, 20:00, nonsense"));
        assertEquals(Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                Schedule.parseDays("mon, FRI"));
        assertTrue(Schedule.parseDays("*").isEmpty());
    }

    @Test
    @DisplayName("every field the editor asks for can be sent to a client")
    void editorFieldsAreSendable() throws Exception {
        // The descriptor holds its form keys as static finals, so loading the
        // class builds all of them. Two of them used to be "min-players" and
        // "max-players", which a dialog cannot carry: the client rejected the
        // input name and failed the decode of the whole packet, disconnecting
        // whoever opened the schedule editor.
        Class.forName("net.exylia.lib.schedule.ScheduleDescriptor");
    }

    @Test
    @DisplayName("a duration is written the way it is read")
    void writesDurations() {
        assertEquals("2h30m", Schedule.writeDuration(Duration.ofMinutes(150)));
        assertEquals("1d", Schedule.writeDuration(Duration.ofDays(1)));
        assertEquals("45s", Schedule.writeDuration(Duration.ofSeconds(45)));
    }
}
