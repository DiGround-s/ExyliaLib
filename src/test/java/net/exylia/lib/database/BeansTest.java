package net.exylia.lib.database;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BeansTest {

    record Stage(String material, double weight) {
    }

    @Test
    void roundTripsAnArrayOfObjects() {
        List<Stage> stages = List.of(new Stage("STONE", 70), new Stage("COAL_ORE", 30));
        String stored = Beans.encode(stages);
        assertEquals("[{\"material\":\"STONE\",\"weight\":70.0},{\"material\":\"COAL_ORE\",\"weight\":30.0}]", stored);
        assertEquals(stages, Beans.decode(stored, Stage.class));
    }

    @Test
    void emptyIsNullAndUnreadableIsEmpty() {
        assertNull(Beans.encode(List.of()));
        assertEquals(List.of(), Beans.decode(null, Stage.class));
        assertEquals(List.of(), Beans.decode("not json", Stage.class));
        assertEquals(List.of(new Stage("STONE", 1)), Beans.decode("[null,{\"material\":\"STONE\",\"weight\":1}]", Stage.class));
    }
}
