package net.exylia.lib.placeholder.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.text.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PapiBridgeTest {

    private FakePlayer player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Registry.clear();
        player = new FakePlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        PapiBridge.resetForTests();
        Registry.clear();
    }

    @Test
    void externalPlaceholdersRenderInTemplates() {
        PapiBridge.setApplierForTests((viewer, text) -> text.replace("%external_value%", "outside"));

        assertEquals("outside", Placeholders.apply("%external_value%", player.player()));
    }

    @Test
    void externalPlaceholdersRenderInText() {
        PapiBridge.setApplierForTests((viewer, text) -> text.replace("%external_value%", "outside"));

        assertEquals("outside", Text.of("%external_value%").forPlayer(player.player()).plain());
    }

    @Test
    void unavailablePapiLeavesExternalPlaceholdersVisible() {
        assertEquals("%external_value%", Placeholders.apply("%external_value%", player.player()));
    }
}
