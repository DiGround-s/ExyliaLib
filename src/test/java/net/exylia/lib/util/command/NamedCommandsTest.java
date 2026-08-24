package net.exylia.lib.util.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Command lists written by ExyliaCommons, read unchanged.
 *
 * <p>The JSON here is the shape a bare {@code new Gson().toJson(...)} over the
 * old Lombok bean produces, because that is what the stored columns hold.
 */
class NamedCommandsTest {

    @Test
    @DisplayName("a list stored by commons reads back whole")
    void legacy() {
        List<NamedCommand> commands = NamedCommands.decode("""
                [{"id":"a","name":"Welcome kit","command":"give %player_name% bread 3"},\
                {"id":"b","command":"say hi"}]""");

        assertEquals(2, commands.size());
        assertEquals("Welcome kit", commands.get(0).name());
        assertEquals("give %player_name% bread 3", commands.get(0).command());
        assertNull(commands.get(1).name());
        assertEquals("say hi", commands.get(1).displayName(),
                "A command nobody named reads as the command");
    }

    @Test
    @DisplayName("a null field is absent, not null, exactly as Gson left it")
    void omitsNulls() {
        String written = NamedCommands.encode(List.of(
                new NamedCommand("a", null, "say hi")));

        assertEquals("[{\"id\":\"a\",\"command\":\"say hi\"}]", written);
        assertFalse(written.contains("name"));
    }

    @Test
    @DisplayName("an empty list stores as NULL, not as []")
    void emptyIsNull() {
        assertNull(NamedCommands.encode(List.of()));
    }

    @Test
    @DisplayName("a stored list survives a round trip, ids included")
    void roundTrip() {
        List<NamedCommand> written = List.of(
                new NamedCommand("a", "Welcome kit", "give %player_name% bread 3"),
                new NamedCommand("b", null, "say hi"));

        assertEquals(written, NamedCommands.decode(NamedCommands.encode(written)));
    }

    @Test
    @DisplayName("a row that is not JSON costs the list, not an exception")
    void malformed() {
        assertTrue(NamedCommands.decode("{not json").isEmpty());
        assertTrue(NamedCommands.decode(null).isEmpty());
        assertTrue(NamedCommands.decode("  ").isEmpty());
    }

    @Test
    @DisplayName("the plain string list that came before names still reads")
    void fromBodies() {
        List<NamedCommand> commands = NamedCommands.fromBodies(List.of("say hi", "say bye"));

        assertEquals(2, commands.size());
        assertNull(commands.get(0).name());
        assertEquals("say hi", commands.get(0).command());
        assertTrue(NamedCommands.fromBodies(null).isEmpty());
    }

    @Test
    @DisplayName("only the rows that would run something are handed to a runner")
    void bodiesSkipsTheUnfinished() {
        List<NamedCommand> commands = List.of(
                NamedCommand.of("Done", "say hi"),
                NamedCommand.of("Half configured", null),
                NamedCommand.of("Blank", "   "));

        assertEquals(List.of("say hi"), NamedCommand.bodies(commands));
    }

    @Test
    @DisplayName("a copy is a different row carrying the same thing")
    void copyIsANewRow() {
        NamedCommand original = NamedCommand.of("Welcome", "say hi");
        NamedCommand copy = original.copy();

        assertEquals(original.name(), copy.name());
        assertEquals(original.command(), copy.command());
        assertFalse(original.id().equals(copy.id()),
                "Two rows sharing an id are one row as far as an editor is concerned");
    }

    @Test
    @DisplayName("an unfinished row says so rather than reading as blank")
    void unfinished() {
        assertEquals("(not set)", NamedCommand.blank().displayName());
        assertFalse(NamedCommand.blank().isRunnable());
    }
}
