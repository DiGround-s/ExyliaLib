package net.exylia.lib.hologram.internal;

import net.exylia.lib.hologram.Hologram;
import net.exylia.lib.hologram.HologramConfig;
import net.exylia.lib.interaction.Click;
import net.exylia.lib.interaction.Interaction;
import net.exylia.lib.interaction.Interactions;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.Request;
import net.exylia.lib.placeholder.Template;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.internal.Conditions;
import net.exylia.lib.text.Colors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * One hologram: its displays, its viewers, and what each of them has been sent.
 *
 * <p>Three things keep this cheap. A player only receives packets when they
 * cross the view distance, not every tick. A line is only re-sent when its
 * rendered text actually changes. And what gets parsed is the line's raw
 * template text, which never changes, with the resolved values substituted into
 * that parsed component: parsing the resolved string instead costs six times
 * more, measured in the scoreboard module.
 *
 * <p>A hologram whose lines have no placeholders never schedules a refresh at
 * all, so a sign that says "Spawn" costs exactly one packet per viewer, once.
 *
 * <p>Line tags ({@link LineStyle}) add three costs, each paid only by the
 * holograms that use them. A line with a condition or a range makes every
 * viewer's set of visible lines checked on the visibility pass, and the stack
 * re-laid out for them alone when it changes. An animated line sends one
 * transformation every {@value #STEP_MS} ms, which the client glides through on
 * its own. A typewriter line sends its growing text to a viewer only while it
 * is being typed for them.
 */
final class HologramImpl implements Hologram {

    private static final long TICK_MS = 50L;

    /** How far apart animation keyframes are; the client interpolates between them. */
    static final long STEP_MS = 500L;
    private static final int STEP_TICKS = (int) (STEP_MS / TICK_MS);

    /** Ticks a hover grows or shrinks over. */
    private static final int HOVER_TICKS = 3;
    /** How far away a look still counts as hovering, about where a click reaches. */
    private static final double HOVER_REACH = 6.0;
    /** Eye height of a standing player; ponytail: sneaking reads a little high. */
    private static final double EYE_HEIGHT = 1.62;

    /** Shortest gap between two clicks, which also swallows a double-fired press. */
    private static final long MIN_CLICK_GAP_MS = 200L;

    /** Unreadable conditions already reported, so each is logged once. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private final String id;
    private final Plugin owner;
    private final String ownerName;
    private final HologramConfig config;

    /** One display per line for text, a single one for items and blocks. */
    private volatile DisplayState[] displays;
    private volatile Template[] templates;
    /** How each text line is drawn, from the tags at its start. */
    private volatile LineStyle[] styles;
    /** Whether any line moves, is typed in, or is shown to some viewers only. */
    private volatile boolean animated;
    private volatile boolean typed;
    private volatile boolean conditional;
    private volatile boolean shimmering;
    /** Per line, whether the plain text went out after the shine came to rest. */
    private volatile boolean[] restSent = new boolean[0];
    /** The viewers looking at a clickable hologram right now. */
    private final Set<UUID> hovering = ConcurrentHashMap.newKeySet();

    /** Which lines each viewer is shown, for holograms with conditional lines. */
    private final Map<UUID, boolean[]> masks = new ConcurrentHashMap<>();
    /** When each line started being typed for each viewer; 0 once it is whole. */
    private final Map<UUID, long[]> typing = new ConcurrentHashMap<>();
    private volatile long lastFrame = Long.MIN_VALUE;

    /** The invisible box clicks land on; only spawned when it is clickable. */
    private final int hitboxId;
    private final Interaction clickLines;
    private volatile BiConsumer<Player, Click> clickHandler;
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    /** What each display currently shows, for the diff. */
    private volatile String[] lastText;

    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

    /** The players the last visibility check found, for the frames drawn between checks. */
    private volatile List<Player> lastViewers = List.of();

    private volatile Location location;
    private volatile Entity mount;
    private volatile Map<String, Object> data = Map.of();
    private volatile Predicate<Player> filter;
    private volatile boolean removed;

    private final long intervalMs;
    /** Whether the text resolves to something new on its own. */
    private volatile boolean dynamic;
    /** Whether the text was changed and has not been drawn since. */
    private volatile boolean pending;
    private volatile long lastRender;

    HologramImpl(String id, Plugin owner, HologramConfig config, Location location) {
        this.id = id;
        this.owner = owner;
        this.ownerName = owner.getName();
        this.config = config;
        this.location = location.clone().add(config.offsetX(), config.offsetY(), config.offsetZ());
        this.intervalMs = Math.max(TICK_MS, config.config().updateInterval() * TICK_MS);
        this.clickLines = Interactions.compile(owner, config.click().commands(), "hologram \"" + id + "\"");
        restyle(styles(config.lines()));
        this.templates = compile(config.lines());
        this.displays = build(config, styles);
        this.hitboxId = HologramRuntime.newEntityId();
        this.lastText = new String[displays.length];
        this.dynamic = config.config().autoUpdate() && isDynamic(templates);
        this.lastRender = -intervalMs;
    }

    private static Template[] compile(List<String> lines) {
        Template[] templates = new Template[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            templates[i] = Placeholders.compile(LineStyle.parse(lines.get(i)).body());
        }
        return templates;
    }

    private void restyle(LineStyle[] styles) {
        boolean text = config.type() == HologramConfig.Kind.TEXT;
        this.styles = styles;
        this.animated = text && Arrays.stream(styles).anyMatch(LineStyle::animated);
        this.typed = text && Arrays.stream(styles).anyMatch(style -> style.typewriter() > 0);
        this.conditional = text && Arrays.stream(styles).anyMatch(LineStyle::conditional);
        this.shimmering = text && Arrays.stream(styles).anyMatch(LineStyle::shimmers);
        this.restSent = new boolean[styles.length];
    }

    private static LineStyle[] styles(List<String> lines) {
        LineStyle[] styles = new LineStyle[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            styles[i] = LineStyle.parse(lines.get(i)).style();
        }
        return styles;
    }

    private static boolean isDynamic(Template[] templates) {
        for (Template template : templates) {
            if (template.isDynamic()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates the displays.
     *
     * <p>Text holograms get one display per line, which is what allows a line
     * to change without touching the others. Item and block holograms are a
     * single display. A text line tagged {@code <item:...>} or
     * {@code <block:...>} is an item or block display standing in the stack.
     */
    private static DisplayState[] build(HologramConfig config, LineStyle[] styles) {
        if (config.type() != HologramConfig.Kind.TEXT) {
            Material material = material(config);
            return new DisplayState[]{
                    new DisplayState(HologramRuntime.newEntityId(), config.type(),
                            config.properties(), material)};
        }
        DisplayState[] displays = new DisplayState[styles.length];
        for (int i = 0; i < styles.length; i++) {
            LineStyle style = styles[i];
            HologramConfig.Properties properties = style.apply(config.properties());
            HologramConfig.Kind kind = !style.isModel() ? HologramConfig.Kind.TEXT
                    : style.block() ? HologramConfig.Kind.BLOCK : HologramConfig.Kind.ITEM;
            displays[i] = new DisplayState(HologramRuntime.newEntityId(), kind, properties,
                    style.isModel() ? material(style.model()) : Material.STONE, style);
        }
        return displays;
    }

    /**
     * Reads the item or block a hologram shows.
     *
     * <p>A misspelled material is the owner's typo, not a reason to refuse to
     * draw anything: it is reported once and stone is used instead.
     */
    private static Material material(HologramConfig config) {
        return material(config.type() == HologramConfig.Kind.ITEM ? config.item() : config.block());
    }

    private static Material material(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null) {
            HologramRuntime.logger().warning("Unknown material '" + name
                    + "' in a hologram; showing stone instead.");
            return Material.STONE;
        }
        return material;
    }

    // ------------------------------------------------------------------
    // Hologram
    // ------------------------------------------------------------------

    @Override
    public @NotNull String id() {
        return id;
    }

    @Override
    public @NotNull Location location() {
        return location.clone();
    }

    @Override
    public void moveTo(@NotNull Location target) {
        this.location = target.clone().add(config.offsetX(), config.offsetY(), config.offsetZ());
        HologramRuntime.moved(this);
    }

    @Override
    public void attachTo(@Nullable Entity entity) {
        this.mount = entity;
        HologramRuntime.remounted(this);
    }

    @Override
    public void lines(@NotNull List<String> lines) {
        Template[] compiled = compile(lines);
        LineStyle[] restyled = styles(lines);
        // A different number of lines, or lines drawn differently, means
        // different displays, so viewers have to be given the new ones rather
        // than an update for entities that no longer look that way.
        boolean sameShape = java.util.Arrays.equals(restyled, styles)
                && config.type() == HologramConfig.Kind.TEXT;
        restyle(restyled);
        this.templates = compiled;
        // Lines are replaced, so what they resolve from is decided again: text
        // that had a placeholder may no longer have one, and the other way
        // round.
        this.dynamic = config.config().autoUpdate() && isDynamic(compiled);
        if (sameShape) {
            refresh();
            return;
        }
        HologramRuntime.rebuild(this);
    }

    @Override
    public void refresh() {
        pending = true;
        lastRender = -intervalMs;
    }

    @Override
    public void onClick(@Nullable BiConsumer<Player, Click> handler) {
        boolean was = clickable();
        boolean will = !clickLines.isEmpty() || handler != null;
        if (was != will) {
            // The box is spawned with the hologram, so viewers are given it
            // anew; taken down first, while it still counts as there.
            HologramRuntime.reshow(this);
        }
        this.clickHandler = handler;
    }

    @Override
    public void updateData(@NotNull Map<String, Object> data) {
        this.data = data == null ? Map.of() : data;
        refresh();
    }

    /** The filter a hologram is born with, set before anything can draw it. */
    void initialFilter(@Nullable Predicate<Player> filter) {
        this.filter = filter;
    }

    @Override
    public void visibleIf(@Nullable Predicate<Player> filter) {
        this.filter = filter;
        HologramRuntime.visibilityChanged(this);
    }

    @Override
    public boolean isViewing(@NotNull Player player) {
        return viewers.contains(player.getUniqueId());
    }

    @Override
    public int viewerCount() {
        return viewers.size();
    }

    @Override
    public void remove() {
        HologramRuntime.remove(this);
    }

    @Override
    public boolean removed() {
        return removed;
    }

    // ------------------------------------------------------------------
    // Engine, driven by HologramRuntime
    // ------------------------------------------------------------------

    /** Returns whether a player should be able to see this hologram now. */
    boolean canSee(Player player) {
        if (removed || !player.isOnline()) {
            return false;
        }
        Location at = location;
        if (player.getWorld() != at.getWorld()) {
            return false;
        }
        Predicate<Player> current = filter;
        if (current != null && !current.test(player)) {
            return false;
        }
        double range = config.viewDistance();
        // Squared, so a player walking past does not pay for a square root.
        return player.getLocation().distanceSquared(at) <= range * range;
    }

    /**
     * Whether this hologram has anything worth refreshing.
     *
     * <p>Text that resolves to something new on its own is redrawn on the
     * interval. Plain text is not, because it would render the same string
     * every time — but a caller who changes it, through {@link #lines} or
     * {@link #updateData}, has made it different exactly once, and that one
     * render still has to happen.
     */
    boolean refreshes() {
        return dynamic || pending;
    }

    boolean due(long now) {
        return now - lastRender >= intervalMs;
    }

    /**
     * Renders the lines and sends the ones that changed.
     *
     * <p>For a shared hologram this happens once and the result goes to every
     * viewer. A per-player hologram renders for each viewer instead, which is
     * what makes its placeholders personal and also what makes it cost more.
     */
    void render(List<Player> currentViewers) {
        if (removed) {
            return;
        }
        lastRender = HologramRuntime.now();
        pending = false;

        if (config.type() != HologramConfig.Kind.TEXT || templates.length == 0) {
            return;
        }
        if (config.perPlayer()) {
            for (Player viewer : currentViewers) {
                renderFor(viewer, true);
            }
            return;
        }
        renderShared(currentViewers);
    }

    /** One render, sent to everybody: the cheap path, and the default. */
    private void renderShared(List<Player> currentViewers) {
        DisplayState[] current = displays;
        Template[] lines = templates;
        String[] previous = lastText;

        for (int i = 0; i < current.length && i < lines.length; i++) {
            if (current[i].kind() != HologramConfig.Kind.TEXT) {
                continue;
            }
            String text = lines[i].render(null, data);
            if (text.equals(previous[i])) {
                continue;
            }
            previous[i] = text;
            current[i].text(componentFor(lines[i], text, null));
            for (Player viewer : currentViewers) {
                if (receivesText(viewer.getUniqueId(), i)) {
                    HologramRuntime.sink().text(viewer, current[i], current[i].text());
                }
            }
        }
    }

    /**
     * Renders for one player.
     *
     * <p>Used by per-player holograms, and when a viewer first comes into
     * range: what they are sent has to be resolved for them.
     */
    private void renderFor(Player viewer, boolean send) {
        DisplayState[] current = displays;
        Template[] lines = templates;
        for (int i = 0; i < current.length && i < lines.length; i++) {
            if (current[i].kind() != HologramConfig.Kind.TEXT) {
                continue;
            }
            String text = lines[i].render(viewer, data);
            current[i].text(componentFor(lines[i], text, viewer));
            if (send && receivesText(viewer.getUniqueId(), i)) {
                HologramRuntime.sink().text(viewer, current[i], current[i].text());
            }
        }
    }

    /**
     * Whether a viewer is sent a line's text as it changes: not when the line
     * is hidden from them, and not while it is still being typed for them,
     * which would show it whole too early.
     */
    private boolean receivesText(UUID viewer, int line) {
        long[] started = typing.get(viewer);
        return shown(masks.get(viewer), line)
                && (started == null || line >= started.length || started[line] == 0);
    }

    /** The whole text a viewer sees on a line. */
    private Component textFor(Player viewer, int line) {
        if (!config.perPlayer()) {
            return displays[line].text();
        }
        Template template = templates[line];
        return componentFor(template, template.render(viewer, data), viewer);
    }

    /**
     * Builds the component for a line.
     *
     * <p>The raw template text is what gets parsed, because it never changes
     * and is therefore always a cache hit, and the resolved values are
     * substituted into that parsed component.
     */
    private Component componentFor(Template template, String text, Player viewer) {
        if (!template.isDynamic()) {
            return Text.component(text);
        }
        List<String> pairs = Placeholders.resolvePairs(template,
                new Request(viewer, viewer, List.of(), data));
        if (pairs.isEmpty()) {
            return Text.component(text);
        }
        return Text.component(template.raw(), pairs);
    }

    /** Sends the whole hologram to a player who just came into range. */
    void spawnFor(Player viewer) {
        if (removed) {
            return;
        }
        DisplayState[] current = displays;
        if (config.type() == HologramConfig.Kind.TEXT) {
            // Resolve for this viewer before spawning: a shared hologram still
            // has to show them something, and a per-player one shows them
            // theirs.
            renderFor(viewer, false);
        }
        UUID uuid = viewer.getUniqueId();
        boolean[] mask = conditional ? maskFor(viewer) : null;
        if (mask != null) {
            masks.put(uuid, mask);
        }
        long now = HologramRuntime.now();
        beginTyping(uuid, null, mask, now);
        placeLines(viewer, current, null, mask, now);
        spawnHitbox(viewer, mask);
        Entity vehicle = mount;
        if (vehicle != null) {
            HologramRuntime.sink().mount(viewer, vehicle.getEntityId(), entityIdsWithHitbox());
        }
        viewers.add(uuid);
    }

    /**
     * Re-checks which lines a viewer who stays in range is shown.
     *
     * <p>Called on every visibility pass, and free for holograms without a
     * conditional line. When the set changes, lines that went away are
     * destroyed, new ones are spawned, and the ones that stay are moved to
     * where the new stack puts them, so nothing flickers.
     */
    void revalidate(Player viewer) {
        if (!conditional || removed) {
            return;
        }
        UUID uuid = viewer.getUniqueId();
        boolean[] before = masks.get(uuid);
        if (before == null) {
            return;
        }
        boolean[] after = maskFor(viewer);
        if (Arrays.equals(before, after)) {
            return;
        }
        masks.put(uuid, after);
        DisplayState[] current = displays;
        List<Integer> hidden = new ArrayList<>();
        boolean revealed = false;
        for (int i = 0; i < current.length; i++) {
            if (shown(before, i) && !shown(after, i)) {
                hidden.add(current[i].entityId());
            }
            revealed |= !shown(before, i) && shown(after, i);
        }
        if (!hidden.isEmpty()) {
            HologramRuntime.sink().destroy(viewer,
                    hidden.stream().mapToInt(Integer::intValue).toArray());
        }
        if (revealed && config.type() == HologramConfig.Kind.TEXT) {
            renderFor(viewer, false);
        }
        long now = HologramRuntime.now();
        beginTyping(uuid, before, after, now);
        placeLines(viewer, current, before, after, now);
        if (clickable()) {
            // The box is as tall as what this viewer sees.
            HologramRuntime.sink().destroy(viewer, new int[]{hitboxId});
            spawnHitbox(viewer, after);
        }
    }

    /**
     * Which lines a viewer is shown, by range, permission and condition.
     *
     * <p>Runs off the main thread, like every placeholder in a hologram line.
     */
    private boolean[] maskFor(Player viewer) {
        LineStyle[] current = styles;
        boolean[] mask = new boolean[current.length];
        double distance = viewer.getLocation().distanceSquared(location);
        for (int i = 0; i < current.length; i++) {
            LineStyle style = current[i];
            boolean shown = style.range() <= 0 || distance <= style.range() * style.range();
            String permission = style.permission();
            if (shown && permission != null) {
                boolean negated = permission.startsWith("!");
                boolean has = viewer.hasPermission(negated ? permission.substring(1) : permission);
                shown = negated != has;
            }
            if (shown && style.condition() != null) {
                shown = Conditions.holds(style.condition(), viewer, (subject, message) -> {
                    if (REPORTED.add(subject)) {
                        HologramRuntime.logger().warning("Hologram '" + id + "': " + message);
                    }
                });
            }
            mask[i] = shown;
        }
        return mask;
    }

    private static boolean shown(boolean[] mask, int line) {
        return mask == null || (line < mask.length && mask[line]);
    }

    /**
     * Spawns the lines a viewer gained and moves the ones they already had.
     *
     * @param before what they were shown, or {@code null} when they had nothing
     * @param after  what they are shown now, or {@code null} for every line
     */
    private void placeLines(Player viewer, DisplayState[] current, boolean[] before,
                            boolean[] after, long now) {
        Location at = location;
        double[] heights = heights(current.length);
        UUID uuid = viewer.getUniqueId();
        for (int i = 0; i < current.length; i++) {
            if (!shown(after, i)) {
                continue;
            }
            Location where = lineLocation(at, i, heights, after, current[i]);
            if (before != null && shown(before, i)) {
                HologramRuntime.sink().teleport(viewer, current[i].entityId(), where);
                if (mount != null || hovering.contains(uuid)) {
                    // Riding, a line's place is its translation; hovered, it
                    // is spread by it. Either way the new stack moves it.
                    HologramRuntime.sink().transform(viewer, current[i],
                            viewOf(current[i], i, uuid, heights, after), HOVER_TICKS);
                }
                continue;
            }
            current[i].transform(current[i].transformAt(now));
            Component text = receivesText(uuid, i) ? current[i].text() : Component.empty();
            HologramRuntime.sink().spawn(viewer, current[i], where, text,
                    viewOf(current[i], i, uuid, heights, after));
        }
    }

    /** Starts the typewriter on the typed lines a viewer was just given. */
    private void beginTyping(UUID viewer, boolean[] before, boolean[] after, long now) {
        if (!typed) {
            return;
        }
        LineStyle[] current = styles;
        long[] started = typing.computeIfAbsent(viewer, key -> new long[current.length]);
        if (started.length != current.length) {
            started = new long[current.length];
            typing.put(viewer, started);
        }
        boolean any = false;
        for (int i = 0; i < current.length; i++) {
            if (current[i].typewriter() > 0 && shown(after, i) && !(before != null && shown(before, i))) {
                started[i] = Math.max(1, now);
            }
            any |= started[i] != 0;
        }
        if (!any) {
            typing.remove(viewer);
        }
    }

    /**
     * Moves the animations and the typewriter on, for everybody in range.
     *
     * <p>A transformation is sent once a keyframe, aimed at the next one, and
     * the client glides there on its own: a spinning block costs two packets a
     * second per viewer, not twenty.
     */
    void animate(List<Player> currentViewers, long now) {
        if (removed || currentViewers.isEmpty()) {
            return;
        }
        if (!typing.isEmpty()) {
            type(currentViewers, now);
        }
        if (shimmering) {
            shimmer(currentViewers, now);
        }
        if (!animated) {
            return;
        }
        long frame = now / STEP_MS;
        if (frame == lastFrame) {
            return;
        }
        lastFrame = frame;
        long target = (frame + 1) * STEP_MS;
        DisplayState[] current = displays;
        double[] heights = heights(current.length);
        for (int i = 0; i < current.length; i++) {
            if (!current[i].style().animated()) {
                continue;
            }
            current[i].transform(current[i].transformAt(target));
            for (Player viewer : currentViewers) {
                UUID uuid = viewer.getUniqueId();
                boolean[] mask = masks.get(uuid);
                if (shown(mask, i)) {
                    HologramRuntime.sink().transform(viewer, current[i],
                            viewOf(current[i], i, uuid, heights, mask), STEP_TICKS);
                }
            }
        }
    }

    /**
     * Moves the colour of shining and flowing lines on.
     *
     * <p>A flowing line is re-sent every pass; a shining one only while its
     * band crosses it, plus once to put the plain line back. A shared
     * hologram works the frame out once for everybody.
     */
    private void shimmer(List<Player> currentViewers, long now) {
        DisplayState[] current = displays;
        LineStyle[] lineStyles = styles;
        boolean[] rested = restSent;
        double seconds = now / 1000.0;
        for (int i = 0; i < current.length && i < lineStyles.length && i < rested.length; i++) {
            LineStyle style = lineStyles[i];
            if (!style.shimmers() || current[i].kind() != HologramConfig.Kind.TEXT) {
                continue;
            }
            boolean resting = style.flow() == 0
                    && Shimmer.resting(current[i].text(), seconds);
            if (resting && rested[i]) {
                continue;
            }
            rested[i] = resting;
            Component shared = config.perPlayer() ? null : lit(style, current[i].text(), seconds);
            for (Player viewer : currentViewers) {
                if (!receivesText(viewer.getUniqueId(), i)) {
                    continue;
                }
                Component frame = shared != null ? shared : lit(style, textFor(viewer, i), seconds);
                HologramRuntime.sink().text(viewer, current[i], frame);
            }
        }
    }

    /** A line with its moving colour where it stands at {@code seconds}. */
    private static Component lit(LineStyle style, Component line, double seconds) {
        Component frame = style.flow() != 0 ? Shimmer.flow(line, style.flow(), seconds) : line;
        if (style.shine() != null) {
            Component shone = Shimmer.shine(frame, colour(style.shine()), seconds);
            if (shone != null) {
                frame = shone;
            }
        }
        return frame;
    }

    /** A palette name such as {@code highlight} or {@code {highlight}}, or a hex colour. */
    private static TextColor colour(String value) {
        String token = value.replace("{", "").replace("}", "").trim();
        TextColor parsed = token.startsWith("#") ? TextColor.fromHexString(token) : Colors.get(token);
        return parsed == null ? TextColor.color(0xFFFFFF) : parsed;
    }

    /**
     * How one viewer is shown a display's transformation.
     *
     * <p>Riding an entity, every passenger is drawn at the same point of the
     * vehicle, so the stack and the configured offset can only be built from
     * the translation. Hovered, the whole hologram grows, and the lines are
     * spread by as much so they do not overlap.
     */
    private DisplayState.Transform viewOf(DisplayState display, int line, UUID viewer,
                                          double[] heights, boolean[] mask) {
        double grow = hovering.contains(viewer) ? config.click().hoverScale() : 1;
        double y = lineY(line, heights, mask, display);
        if (mount != null) {
            return display.transform().view(grow, config.offsetX(),
                    config.offsetY() + y * grow, config.offsetZ());
        }
        return display.transform().view(grow, 0, y * (grow - 1), 0);
    }

    /**
     * Grows or shrinks the hologram for a viewer who started or stopped
     * looking at it, so a clickable hologram shows it can be clicked.
     */
    void hover(Player viewer) {
        if (!clickable() || config.click().hoverScale() == 1 || mount != null) {
            return;
        }
        UUID uuid = viewer.getUniqueId();
        boolean[] mask = masks.get(uuid);
        DisplayState[] current = displays;
        double[] heights = heights(current.length);
        boolean looking = looksAt(viewer, total(heights, mask));
        if (looking == hovering.contains(uuid)) {
            return;
        }
        if (looking) {
            hovering.add(uuid);
        } else {
            hovering.remove(uuid);
        }
        for (int i = 0; i < current.length; i++) {
            if (shown(mask, i)) {
                HologramRuntime.sink().transform(viewer, current[i],
                        viewOf(current[i], i, uuid, heights, mask), HOVER_TICKS);
            }
        }
    }

    /** Whether the viewer's line of sight crosses the click box, within reach. */
    private boolean looksAt(Player viewer, double height) {
        Location eye = viewer.getLocation();
        org.bukkit.util.Vector direction = eye.getDirection();
        double[] origin = {eye.getX(), eye.getY() + EYE_HEIGHT, eye.getZ()};
        double[] dir = {direction.getX(), direction.getY(), direction.getZ()};
        double half = config.click().width() / 2;
        Location at = location;
        double[] min = {at.getX() - half, at.getY(), at.getZ() - half};
        double[] max = {at.getX() + half, at.getY() + height, at.getZ() + half};
        double near = 0;
        double far = HOVER_REACH;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(dir[axis]) < 1e-9) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
                    return false;
                }
                continue;
            }
            double a = (min[axis] - origin[axis]) / dir[axis];
            double b = (max[axis] - origin[axis]) / dir[axis];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
            if (near > far) {
                return false;
            }
        }
        return true;
    }

    private static double total(double[] heights, boolean[] mask) {
        double total = 0;
        for (int i = 0; i < heights.length; i++) {
            if (shown(mask, i)) {
                total += heights[i];
            }
        }
        return total;
    }

    private void type(List<Player> currentViewers, long now) {
        DisplayState[] current = displays;
        LineStyle[] lineStyles = styles;
        for (Player viewer : currentViewers) {
            long[] started = typing.get(viewer.getUniqueId());
            if (started == null) {
                continue;
            }
            boolean any = false;
            for (int i = 0; i < started.length && i < current.length && i < lineStyles.length; i++) {
                if (started[i] == 0) {
                    continue;
                }
                Component whole = textFor(viewer, i);
                int count = (int) ((now - started[i]) * lineStyles[i].typewriter() / 1000);
                if (count <= 0) {
                    any = true;
                } else if (count >= Typewriter.length(whole)) {
                    started[i] = 0;
                    HologramRuntime.sink().text(viewer, current[i], whole);
                } else {
                    any = true;
                    HologramRuntime.sink().text(viewer, current[i], Typewriter.cut(whole, count));
                }
            }
            if (!any) {
                typing.remove(viewer.getUniqueId());
            }
        }
    }

    // ------------------------------------------------------------------
    // Clicks
    // ------------------------------------------------------------------

    boolean clickable() {
        return !clickLines.isEmpty() || clickHandler != null;
    }

    int hitboxId() {
        return hitboxId;
    }

    private void spawnHitbox(Player viewer, boolean[] mask) {
        if (!clickable()) {
            return;
        }
        double total = total(heights(displays.length), mask);
        // Riding, the box sits where the vehicle carries its passengers, so it
        // also has to reach up over the offset to cover the lines.
        if (mount != null) {
            total += Math.max(0, config.offsetY());
        }
        HologramRuntime.sink().hitbox(viewer, hitboxId, location,
                (float) config.click().width(), (float) Math.max(total, 0.25));
    }

    /**
     * A player clicked the box.
     *
     * <p>Arrives on the network thread. The lines find their own thread, and
     * the plugin's handler is handed to the player's.
     */
    void clicked(Player player, Click click) {
        UUID uuid = player.getUniqueId();
        if (removed || !clickable() || !viewers.contains(uuid)) {
            return;
        }
        long now = HologramRuntime.now();
        Long last = lastClick.get(uuid);
        if (last != null && now - last < Math.max(MIN_CLICK_GAP_MS, config.click().cooldown())) {
            return;
        }
        lastClick.put(uuid, now);
        if (clickLines.handles(click)) {
            clickLines.run(player, click, data);
        }
        BiConsumer<Player, Click> handler = clickHandler;
        if (handler != null) {
            Tasks.of(owner).runAtEntity(player, () -> handler.accept(player, click));
        }
    }

    // ------------------------------------------------------------------
    // Layout and lifecycle
    // ------------------------------------------------------------------

    /** Takes the hologram off a player's screen. */
    void despawnFor(Player viewer) {
        UUID uuid = viewer.getUniqueId();
        masks.remove(uuid);
        typing.remove(uuid);
        lastClick.remove(uuid);
        hovering.remove(uuid);
        if (viewers.remove(uuid)) {
            HologramRuntime.sink().destroy(viewer, entityIdsWithHitbox());
        }
    }

    /** Forgets a player who left, without sending them anything. */
    void forget(UUID viewer) {
        viewers.remove(viewer);
        masks.remove(viewer);
        typing.remove(viewer);
        lastClick.remove(viewer);
        hovering.remove(viewer);
    }

    /** Moves every display for everybody who can see it. */
    void teleportFor(List<Player> currentViewers) {
        DisplayState[] current = displays;
        Location at = location;
        double[] heights = heights(current.length);
        for (Player viewer : currentViewers) {
            boolean[] mask = masks.get(viewer.getUniqueId());
            for (int i = 0; i < current.length; i++) {
                if (shown(mask, i)) {
                    HologramRuntime.sink().teleport(viewer, current[i].entityId(),
                            lineLocation(at, i, heights, mask, current[i]));
                }
            }
            if (clickable()) {
                HologramRuntime.sink().teleport(viewer, hitboxId, at);
            }
        }
    }

    /**
     * Where a line stands.
     *
     * <p>Lines are stacked downwards from the anchor, so the first line is at
     * the top and adding one grows the hologram down rather than moving what
     * was already there. Each line takes the room its size gives it, so a
     * bigger line pushes the ones above it up instead of overlapping them, and
     * a line a viewer is not shown takes no room for them. A text or block
     * display grows up from its position and an item one around it, so an item
     * stands half its height higher.
     */
    private static Location lineLocation(Location anchor, int index, double[] heights,
                                         boolean[] mask, DisplayState display) {
        return anchor.clone().add(0, lineY(index, heights, mask, display), 0);
    }

    /** How high above the anchor a line stands. */
    private static double lineY(int index, double[] heights, boolean[] mask, DisplayState display) {
        double y = 0;
        for (int i = index + 1; i < heights.length; i++) {
            if (shown(mask, i)) {
                y += heights[i];
            }
        }
        if (display.kind() == HologramConfig.Kind.ITEM && index < heights.length) {
            y += heights[index] / 2;
        }
        return y;
    }

    /** Every entity a viewer may have been sent, click box included when there is one. */
    private int[] entityIdsWithHitbox() {
        int[] ids = entityIds();
        if (!clickable()) {
            return ids;
        }
        int[] all = Arrays.copyOf(ids, ids.length + 1);
        all[ids.length] = hitboxId;
        return all;
    }

    /** The room each display takes; the bottom-up stacking reads this. */
    private double[] heights(int count) {
        double[] heights = new double[count];
        LineStyle[] current = styles;
        HologramConfig.Properties base = config.properties();
        for (int i = 0; i < count; i++) {
            LineStyle style = config.type() == HologramConfig.Kind.TEXT && i < current.length
                    ? current[i] : LineStyle.PLAIN;
            heights[i] = style.height(base);
        }
        return heights;
    }

    int[] entityIds() {
        DisplayState[] current = displays;
        int[] ids = new int[current.length];
        for (int i = 0; i < current.length; i++) {
            ids[i] = current[i].entityId();
        }
        return ids;
    }

    /** Replaces the displays, for a hologram whose lines changed shape. */
    void rebuildDisplays() {
        this.displays = build(config, styles);
        this.lastText = new String[displays.length];
        refresh();
    }

    void lastViewers(List<Player> found) {
        lastViewers = found;
    }

    /** The last visibility check's players still being shown it: one may have quit or been hidden since. */
    List<Player> lastViewers() {
        List<Player> found = lastViewers;
        return found.stream().filter(player -> viewers.contains(player.getUniqueId())).toList();
    }

    Set<UUID> viewerIds() {
        return viewers;
    }

    Entity mount() {
        return mount;
    }

    HologramConfig config() {
        return config;
    }

    boolean ownedBy(String plugin) {
        return ownerName.equals(plugin);
    }

    void markRemoved() {
        removed = true;
    }

    /** Drops what viewers were sent, so they are re-sent in full. */
    void invalidate() {
        this.lastText = new String[displays.length];
        refresh();
    }
}
