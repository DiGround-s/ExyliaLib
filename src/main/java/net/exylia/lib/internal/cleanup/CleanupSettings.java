package net.exylia.lib.internal.cleanup;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;

/**
 * What the server keeps and what it throws away, from
 * {@code plugins/ExyliaLib/cleanup.yml}.
 *
 * <p>Housekeeping the server itself never does. It lives in the library rather
 * than in a plugin because the folders it tidies belong to the server, not to
 * any one plugin, and because every Exylia server would otherwise need the same
 * task written again.
 *
 * @param logs what to do with the server's own log folder
 * @since 1.90.0
 */
@Comment("Housekeeping ExyliaLib does for the server.")
@Comment("")
@Comment("Nothing here touches a plugin's own files: these are the folders the")
@Comment("server writes to and never cleans, which grow until someone notices.")
public record CleanupSettings(

        @Comment("The server's own logs, in the logs/ folder next to plugins/.")
        Logs logs
) {

    /** Defaults used when the file does not exist yet. */
    public CleanupSettings() {
        this(new Logs());
    }

    /**
     * How long the server's logs are kept.
     *
     * @param enabled  whether old logs are deleted at all
     * @param keepDays how many days of logs to keep
     * @since 1.90.0
     */
    public record Logs(

            @Comment("Whether old log files are deleted.")
            @Comment("Turn it off if something else already rotates them.")
            boolean enabled,

            @Key("keep-days")
            @Comment("How many days of logs to keep. Anything older goes.")
            @Comment("The log the server is writing right now is never touched,")
            @Comment("and neither is anything that is not a log file.")
            @Comment("Minimum 1: a value below that is read as 1, since deleting")
            @Comment("today's logs would take the one being written with them.")
            int keepDays
    ) {

        /** A week, which is long enough to look into yesterday's crash. */
        public Logs() {
            this(true, 7);
        }
    }
}
