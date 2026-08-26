package net.exylia.lib.clan.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the clan providers rely on when they reach a plugin they cannot import.
 *
 * <p>Every provider is a thin layer over these calls, so a mistake here is a
 * mistake in all eight of them at once.
 */
class ReflectTest {

    /** Stands in for a clan plugin's API object. */
    public static final class FakeApi {

        private final String tag;

        public FakeApi(String tag) {
            this.tag = tag;
        }

        public String getTag() {
            return tag;
        }

        public Optional<String> getName() {
            return Optional.of("Red");
        }

        public int getLevel() {
            return 7;
        }

        public boolean isOpen() {
            return true;
        }

        public UUID leaderId() {
            return UUID.nameUUIDFromBytes("leader".getBytes());
        }

        public String leaderText() {
            return leaderId().toString();
        }

        public List<String> members() {
            return List.of("a", "b");
        }

        public Map<String, Integer> weights() {
            return Map.of("a", 1);
        }

        public String greet(String who) {
            return "hi " + who;
        }

        public String greet(UUID who) {
            return "uuid " + who;
        }

        public static String origin() {
            return "static";
        }
    }

    private static final String CLASS = FakeApi.class.getName();

    private final FakeApi api = new FakeApi("RED");

    @Test
    @DisplayName("a class that is not installed resolves to nothing, not an error")
    void missingClassIsNotAnError() {
        assertNull(Reflect.type("com.example.no.such.Plugin"));
        assertNull(Reflect.statically("com.example.no.such.Plugin", "getInstance"));
    }

    @Test
    @DisplayName("a method that does not exist answers null instead of throwing")
    void missingMethodIsNotAnError() {
        assertNull(Reflect.call(api, "getSomethingElse"));
        assertNull(Reflect.string(api, "getSomethingElse"));
        assertEquals(0, Reflect.number(api, "getSomethingElse"));
        assertFalse(Reflect.flag(api, "getSomethingElse"));
        assertTrue(Reflect.collection(api, "getSomethingElse").isEmpty());
    }

    @Test
    @DisplayName("a null target answers null rather than failing")
    void nullTargetIsSafe() {
        assertNull(Reflect.call(null, "getTag"));
        assertNull(Reflect.uuid(null, "leaderId"));
    }

    @Test
    @DisplayName("instance and static calls both reach their method")
    void callsReachTheirMethod() {
        assertEquals("RED", Reflect.call(api, "getTag"));
        assertEquals("static", Reflect.statically(CLASS, "origin"));
    }

    @Test
    @DisplayName("an overload is picked by the type of the argument passed")
    void overloadsArePickedByArgumentType() {
        UUID id = UUID.randomUUID();
        assertEquals("hi bob", Reflect.call(api, "greet", "bob"));
        assertEquals("uuid " + id, Reflect.call(api, "greet", id));
    }

    @Test
    @DisplayName("the first name that exists wins, so a renamed API still resolves")
    void firstExistingNameWins() {
        assertEquals("RED", Reflect.string(api, "tag", "getTag"));
        assertNull(Reflect.string(api, "tag", "label"));
    }

    @Test
    @DisplayName("an optional the plugin returns is unwrapped")
    void optionalsAreUnwrapped() {
        assertEquals("Red", Reflect.get(api, "getName"));
        assertEquals("Red", Reflect.string(api, "getName"));
        assertNull(Reflect.unwrap(Optional.empty()));
    }

    @Test
    @DisplayName("an id reads the same whether the plugin stores a UUID or a string")
    void idsReadEitherWay() {
        UUID leader = UUID.nameUUIDFromBytes("leader".getBytes());
        assertEquals(leader, Reflect.uuid(api, "leaderId"));
        assertEquals(leader, Reflect.uuid(api, "leaderText"));
        assertNull(Reflect.toUuid("not-an-id"));
        assertNull(Reflect.toUuid(42));
    }

    @Test
    @DisplayName("numbers and flags read through their own accessors")
    void numbersAndFlagsRead() {
        assertEquals(7, Reflect.number(api, "getLevel"));
        assertTrue(Reflect.flag(api, "isOpen"));
    }

    @Test
    @DisplayName("a map getter reads as its keys when a collection is what was asked for")
    void mapsReadAsKeysAndAsMaps() {
        assertEquals(List.of("a", "b"), List.copyOf(Reflect.collection(api, "members")));
        assertEquals(java.util.Set.of("a"), java.util.Set.copyOf(Reflect.collection(api, "weights")));
        assertEquals(1, Reflect.map(api, "weights").get("a"));
        assertTrue(Reflect.map(api, "members").isEmpty());
    }
}
