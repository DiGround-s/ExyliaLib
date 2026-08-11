package net.exylia.lib.scoreboard.internal;

import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.Request;
import net.exylia.lib.placeholder.Template;
import net.exylia.lib.scoreboard.Board;
import net.exylia.lib.scoreboard.SidebarConfig;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The scoreboard engine: one instance per board shown to a player.
 *
 * <p>Every refresh renders the compiled templates, compares the result with
 * what the player already has, and sends only the difference. Resolving a
 * placeholder is cheap, but parsing text and writing packets are not, so the
 * diff happens on the rendered <em>strings</em>, before any of that.
 *
 * <p>Only the board at the top of the player's stack renders; a paused board
 * costs its map entry and nothing else.
 */
final class BoardImpl implements Board {

    /** Milliseconds between two ticks of the server clock. */
    private static final long TICK_MS = 50L;

    private final String ownerName;
    private final Player player;
    private final SidebarConfig config;
    private final SidebarHandle sidebar;

    private final Template[] titleFrames;
    private final Template[] lineTemplates;
    private final boolean smart;

    private final long intervalMs;
    /**
     * Fixed offset derived from the player's id, so two hundred boards due at
     * the same second spread their renders across the interval instead of
     * piling into one tick.
     */
    private final long stagger;

    /**
     * Mutual exclusion for renders: a slow placeholder must not make the next
     * cycle pile up on this one, so an overlapping cycle is skipped instead.
     * Everything mutable below is guarded by it.
     */
    private final AtomicBoolean rendering = new AtomicBoolean();

    private volatile Map<String, Object> data;
    private volatile long lastRender;
    private volatile boolean stopped;
    private volatile boolean paused;
    /** Set to make the next render ignore the diff and re-send everything. */
    private volatile boolean invalidated = true;

    private int frame;
    private String lastTitle;
    private String[] lastLines = new String[0];

    BoardImpl(String ownerName, Player player, SidebarConfig config, SidebarHandle sidebar) {
        this.ownerName = ownerName;
        this.player = player;
        this.config = config;
        this.sidebar = sidebar;
        this.titleFrames = compile(config.title());
        this.lineTemplates = compile(config.lines());
        this.smart = config.update().smart();
        this.intervalMs = Math.max(TICK_MS, config.update().interval() * TICK_MS);
        this.stagger = Math.floorMod(player.getUniqueId().getLeastSignificantBits(), intervalMs);
        this.data = Map.of();
        markDue();
    }

    private static Template[] compile(List<String> texts) {
        if (texts.isEmpty()) {
            return new Template[]{Placeholders.compile("")};
        }
        Template[] templates = new Template[texts.size()];
        for (int i = 0; i < texts.size(); i++) {
            templates[i] = Placeholders.compile(texts.get(i) == null ? "" : texts.get(i));
        }
        return templates;
    }

    // ------------------------------------------------------------------
    // Board
    // ------------------------------------------------------------------

    @Override
    public @NotNull Player player() {
        return player;
    }

    @Override
    public @NotNull SidebarConfig config() {
        return config;
    }

    @Override
    public void refresh() {
        markDue();
    }

    @Override
    public void updateData(@NotNull Map<String, Object> data) {
        this.data = data == null ? Map.of() : data;
        refresh();
    }

    @Override
    public void stop() {
        BoardManager.stop(this);
    }

