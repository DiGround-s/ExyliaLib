package net.exylia.lib.placeholder.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.text.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PapiBridgeTest {

    private FakePlayer player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Registry.clear();
        PapiBridge.resetForTests();
        player = new FakePlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        PapiBridge.resetForTests();
        Registry.clear();
        FakeServer.reset();
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
    @DisplayName("a PlaceholderAPI placeholder carrying a pattern still reaches it")
    void externalPlaceholdersWithSpacesReachPapi() {
        PapiBridge.setApplierForTests((viewer, text) ->
                text.replace("%server_time_'\u23f0' MMM dd, yyyy%", "\u23f0 Sep 02, 2026"));

        assertEquals("\u23f0 Sep 02, 2026",
                Placeholders.apply("%server_time_'\u23f0' MMM dd, yyyy%", player.player()));
    }

    @Test
    @DisplayName("prose between two percent signs is still prose")
    void proseWithPercentSignsIsNotAPlaceholder() {
        PapiBridge.setApplierForTests((viewer, text) -> "resolved");

        assertEquals("50% of 20% is fine",
                Placeholders.apply("50% of 20% is fine", player.player()));
    }

    @Test
    void unavailablePapiLeavesExternalPlaceholdersVisible() {
        assertEquals("%external_value%", Placeholders.apply("%external_value%", player.player()));
    }

    @Test
    @DisplayName("PlaceholderAPI is never consulted off the main thread")
    void papiIsNotConsultedOffTheMainThread() {
        AtomicInteger calls = new AtomicInteger();
        PapiBridge.setApplierForTests((viewer, text) -> {
            calls.incrementAndGet();
            // What a third-party expansion that reads the world does when it is
            // reached from an async scoreboard render.
            throw new IllegalStateException("Asynchronous entity world add!");
        });
        FakeServer.setPrimaryThread(false);

        assertEquals("%external_value%",
                Placeholders.apply("%external_value%", player.player()));
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("an async render shows what the main-thread pass resolved")
    void asyncRendersUseTheRefreshedValue() {
        FakeServer.online(player.player());
        PapiBridge.setApplierForTests((viewer, text) -> text.replace("%external_value%", "outside"));
        FakeServer.setPrimaryThread(false);

        // Nothing has been resolved yet, so this render still shows the
        // placeholder as written — and asks for it.
        assertEquals("%external_value%", Placeholders.apply("%external_value%", player.player()));

        FakeServer.setPrimaryThread(true);
        PapiBridge.refreshWanted();
        FakeServer.setPrimaryThread(false);

        assertEquals("outside", Placeholders.apply("%external_value%", player.player()));
    }

    @Test
    @DisplayName("a placeholder deferred off the main thread is not called unregistered")
    void deferredPlaceholderIsNotReportedAsUnknown() {
        PapiBridge.setApplierForTests((viewer, text) -> text.replace("%external_value%", "outside"));
        FakeServer.setPrimaryThread(false);

        Placeholders.apply("%external_value%", player.player());

        // Registry.reportUnknown fires once per name for the life of the JVM,
        // so a false report here would also silence the real one later.
        assertFalse(Registry.wasReportedUnknownForTests("external_value"));
    }

    @Test
    @DisplayName("a PlaceholderAPI that throws leaves the text alone instead of aborting")
    void throwingPapiDoesNotAbortRendering() {
        PapiBridge.setApplierForTests((viewer, text) -> {
            throw new NoClassDefFoundError("me/clip/placeholderapi/PlaceholderAPI");
        });

        assertEquals("Kills: %external_value%",
                Placeholders.apply("Kills: %external_value%", player.player()));
    }

    @Test
    @DisplayName("PlaceholderAPI is never asked about a name ExyliaLib owns")
    void papiCannotOverrideWhatExyliaLibOwns() {
        // A PlaceholderAPI expansion answering "0" for a name a plugin
        // registered would be indistinguishable from the real value being zero.
        PapiBridge.setApplierForTests((viewer, text) -> "0");
        Registry.register("test_kills",
                new Registry.Entry(request -> "7", "PapiBridgeTest", true, ""));

        assertEquals("7", Placeholders.apply("%test_kills%", player.player()));
        // And an explicit per-message value still wins for its exact token.
        assertEquals("Warrior",
                Text.of("%class%").with("%class%", "Warrior").forPlayer(player.player()).plain());
    }

    @Test
    @DisplayName("availability is re-checked, so a late PlaceholderAPI still works")
    void availabilityIsRecheckedAfterALateLoad() {
        // ExyliaLib is load: STARTUP, so the first ask can happen before
        // PlaceholderAPI has enabled.
        assertFalse(PapiBridge.available());

        FakeServer.plugins(FakeServer.newPlugin("PlaceholderAPI"));

        assertTrue(PapiBridge.available());
    }
}
