package net.exylia.lib.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SoundMigrationTest {

    private static MutableConfig of(Map<String, Object> values) {
        return new MutableConfig() {
            @Override public Object get(String path) { return values.get(path); }
            @Override public void set(String path, Object value) { values.put(path, value); }
            @Override public void remove(String path) { values.remove(path); }
            @Override public boolean contains(String path) { return values.containsKey(path); }
        };
    }

    @Test
    void retagSwapsOnlyTheShippedTag() {
        Map<String, Object> values = new HashMap<>(Map.of(
                "a", "[sound:ENTITY_PLAYER_LEVELUP|1.0|1.2]{success}Bien",
                "b", "[sound:BLOCK_NOTE_BLOCK_PLING|0.5|1.0]{success}Mine"));
        MutableConfig data = of(values);
        Migration.retag("a", "[sound:ENTITY_PLAYER_LEVELUP|1.0|1.2]", "[sound:ENTITY_PLAYER_LEVELUP|0.6|1.2]").apply(data);
        Migration.retag("b", "[sound:ENTITY_PLAYER_LEVELUP|1.0|1.2]", "").apply(data);
        Migration.retag("missing", "[x]", "").apply(data);
        assertEquals("[sound:ENTITY_PLAYER_LEVELUP|0.6|1.2]{success}Bien", values.get("a"));
        assertEquals("[sound:BLOCK_NOTE_BLOCK_PLING|0.5|1.0]{success}Mine", values.get("b"));
        assertEquals(2, values.size());
    }

    @Test
    void swapSoundNeedsAllThreeAsShipped() {
        Map<String, Object> values = new HashMap<>(Map.of(
                "s.name", "ENTITY_PLAYER_LEVELUP", "s.volume", 1, "s.pitch", 1.2,
                "t.name", "ENTITY_PLAYER_LEVELUP", "t.volume", 0.8, "t.pitch", 1.2));
        MutableConfig data = of(values);
        Migration swap = Migration.all(
                Migration.swapSound("s", "ENTITY_PLAYER_LEVELUP", 1.0, 1.2, "BLOCK_NOTE_BLOCK_CHIME", 0.6, 1.2),
                Migration.swapSound("t", "ENTITY_PLAYER_LEVELUP", 1.0, 1.2, "BLOCK_NOTE_BLOCK_CHIME", 0.6, 1.2));
        swap.apply(data);
        assertEquals("BLOCK_NOTE_BLOCK_CHIME", values.get("s.name"));
        assertEquals(0.6, values.get("s.volume"));
        assertEquals("ENTITY_PLAYER_LEVELUP", values.get("t.name"), "a volume the owner tuned stays");
    }
}
