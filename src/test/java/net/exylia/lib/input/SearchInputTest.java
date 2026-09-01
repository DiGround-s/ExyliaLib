package net.exylia.lib.input;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.input.internal.TransportKind;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding one thing among thousands.
 *
 * <p>The case this exists for is a registry: every sound, every particle, every
 * material. ExyliaCommons showed those as pages of dialog buttons, which cannot
 * react while somebody types, so finding {@code block.note_block.pling} meant
 * paging through hundreds of buttons.
 *
 * <p>These tests cover the matching and the ranking, which is what the anvil's
 * rename box drives on every keystroke.
 */
class SearchInputTest {

    /** A stand-in for a registry key: exactly the shape of the real problem. */
    private static final List<String> SOUNDS = List.of(
            "block.note_block.pling",
            "block.note_block.bass",
            "block.anvil.land",
            "entity.player.levelup",
            "entity.villager.no",
            "ui.button.click");

    private PluginInputs inputs;
    private Player player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Plugin plugin = FakeServer.newPlugin("SearchTestPlugin", null);
        player = new FakePlayer("Steve").player();
        FakeServer.online(player);
        Inputs.releaseAll();
        inputs = Inputs.of(plugin);
    }

    private SearchInput<String> sounds() {
        return inputs.search(player, "Pick a sound", SOUNDS)
                .label(sound -> sound)
                .icon(sound -> Material.NOTE_BLOCK);
    }

    @Test
    @DisplayName("typing narrows the list")
    void filters() {
        SearchInput<String> search = sounds();

        assertEquals(6, search.search("").size(), "an empty query shows everything");
        assertEquals(2, search.search("note_block").size());
        assertEquals(List.of("block.anvil.land"), search.search("anvil"));
    }

    @Test
    @DisplayName("a query matches whatever the player is likely to remember")
    void matchesAnyPart() {
        // Somebody looking for the pling sound types "pling", not the namespace
        // it happens to live under.
        assertEquals(List.of("block.note_block.pling"), sounds().search("pling"));
        assertEquals(List.of("entity.player.levelup"), sounds().search("levelup"));
    }

    @Test
    @DisplayName("case does not matter, on any host")
    void caseInsensitive() {
        // And not with the default locale: in Turkish, lowercasing an I gives a
        // dotless i, so the same search would find nothing on that host.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(List.of("ui.button.click"), sounds().search("UI.BUTTON"));
            assertEquals(List.of("block.note_block.pling"), sounds().search("PLING"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("nothing found is an empty list, never an error")
    void noMatches() {
        // A search that finds nothing is a normal thing for a player to do.
        assertTrue(sounds().search("zzzz").isEmpty());
    }

    @Test
    @DisplayName("a custom matcher replaces the default")
    void customMatcher() {
        SearchInput<String> search = sounds()
                .matcher((sound, query) -> sound.startsWith(query));

        assertEquals(3, search.search("block").size());
        assertTrue(search.search("pling").isEmpty(), "startsWith only, as asked");
    }

    @Test
    @DisplayName("the text searched against is worked out once, not per keystroke")
    void normalizedOnce() {
        // The whole reason a search can run on every keystroke: the strings are
        // derived at open, so typing compares already-lowercased text rather
        // than calling toLowerCase on the whole registry per character.
        SearchInput<String> search = sounds();
        var normalized = search.normalizedSearchStrings();

        assertEquals(SOUNDS.size(), normalized.size());
        assertEquals("block.note_block.pling", normalized.get("block.note_block.pling"));
        for (String value : normalized.values()) {
            assertEquals(value.toLowerCase(Locale.ROOT), value, "must already be normalized");
        }
    }

    @Test
    @DisplayName("a label that costs something is only paid once per option")
    void labelsAreNotRecomputedPerKeystroke() {
        // A label function that hits a config or builds a component would be
        // ruinous if the filter called it for every option on every keystroke.
        List<String> calls = new ArrayList<>();
        SearchInput<String> search = inputs.search(player, "Pick a sound", SOUNDS)
                .label(sound -> {
                    calls.add(sound);
                    return sound;
                })
                .icon(sound -> Material.NOTE_BLOCK);

        search.normalizedSearchStrings();
        int afterIndexing = calls.size();

        search.search("note");
        search.search("note_");
        search.search("note_b");

        assertEquals(afterIndexing, calls.size(),
                "typing must not re-derive labels");
    }

    @Test
    @DisplayName("choosing gives back the object, not a string to look up again")
    void resolvesToTheValue() {
        // ExyliaCommons returned the option's key and every caller had to find
        // the object it named all over again.
        SearchInput<String> search = sounds();
        String key = search.keyOf("block.note_block.pling");

        InputParser.Parsed<String> parsed = search.parseRaw(key);

        assertTrue(parsed.ok(), () -> "should have resolved, got " + parsed.error());
        assertEquals("block.note_block.pling", parsed.value());
    }

    @Test
    @DisplayName("a key nobody offered is refused")
    void unknownKeyRefused() {
        // The client sends a slot, and a lying or stale client must not be able
        // to pick something that was never on the screen.
        InputParser.Parsed<String> parsed = sounds().parseRaw("not.a.sound");

        assertFalse(parsed.ok());
        assertFalse(parsed.error().isBlank());
    }

    @Test
    @DisplayName("a search over nothing is a caller bug")
    void emptyChoicesRefused() {
        assertThrows(InputException.class,
                () -> inputs.search(player, "Pick", List.<String>of()));
    }

    @Test
    @DisplayName("a page size decides how many icons a screen shows")
    void pageSize() {
        assertEquals(5, sounds().pageSize(5).pageSize());
        assertThrows(InputException.class, () -> sounds().pageSize(0));
    }

    @Test
    @DisplayName("a large registry filters without touching every label")
    void scalesToARealRegistry() {
        // Roughly the size of the sound registry, which is the thing this was
        // built for. Not a timing assertion — those are flaky on a shared
        // machine — but it does prove the path is linear and allocation-light
        // enough to run per keystroke.
        List<String> many = new ArrayList<>(2000);
        for (int index = 0; index < 2000; index++) {
            many.add("namespace.sound_" + index);
        }
        SearchInput<String> search = inputs.search(player, "Pick", many)
                .label(sound -> sound)
                .icon(sound -> Material.NOTE_BLOCK);

        assertEquals(1, search.search("sound_1999").size());
        for (int keystroke = 0; keystroke < 200; keystroke++) {
            search.search("sound_" + keystroke);
        }
    }

    // ------------------------------------------------------------------
    // A catalogue nobody holds: results fetched one page at a time.
    // ------------------------------------------------------------------

    /** A source standing in for an API: it answers, and remembers being asked. */
    private static SearchInput.Pages<String> catalogue(List<String> asked) {
        return (query, offset, limit) -> {
            asked.add(query + "@" + offset + "+" + limit);
            return CompletableFuture.completedFuture(
                    new SearchInput.Page<>(List.of("first", "second"), 4000));
        };
    }

    @Test
    @DisplayName("a paged request holds no snapshot and indexes nothing")
    void pagedHoldsNothing() {
        List<String> asked = new ArrayList<>();
        SearchInput<String> search = inputs.<String>search(player, "Pick")
                .source(catalogue(asked))
                .label(value -> value);

        assertTrue(search.choices().isEmpty());
        assertTrue(search.normalizedSearchStrings().isEmpty());
        assertTrue(asked.isEmpty(), "opening must not fetch anything by itself");
    }

    @Test
    @DisplayName("a paged request is pinned to the only transport that can page")
    void pagedPinsTheAnvil() {
        SearchInput<String> search = inputs.<String>search(player, "Pick")
                .source(catalogue(new ArrayList<>()));

        assertEquals(List.of(TransportKind.ANVIL_SEARCH), search.preferredTransports());
    }

    @Test
    @DisplayName("a fetched result is accepted as itself, with no key to resolve")
    void pagedValueAccepted() {
        SearchInput<String> search = inputs.<String>search(player, "Pick")
                .source(catalogue(new ArrayList<>()))
                .label(value -> value);

        InputParser.Parsed<String> parsed = search.parseValue("anything");

        assertTrue(parsed.ok());
        assertEquals("anything", parsed.value());
    }

    @Test
    @DisplayName("validation still runs on a fetched result")
    void pagedValueValidated() {
        SearchInput<String> search = inputs.<String>search(player, "Pick")
                .source(catalogue(new ArrayList<>()))
                .label(value -> value)
                .validate(value -> value.startsWith("ok"), "Not that one.");

        assertTrue(search.parseValue("ok-head").ok());
        InputParser.Parsed<String> refused = search.parseValue("no-head");
        assertFalse(refused.ok());
        assertEquals("Not that one.", refused.error());
    }
}
