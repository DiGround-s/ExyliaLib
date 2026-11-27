package net.exylia.lib.setup;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupChoicesTest {

    private static SetupChoices.Choice choice(String plugin, String id) {
        return new SetupChoices.Choice(plugin, id, "T", List.of(
                new SetupChoices.Option("a", "A", List.of(), Material.STONE)), () -> "a", x -> { });
    }

    @Test
    void registersListsReplacesAndReleases() {
        SetupChoices.register(choice("P1", "layout"));
        SetupChoices.register(choice("P2", "mode"));
        SetupChoices.register(choice("P1", "layout"));
        assertEquals(2, SetupChoices.all().size());
        assertEquals("P2", SetupChoices.all().get(0).plugin());
        SetupChoices.release("P1");
        assertEquals(1, SetupChoices.all().size());
        SetupChoices.release("P2");
        assertTrue(SetupChoices.all().isEmpty());
    }
}
