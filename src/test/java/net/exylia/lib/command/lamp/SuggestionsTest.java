package net.exylia.lib.command.lamp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The command framework sends back whatever a provider returns, so cutting the
 * list down to the word being typed is this library's own job.
 */
class SuggestionsTest {

    private static final List<String> NAMES = List.of("Drako98", "DiGround_", "antonycpvp");

    @Test
    @DisplayName("a half-typed name keeps only the names that carry it on")
    void filtersOnTheWordBeingTyped() {
        assertEquals(List.of("Drako98"), Suggestions.matching("/punish Drak", NAMES));
    }

    @Test
    @DisplayName("the word being typed is matched however it is cased")
    void ignoresCase() {
        assertEquals(List.of("DiGround_"), Suggestions.matching("/punish digr", NAMES));
    }

    @Test
    @DisplayName("a buffer ending on a space is a fresh argument: everything is offered")
    void offersEverythingOnAFreshArgument() {
        assertEquals(NAMES, Suggestions.matching("/punish ", NAMES));
    }

    @Test
    @DisplayName("only the last word counts, not the arguments already given")
    void filtersOnTheLastWordOnly() {
        assertEquals(List.of("Drako98"), Suggestions.matching("/punish DiGround_ Drak", NAMES));
    }
}
