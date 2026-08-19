package net.exylia.lib.util.snapshot;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;

/**
 * What a plugin's snapshot store does on the way up.
 *
 * <p>Nests inside a plugin's own configuration record like any other section:
 *
 * <pre>{@code
 * public record MySettings(SnapshotSettings snapshots) {
 *     public MySettings() {
 *         this(new SnapshotSettings());
 *     }
 * }
 * }</pre>
 *
 * <p>There is deliberately nothing here about expiry. An orphaned snapshot is
 * somebody's inventory, and a rule that deletes it after a fortnight is a rule
 * that deletes an inventory belonging to a player who was on holiday.
 *
 * @param importLegacy whether to copy the ExyliaCommons table on first use
 *
 * @since 1.34.0
 */
@Comment("Stored player snapshots: inventories held while a player is somewhere")
@Comment("else, such as an arena, an event or a sandbox world.")
public record SnapshotSettings(

        @Key("import-legacy")
        @Comment("Whether to copy rows written by ExyliaCommons out of its own")
        @Comment("table the first time this plugin stores a snapshot. It copies;")
        @Comment("it never deletes, so the old table is left exactly as it was.")
        @Comment("Turn this off on a server that has never run ExyliaCommons:")
        @Comment("reading a table is what creates it, so leaving it on there")
        @Comment("costs one empty table nobody will ever use.")
        boolean importLegacy
) {

    /** Safe defaults, used when a plugin declares no section of its own. */
    public SnapshotSettings() {
        // On by default: the cost of importing on a server with nothing to
        // import is one empty table, and the cost of not importing on a server
        // that has something is every stored inventory on it.
        this(true);
    }
}
