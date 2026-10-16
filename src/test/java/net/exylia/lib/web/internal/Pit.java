package net.exylia.lib.web.internal;

import java.util.List;

/** One entry of a test store: a nested list of records and an enum, as a mine would have. */
public record Pit(String name, Kind kind, List<Layer> layers) {

    public Pit() {
        this("Pit", Kind.CLASSIC, List.of());
    }

    public enum Kind { CLASSIC, REALISTIC }

    public record Layer(String block, int weight) {
        public Layer() {
            this("STONE", 1);
        }
    }
}
