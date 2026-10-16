package net.exylia.lib.web.internal;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Time;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** A file with one of everything the editor has to describe: the test fixture. */
public record Board(
        @Comment("Ticks between refreshes.")
        @Time(Time.Unit.TICKS) long refresh,
        Duration lasts,
        Mode mode,
        @Comment("The lines, top first.")
        List<String> lines,
        List<Page> pages,
        Map<String, Entry> entries,
        Look look) {

    public enum Mode { FAST, SLOW_MOTION }

    public record Page(List<String> lines, List<String> click) {
    }

    public record Entry(boolean enabled, double scale, Click click) {
        public Entry() {
            this(true, 1.0, new Click());
        }
    }

    public record Click(@Comment("What a click runs.") List<String> commands) {
        public Click() {
            this(List.of());
        }
    }

    public record Look(boolean shadow) {
        public Look() {
            this(false);
        }
    }

    public Board() {
        this(20, Duration.ofSeconds(5), Mode.FAST, List.of("hello"), List.of(), Map.of(), new Look());
    }

    public Board withLook(Look look) {
        return new Board(refresh, lasts, mode, lines, pages, entries, look);
    }

    public Board withEntries(Map<String, Entry> entries) {
        return new Board(refresh, lasts, mode, lines, pages, entries, look);
    }

    public Board withLines(List<String> lines) {
        return new Board(refresh, lasts, mode, lines, pages, entries, look);
    }
}
