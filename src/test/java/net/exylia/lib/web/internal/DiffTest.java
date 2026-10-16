package net.exylia.lib.web.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffTest {

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    @Test
    void changesAreReportedPerLeafWithListsByPosition() {
        List<Diff.Change> changes = Diff.of(
                json("{\"a\":1,\"b\":{\"c\":[1,2]},\"gone\":true}"),
                json("{\"a\":2,\"b\":{\"c\":[1,3,4]},\"added\":\"x\"}"));
        List<String> paths = changes.stream().map(Diff.Change::path).toList();
        assertEquals(List.of("a", "b.c[1]", "b.c[2]", "gone", "added"), paths);
    }

    @Test
    void aBlockAddedWholeIsWalkedLeafByLeaf() {
        List<Diff.Change> changes = Diff.of(json("{\"entries\":{}}"),
                json("{\"entries\":{\"spawn\":{\"scale\":1,\"click\":{\"commands\":[\"console: x\"]}}}}"));
        List<String> paths = changes.stream().map(Diff.Change::path).toList();
        assertEquals(List.of("entries.spawn.scale", "entries.spawn.click.commands[0]"), paths);
        assertTrue(changes.get(1).privileged());
        assertFalse(changes.get(0).privileged());
    }

    @Test
    void patternsNameABranchAndIgnoreIndices() {
        assertTrue(Diff.matches("entries.spawn.click.commands[0]", "entries.*.click"));
        assertTrue(Diff.matches("pages[2].click[0]", "pages.click"));
        assertFalse(Diff.matches("entries.spawn.scale", "entries.*.click"));
        assertFalse(Diff.matches("entries", "entries.*.click"));
        assertTrue(Diff.matchesAny("look.shadow", List.of("nothing", "look")));
    }

    @Test
    void aLineRunAsTheConsoleIsPrivilegedWhateverClickItIsLimitedTo() {
        assertTrue(Diff.privileged(json("\"console: say hi\"")));
        assertTrue(Diff.privileged(json("\"right: CONSOLE: op %player%\"")));
        assertTrue(Diff.privileged(json("\"[CONSOLE] say hi\"")));
        assertTrue(Diff.privileged(json("\"console-proxy: alert hi\"")));
        assertTrue(Diff.privileged(json("[\"say hi\", \"op: me\"]")));
        assertTrue(Diff.privileged(json("{\"click\":{\"commands\":[\"console: x\"]}}")));
        assertFalse(Diff.privileged(json("\"say hi\"")));
        assertFalse(Diff.privileged(json("\"player-proxy: lobby\"")));
        assertFalse(Diff.privileged(json("\"[MESSAGE] console: is a word here\"")));
        assertFalse(Diff.privileged(json("3")));
    }
}