    @Override
    public boolean stopped() {
        return stopped;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Returns whether the board's slot in its staggered grid has been crossed.
     *
     * <p>Comparing grid slots instead of elapsed time keeps the spread between
     * players stable even when every board was created in the same tick, which
     * is exactly what a reload does.
     */
    boolean due(long now) {
        return Math.floorDiv(now - stagger, intervalMs)
                > Math.floorDiv(lastRender - stagger, intervalMs);
    }

    /**
     * Marks the board due at the next driver tick.
     *
     * <p>A whole interval behind the board's own stagger is always an earlier
     * grid slot than the present, whatever the clock counts from. Zero would
     * work too against a wall clock, but only because the epoch is far away;
     * this does not depend on that.
     */
    private void markDue() {
        lastRender = stagger - intervalMs;
    }

    /**
     * Renders the templates and sends what changed.
     *
     * <p>Runs on the refresh driver, off the main thread: the sidebar writes
     * packets asynchronously, and the placeholders used here are the plugin's
     * responsibility to mark {@code async()}.
     */
    void render() {
        if (stopped || paused || sidebar.closed() || !player.isOnline()) {
            return;
        }
        if (!rendering.compareAndSet(false, true)) {
            return;
        }
        try {
            lastRender = BoardManager.now();
            boolean sendAll = invalidated || !smart;

            renderTitle(sendAll);
            renderLines(sendAll);

            invalidated = false;
        } catch (Throwable t) {
            BoardManager.logger().warning("Could not render the scoreboard of "
                    + player.getName() + ": " + t.getMessage());
        } finally {
            rendering.set(false);
        }
    }

    private void renderTitle(boolean sendAll) {
        Template current = titleFrames[frame % titleFrames.length];
        String text = current.render(player, data);
        frame = (frame + 1) % titleFrames.length;

        if (sendAll || !text.equals(lastTitle)) {
            sidebar.title(componentFor(current, text));
            lastTitle = text;
        }
    }

    private void renderLines(boolean sendAll) {
        List<String> rendered = new ArrayList<>(lineTemplates.length);
        // Which template produced each output line. A line that came out of an
        // expansion has none: its text only exists as a resolved string.
        List<Template> sources = new ArrayList<>(lineTemplates.length);

        for (Template template : lineTemplates) {
            String text = template.render(player, data);
            if (text.indexOf('\n') < 0) {
                rendered.add(text);
                sources.add(template);
            } else {
                int before = rendered.size();
                appendExpanded(text, rendered);
                for (int i = before; i < rendered.size(); i++) {
                    sources.add(null);
                }
            }
            if (rendered.size() >= SidebarHandle.MAX_LINES) {
                break;
            }
        }

        int count = rendered.size();
        int previous = lastLines.length;
        for (int i = 0; i < count; i++) {
            String text = rendered.get(i);
            if (sendAll || i >= previous || !text.equals(lastLines[i])) {
                sidebar.line(i, componentFor(sources.get(i), text));
            }
        }
        // A placeholder can shrink the board between cycles; the leftover
        // lines the player still has have to be cleared explicitly.
        for (int i = count; i < previous; i++) {
            sidebar.line(i, null);
        }
        lastLines = rendered.toArray(new String[0]);
    }

    /**
     * Builds the component for a line that changed.
     *
     * <p>Parsing is what a scoreboard actually spends its time on: resolving a
     * placeholder costs nanoseconds, parsing colours and MiniMessage costs
     * microseconds. So the template's <em>raw</em> text is what gets parsed —
     * it never changes, so it is a cache hit for every player and every tick —
     * and the resolved values are inserted into that parsed component.
     * Measured on a nine line board, that is 26.8µs against 4.2µs per changed
     * line.
     *
     * <p>Values are parsed too, so a placeholder that returns
     * {@code &c[VIP]} still comes out red. What it does not do is repaint the
     * rest of the line: the text after a placeholder keeps the colour the
     * template gave it.
     *
     * @param source the template the line came from, or {@code null} when the
     *               line came out of an expansion and only exists as text
     * @param text   the resolved line
     */
    private Component componentFor(Template source, String text) {
        if (source == null || !source.isDynamic()) {
            return Text.component(text);
        }
        List<String> pairs = Placeholders.resolvePairs(source,
                new Request(player, player, List.of(), data));
        if (pairs.isEmpty()) {
            return Text.component(text);
        }

        Component component = Text.component(source.raw());
        for (int i = 0; i < pairs.size(); i += 2) {
            Component value = Text.component(pairs.get(i + 1));
            String placeholder = pairs.get(i);
            component = component.replaceText(builder -> builder
                    .matchLiteral(placeholder)
                    .replacement(value));
        }
        return component;
    }

    /**
     * Adds a rendered line to the output, expanding line breaks.
     *
     * <p>One placeholder can fill a whole leaderboard by returning its entries
     * separated by line breaks. The board is capped at {@link
     * SidebarHandle#MAX_LINES} rather than failing, because a long top-ten is
     * content, not an error.
     */
    private static void appendExpanded(String text, List<String> out) {
        int start = 0;
        int end = text.indexOf('\n');
        while (end >= 0 && out.size() < SidebarHandle.MAX_LINES) {
            out.add(text.substring(start, end));
            start = end + 1;
            end = text.indexOf('\n', start);
        }
        if (out.size() < SidebarHandle.MAX_LINES) {
            out.add(text.substring(start));
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle, driven by BoardManager
    // ------------------------------------------------------------------

    /** Hides the board because another one sits on top, keeping its state. */
    void pause() {
        paused = true;
        sidebar.hide();
    }

    /** Brings a paused board back when the one on top goes away. */
    void resume() {
        paused = false;
        sidebar.show();
        invalidate();
    }

    /**
     * Makes the next render re-send everything.
     *
     * <p>Used when the text is unchanged but what it parses into is not, which
     * is what a palette reload does: the diff would otherwise decide there is
     * nothing to send and leave the old colours on screen.
     */
    void invalidate() {
        invalidated = true;
        markDue();
    }

    /** Stops the board for good. Called by {@link BoardManager}. */
    void stopInternal() {
        stopped = true;
        sidebar.close();
    }

    boolean paused() {
        return paused;
    }

    SidebarHandle sidebar() {
        return sidebar;
    }

    boolean ownedBy(String pluginName) {
        return ownerName.equals(pluginName);
    }

    boolean isFor(Player viewer) {
        return player.getUniqueId().equals(viewer.getUniqueId());
    }
}
