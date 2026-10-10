package net.exylia.lib.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link CommandLine#compile(String, CommandActor)}: a reward list's bare line runs as console. */
class BareActorTest {

    @Test
    void bareLineTakesTheGivenActor() {
        assertEquals(CommandActor.CONSOLE, CommandLine.compile("eco give %player% 100", CommandActor.CONSOLE).actor());
        assertEquals(CommandActor.PLAYER, CommandLine.compile("spawn").actor());
    }

    @Test
    void aNamedActorStillWins() {
        assertEquals(CommandActor.PLAYER, CommandLine.compile("player: spawn", CommandActor.CONSOLE).actor());
        assertEquals("spawn", CommandLine.compile("/spawn", CommandActor.CONSOLE).render(null));
    }
}
