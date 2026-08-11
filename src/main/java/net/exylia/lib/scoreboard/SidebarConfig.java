package net.exylia.lib.scoreboard;

import net.exylia.lib.config.Comment;

import java.util.List;

/**
 * A sidebar scoreboard declared in a config file.
 *
 * <p>The plugin says which board a player should see; the server owner decides
 * what that board looks like:
 *
 * <pre>{@code
 * Scoreboards.show(this, player, config.get().scoreboards().ffa());
 * }</pre>
 *
 * <p>Written in YAML, that same board is:
 *
 * <pre>
 * ffa:
 *   enabled: true
 *   title: '{primary}&amp;lFFA'
 *   lines:
 *     - ''
 *     - ' {muted}❙ {letters}Arena: {highlight}%arena_name%'
 *     - ' {muted}❙ {letters}Players: {success}%arena_players%'
 *     - ''
 *     - ' {highlight}exylia.net'
 *   update:
 *     interval: 15
 *     smart: true
 *     cache: true
 * </pre>
 *
 * <p>The title also accepts a list, which animates it: every refresh shows the
 * next frame.
 *
 * <pre>
 * title:
 *   - '{primary}&amp;lFFA'
 *   - '{secondary}&amp;lFFA'
 * </pre>
 *
 * <p>A placeholder that returns text with line breaks expands to several lines,
 * so a single {@code %arena_top%} can fill a whole top-three. The board never
 * shows more than 15 lines; extra ones are dropped with a warning.
 *
 * <h2>Keys kept from ExyliaCommons</h2>
 * The section reads exactly like the scoreboards ExyliaCommons wrote, so a
 * server moving to a plugin built on ExyliaLib keeps its file untouched:
 * {@code enabled}, {@code title}, {@code lines} and
 * {@code update.interval}/{@code update.smart}/{@code update.cache} all mean
 * what they meant there. In particular {@code interval} stays in
 * <em>ticks</em>, not the seconds other ExyliaLib modules use, because an old
 * {@code interval: 15} must keep refreshing fifteen times a second, not once
 * every fifteen seconds.
 *
 * @param enabled whether the board shows at all; {@code false} keeps the
 *                section without displaying anything
 * @param title   the title frames; one entry for a static title, several to
 *                animate
 * @param lines   the lines, top first
 * @param update  how the board refreshes
 * @since 1.5.0
 */
public record SidebarConfig(
        @Comment("Set to false to keep the section without showing the board.")
        boolean enabled,

        @Comment("The title. Supports colours like {primary} and placeholders like %player_name%.")
        @Comment("Write several titles to animate: each refresh shows the next one.")
        List<String> title,

        @Comment("The lines, top first. At most 15; extra lines are dropped.")
        @Comment("A placeholder may expand to several lines by returning text with line breaks.")
        List<String> lines,

        @Comment("How the board refreshes.")
        Update update) {

    /**
     * A disabled, empty board, which is what an unwritten section means.
     *
     * <p>Required by the config module, and useful on its own: handing this to
     * {@link Scoreboards#show} shows nothing instead of every caller checking
     * first.
     */
    public SidebarConfig() {
        this(false, List.of(), List.of(), new Update());
    }

    public SidebarConfig {
        title = title == null ? List.of() : List.copyOf(title);
        lines = lines == null ? List.of() : List.copyOf(lines);
        if (update == null) {
            update = new Update();
        }
    }

    /**
     * How a board refreshes, as written in config.
     *
     * @param interval ticks between refreshes; 20 ticks are one second. Kept in
     *                 ticks for ExyliaCommons file compatibility
     * @param smart    send only the lines that changed instead of the whole
     *                 board every refresh
     * @param cache    kept so ExyliaCommons files load unchanged. Parsed text
     *                 is cached by the text module for every plugin at once,
     *                 with a size limit and an expiry, which is strictly better
     *                 than a per-board cache: a board-sized map of values that
     *                 change every second is a leak, not a cache. The value is
     *                 read and ignored
     */
    public record Update(
            @Comment("Ticks between refreshes. 20 ticks are one second.")
            long interval,

            @Comment("Send only the lines that changed instead of the whole board.")
            boolean smart,

            @Comment("Kept for compatibility with older scoreboard files; text caching")
            @Comment("is always on and shared between plugins, so this changes nothing.")
            boolean cache) {

        /** The commons defaults: refresh every second, diff and cache on. */
        public Update() {
            this(20, true, true);
        }

        public Update {
            if (interval < 1) {
                interval = 1;
            }
        }
    }
}
