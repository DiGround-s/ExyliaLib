package net.exylia.lib.hologram.internal;

import net.exylia.lib.hologram.Hologram;
import net.exylia.lib.hologram.HologramConfig;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.Request;
import net.exylia.lib.placeholder.Template;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 */
final class HologramImpl implements Hologram {

    private static final long TICK_MS = 50L;

    private final String id;
    private final String ownerName;
    private final HologramConfig config;

    /** One display per line for text, a single one for items and blocks. */
    private volatile DisplayState[] displays;
    private volatile Template[] templates;

    /** What each display currently shows, for the diff. */
    private volatile String[] lastText;

    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

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

    HologramImpl(String id, String ownerName, HologramConfig config, Location location) {
        this.id = id;
        this.ownerName = ownerName;
        this.config = config;
        this.location = location.clone().add(config.offsetX(), config.offsetY(), config.offsetZ());
        this.intervalMs = Math.max(TICK_MS, config.config().updateInterval() * TICK_MS);
        this.templates = compile(config.lines());
        this.displays = build(config, templates.length);
        this.lastText = new String[displays.length];
        this.dynamic = config.config().autoUpdate() && isDynamic(templates);
        this.lastRender = -intervalMs;
    }

    private static Template[] compile(List<String> lines) {
        Template[] templates = new Template[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            templates[i] = Placeholders.compile(lines.get(i) == null ? "" : lines.get(i));
        }
        return templates;
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
     * single display.
     */
    private static DisplayState[] build(HologramConfig config, int lineCount) {
        if (config.type() != HologramConfig.Kind.TEXT) {
            Material material = material(config);
            return new DisplayState[]{
                    new DisplayState(HologramRuntime.newEntityId(), config.type(),
                            config.properties(), material)};
        }
        DisplayState[] displays = new DisplayState[lineCount];
        for (int i = 0; i < lineCount; i++) {
            displays[i] = new DisplayState(HologramRuntime.newEntityId(),
                    HologramConfig.Kind.TEXT, config.properties());
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
        String name = config.type() == HologramConfig.Kind.ITEM ? config.item() : config.block();
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
        // A different number of lines means different displays, so viewers have
        // to be given the new ones rather than an update for entities that no
        // longer exist.
        boolean sameShape = compiled.length == templates.length
                && config.type() == HologramConfig.Kind.TEXT;
        this.templates = compiled;
        // Lines are replaced, so what they resolve from is decided again: text
        // that had a placeholder may no longer have one, and the other way
        // round.
        this.dynamic = config.config().autoUpdate() && isDynamic(compiled);
        if (sameShape) {
            refresh();
            return;
        }
        HologramRuntime.rebuild(this, compiled.length);
    }

    @Override
    public void refresh() {
        pending = true;
        lastRender = -intervalMs;
    }

    @Override
    public void updateData(@NotNull Map<String, Object> data) {
        this.data = data == null ? Map.of() : data;
        refresh();
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
            String text = lines[i].render(null, data);
            if (text.equals(previous[i])) {
                continue;
            }
            previous[i] = text;
            current[i].text(componentFor(lines[i], text, null));
            for (Player viewer : currentViewers) {
                HologramRuntime.sink().text(viewer, current[i], current[i].text());
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
            String text = lines[i].render(viewer, data);
            current[i].text(componentFor(lines[i], text, viewer));
            if (send) {
                HologramRuntime.sink().text(viewer, current[i], current[i].text());
            }
        }
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
        Location at = location;
        double spacing = config.properties().lineSpacing();
        for (int i = 0; i < current.length; i++) {
            HologramRuntime.sink().spawn(viewer, current[i], lineLocation(at, i, spacing, current.length));
        }
        Entity vehicle = mount;
        if (vehicle != null) {
            HologramRuntime.sink().mount(viewer, vehicle.getEntityId(), entityIds());
        }
        viewers.add(viewer.getUniqueId());
    }

    /** Takes the hologram off a player's screen. */
    void despawnFor(Player viewer) {
        if (viewers.remove(viewer.getUniqueId())) {
            HologramRuntime.sink().destroy(viewer, entityIds());
        }
    }

    /** Moves every display for everybody who can see it. */
    void teleportFor(List<Player> currentViewers) {
        DisplayState[] current = displays;
        Location at = location;
        double spacing = config.properties().lineSpacing();
        for (Player viewer : currentViewers) {
            for (int i = 0; i < current.length; i++) {
                HologramRuntime.sink().teleport(viewer, current[i],
                        lineLocation(at, i, spacing, current.length));
            }
        }
    }

    /**
     * Where a line stands.
     *
     * <p>Lines are stacked downwards from the anchor, so the first line is at
     * the top and adding one grows the hologram down rather than moving what
     * was already there.
     */
    private static Location lineLocation(Location anchor, int index, double spacing, int count) {
        return anchor.clone().add(0, (count - 1 - index) * spacing, 0);
    }

    int[] entityIds() {
        DisplayState[] current = displays;
        int[] ids = new int[current.length];
        for (int i = 0; i < current.length; i++) {
            ids[i] = current[i].entityId();
        }
        return ids;
    }

    /** Replaces the displays, for a hologram whose line count changed. */
    void rebuildDisplays(int lineCount) {
        this.displays = build(config, lineCount);
        this.lastText = new String[displays.length];
        refresh();
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
