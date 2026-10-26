package net.exylia.lib.stage;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Displays;
import net.exylia.lib.display.PluginDisplays;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.stage.kit.CardSprite;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What one player sees on an in-world screen: pieces drawn on a {@link StagePlane} in front of
 * them, as packets only they receive, all taken down together.
 *
 * <pre>{@code
 * StagePlane plane = StagePlane.inFrontOf(player.getLocation(), 1.62, 2.5, 0, 3, 3, 0.5, 0.45);
 * Stage stage = new Stage(player, plane, Displays.of(this), 120_000L, theme);
 * stage.table(0, 0, 1.8, 1.8);                                 // first: it also sizes the board
 * stage.heading(Text.component("{primary}&lPICK ONE"), Text.component("{letters}Look and click"), 1.05);
 * StageButton ok = stage.button(Text.component("{success}&lOK"), 0, -0.6, 0.7, 0.22, 200);
 *
 * // every couple of ticks, on the player's thread
 * ok.hover(ok.under(player));
 * ...
 * stage.close();
 * }</pre>
 *
 * <p>Not only for games: a tutorial panel, a 3D selector or a confirmation screen is a stage with a
 * table, a heading and a few buttons. A plugin's own pieces (a string of lights, a spinning reel)
 * are drawn with {@link #show}, {@link #block}, {@link #item} and {@link #text}, posed with
 * {@link #pose}, and taken down with {@link #remove} or with the rest on {@link #close}. A subclass
 * can decorate every table it draws by overriding {@link #framed}.
 *
 * <h2>Threads and lifetime</h2>
 * A stage is used from one thread at a time: the one that runs the screen (the viewer's, or the
 * region the screen stands in on Folia). Drawing sends packets and is safe from that thread; nothing
 * here touches the world. Every piece lives at most {@code life} milliseconds, so a stage nobody
 * closes still goes by itself; and everything a plugin drew goes when that plugin is disabled.
 *
 * <h2>Watched</h2>
 * A screen is its viewer's alone unless others are let in: {@link #watchers} shows every stage a
 * player has open, and every one opened for them later, to other players too, as at a table that
 * stands in the world with people round it; {@link #watchedWithin} lets in whoever is near, and is
 * called again as they come and go. Only the viewer's clicks, hovers and sounds count: the others
 * only look.
 *
 * @since 1.356.0
 */
public class Stage implements CardSprite.Canvas {

    /** A text display's line is about a quarter of a block tall at scale 1. */
    public static final double TEXT_HEIGHT = 0.25;
    /** The verdict's largest text, and the widest it may run. */
    private static final double VERDICT_SCALE = 0.85;
    private static final double VERDICT_WIDTH = 2.3;
    /** How far the frame reaches past the table's edge. */
    private static final double FRAME_REACH = 0.06;
    /** How far a heading, or the verdict banner that replaces it, reaches over the table's top edge. */
    private static final double HEADROOM = 0.55;
    /** The stages each player has open, so what follows a screen (a panel under it) can find their edges. */
    private static final Map<UUID, Set<Stage>> OPEN = new ConcurrentHashMap<>();
    /** Everyone who sees a player's screens, the player first; absent for the player alone. */
    private static final Map<UUID, List<Player>> WATCHED = new ConcurrentHashMap<>();
    /** Every stage drawn for a player and not closed, so watchers who arrive are shown it. */
    private static final Map<UUID, Set<Stage>> LIVE = new ConcurrentHashMap<>();

    /** How something ended: the colour the verdict banner is outlined in. */
    public enum Outcome {
        WIN, LOSE, DRAW;

        public static @NotNull Outcome of(boolean draw, boolean won) {
            return draw ? DRAW : won ? WIN : LOSE;
        }
    }

    protected final Player viewer;
    protected final StagePlane plane;
    protected final PluginDisplays displays;
    protected final long life;
    protected final Rotation facing;
    protected final StageTheme theme;
    private volatile List<Player> audience;
    private final List<DisplayHandle> pieces = new ArrayList<>();
    private final List<StageButton> buttons = new ArrayList<>();
    private DisplayHandle header;
    private DisplayHandle title;
    private DisplayHandle info;
    private double headingUp = Double.NaN;
    private Location foot;

    /** A stage in the library's {@linkplain StageTheme#defaults() default look}. */
    public Stage(@NotNull Player viewer, @NotNull StagePlane plane, @NotNull PluginDisplays displays, long life) {
        this(viewer, plane, displays, life, StageTheme.defaults());
    }

    /**
     * @param viewer   the one player who sees it
     * @param plane    the plane it is drawn on
     * @param displays the plugin's displays, so everything goes when the plugin is disabled
     * @param life     the longest any piece lives, in milliseconds
     * @param theme    its look
     */
    public Stage(@NotNull Player viewer, @NotNull StagePlane plane, @NotNull PluginDisplays displays, long life,
                 @NotNull StageTheme theme) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.plane = Objects.requireNonNull(plane, "plane");
        this.displays = Objects.requireNonNull(displays, "displays");
        this.life = life;
        this.theme = Objects.requireNonNull(theme, "theme");
        this.facing = StageLook.facing(plane);
        this.audience = WATCHED.getOrDefault(viewer.getUniqueId(), List.of(viewer));
        LIVE.computeIfAbsent(viewer.getUniqueId(), id -> java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()))).add(this);
    }

    /**
     * Shows a player's screens to other players as well, from now on: every stage they have open, in
     * the pose it is in, and every stage drawn for them later. An empty list gives them their screens
     * back to themselves. Call it on the thread their stages are drawn from.
     *
     * @param viewer the player whose screens are watched
     * @param others who else sees them; the viewer is left out if listed
     * @since 1.367.0
     */
    public static void watchers(@NotNull Player viewer, @NotNull java.util.Collection<? extends Player> others) {
        List<Player> everyone = new ArrayList<>(others.size() + 1);
        everyone.add(viewer);
        for (Player other : others) {
            if (other != null && !other.getUniqueId().equals(viewer.getUniqueId()) && !everyone.contains(other)) {
                everyone.add(other);
            }
        }
        List<Player> now = List.copyOf(everyone);
        UUID id = viewer.getUniqueId();
        List<Player> before = WATCHED.getOrDefault(id, List.of(viewer));
        if (new java.util.HashSet<>(before).equals(new java.util.HashSet<>(now))) return;
        if (now.size() == 1) WATCHED.remove(id);
        else WATCHED.put(id, now);
        Set<Stage> live = LIVE.get(id);
        if (live == null) return;
        List<Stage> stages;
        synchronized (live) {
            stages = List.copyOf(live);
        }
        for (Stage stage : stages) {
            stage.audience = now;
            List.copyOf(stage.pieces).forEach(piece -> piece.viewers(now));
        }
    }

    /**
     * Lets whoever stands within {@code radius} blocks of a player watch their screens, and nobody
     * else: call it again every half second or so, from the player's thread, as people come and go.
     * A player some plugin holds in a {@linkplain net.exylia.lib.session.Sessions session} (playing a
     * game of their own, perhaps in a room of their own) is not let in.
     *
     * @since 1.367.0
     */
    public static void watchedWithin(@NotNull Player viewer, double radius) {
        watchers(viewer, viewer.getLocation().getNearbyPlayers(radius).stream()
                .filter(other -> net.exylia.lib.session.Sessions.holder(other).isEmpty()).toList());
    }

    /** Who sees this stage: its viewer, and whoever {@link #watchers} let in. */
    public @NotNull List<Player> audience() {
        return audience;
    }

    @Override
    public DisplayHandle draw(DisplayModel model, DisplayMotion motion, Location at) {
        return show(model, motion, at);
    }

    @Override
    public void erase(DisplayHandle handle) {
        remove(handle);
    }

    @Override
    public Rotation facing() {
        return facing;
    }

    @Override
    public long life() {
        return life;
    }

    public @NotNull Player viewer() {
        return viewer;
    }

    public @NotNull StagePlane plane() {
        return plane;
    }

    public @NotNull StageTheme theme() {
        return theme;
    }

    /** A block, sized and lit, facing the seat. */
    public @Nullable DisplayHandle block(BlockData block, Location at, double width, double height, double depth) {
        return show(DisplayModel.block(block).light(15), StageLook.still(facing, width, height, depth, life), at);
    }

    /** An item, facing the seat. */
    public @Nullable DisplayHandle item(ItemStack item, Location at, DisplayMotion motion) {
        return show(DisplayModel.item(item).light(15), motion, at);
    }

    /**
     * Text, facing the seat and centred on {@code at}: a FIXED billboard turned with the board, so it
     * stands still instead of swinging with the camera.
     */
    public @Nullable DisplayHandle text(Component content, Location at, double scale, DisplayMotion motion) {
        return show(textModel(content).background(0).textShadow(false), motion, at.clone().add(0, -TEXT_HEIGHT * scale / 2, 0));
    }

    /** A still pose for a piece of this stage, uniformly sized. */
    public @NotNull DisplayMotion still(double scale) {
        return StageLook.still(facing, scale, scale, scale, life);
    }

    /** A pose that pops in. */
    public @NotNull DisplayMotion pop(double scale) {
        return StageLook.pop(facing, scale, life);
    }

    /**
     * A pose {@code across}, {@code up} and {@code forward} on the board from where a piece was drawn,
     * turned to face the seat: what a glide ({@code moveTo}, or a path of keyframes) is written in,
     * since a display's offsets are in the world's axes, not the board's.
     */
    public @NotNull DisplayKeyframe pose(long millis, double across, double up, double forward, double x, double y, double z) {
        float[] offset = facing.apply(new float[]{(float) across, (float) up, (float) forward});
        return new DisplayKeyframe(millis, offset[0], offset[1], offset[2], facing, (float) x, (float) y, (float) z);
    }

    /** The lines over the board; drawn the first time, rewritten afterwards. */
    public void header(Component lines, double up) {
        if (header != null) {
            header.text(lines);
            return;
        }
        header = show(textModel(lines).background(0x60000000).lineWidth(260),
                StageLook.still(facing, 0.8, 0.8, 0.8, life), plane.point(0, up, 0));
    }

    /** Rewrites the header, once it is drawn. */
    public void status(Component lines) {
        if (header != null) header.text(lines);
    }

    /**
     * The heading over a board: its name large, and one small line of figures under it. Two pieces
     * rather than one wrapped block, so neither ever breaks across lines.
     */
    public void heading(Component title, Component figures, double up) {
        headingUp = up;
        this.title = show(textModel(title).background(0).lineWidth(1000), StageLook.pop(facing, 0.75, 0.75, 0.75, 0, life),
                plane.point(0, up + 0.16, 0.02));
        info = show(textModel(figures).background(0x50000000).lineWidth(1000),
                StageLook.pop(facing, 0.55, 0.55, 0.55, 120, life), plane.point(0, up, 0.02));
    }

    /** Rewrites the small line under the heading. */
    public void figures(Component figures) {
        if (info != null) info.text(figures);
    }

    /**
     * A finished verdict: a solid plaque outlined in the outcome's colour slams in over the heading,
     * the words large on it, and the heading shrinks away behind it. It sits where no piece ever is,
     * so no card, tile or glow outline crosses it. Fitted to its text; one short line. Does nothing on
     * a stage without a {@link #heading}, which has no free space above it to take the banner.
     *
     * @param delay how long it waits before it comes
     */
    public void verdict(Component text, Outcome outcome, long delay) {
        if (Double.isNaN(headingUp)) return;
        double scale = StageLook.fit(text, VERDICT_WIDTH, VERDICT_SCALE);
        String plain = PlainTextComponentSerializer.plainText().serialize(text);
        double width = Math.max(0.18, StageLook.width(plain)) * scale + 0.34;
        double height = TEXT_HEIGHT * scale + 0.16;
        // Its foot on the heading's figures line, so it grows up over the title and never down onto the board.
        double up = headingUp - 0.03 + height / 2;
        String colour = switch (outcome) {
            case WIN -> theme.verdictWin();
            case LOSE -> theme.verdictLose();
            case DRAW -> theme.verdictDraw();
        };
        int glow = StageLook.rgb(colour, outcome == Outcome.WIN ? 0x8FFFC1 : outcome == Outcome.LOSE ? 0xA33B53 : 0xFF9500);
        double[] sizes = {0.01, 0.01, 1.5, 0.93, 1.03, 1.0};
        long[] times = {0, delay, delay + 70, delay + 210, delay + 330, delay + 420};
        double[] forward = {0.25, 0.25, 0.25, 0, 0, 0};
        List<DisplayKeyframe> plate = new ArrayList<>();
        List<DisplayKeyframe> words = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) {
            double f = sizes[i];
            plate.add(pose(times[i], 0, 0, forward[i], width * f, height * f, 0.03));
            double size = scale * f;
            words.add(pose(times[i], 0, -TEXT_HEIGHT * (size - scale) / 2, forward[i], size, size, size));
        }
        show(DisplayModel.block(StageLook.block(theme.verdictBlock(), Material.BLACK_CONCRETE)).light(15).glow(glow),
                DisplayMotion.of(plate, life), plane.point(0, up, 0.06));
        show(textModel(text).background(0).textShadow(true), DisplayMotion.of(words, life),
                plane.point(0, up - TEXT_HEIGHT * scale / 2, 0.1));
        shrink(title, delay + 70);
        shrink(info, delay + 70);
    }

    /** A piece shrinks away to nothing at {@code at}, from wherever it stands. */
    private void shrink(DisplayHandle piece, long at) {
        if (piece == null) return;
        DisplayKeyframe here = piece.pose();
        if (here == null) {
            remove(piece);
            return;
        }
        float tiny = 0.01f;
        piece.then(DisplayMotion.of(List.of(here,
                new DisplayKeyframe(at, here.x(), here.y(), here.z(), here.rotation(), here.scaleX(), here.scaleY(), here.scaleZ()),
                new DisplayKeyframe(at + 90, here.x(), here.y(), here.z(), here.rotation(), tiny, tiny, tiny)), life));
    }

    /**
     * The table behind a board and its glowing frame, sized to it and centred on {@code (across, up)}.
     *
     * <p>The first table of a stage also sizes it: the plane is brought nearer by the theme's zoom, as
     * far as it still fits the view with its heading (or a verdict banner) over it. So call this before
     * anything reads the plane ({@code plane.point}, {@code cell}, {@code grid}): a point taken before it
     * is where the board was before the zoom.
     *
     * @param width  the table's width
     * @param height its height
     */
    public void table(double across, double up, double width, double height) {
        plane.fit(across - width / 2, across + width / 2, up - height / 2, up + height / 2 + HEADROOM,
                theme.zoom(), theme.maxWide(), theme.maxTall(), theme.nearest());
        show(DisplayModel.block(StageLook.block(theme.tableBlock(), Material.BLACK_CONCRETE)).light(15),
                StageLook.pop(facing, width, height, 0.02, 0, life), plane.point(across, up, -0.09));
        double bar = 0.035;
        int glow = StageLook.rgb(theme.frameGlow(), 0x8A51C4);
        DisplayModel frame = DisplayModel.block(StageLook.block(theme.frameBlock(), Material.PURPLE_CONCRETE)).light(15).glow(glow);
        show(frame, StageLook.pop(facing, width + bar, bar, 0.04, 80, life), plane.point(across, up + height / 2, -0.07));
        show(frame, StageLook.pop(facing, width + bar, bar, 0.04, 80, life), plane.point(across, up - height / 2, -0.07));
        show(frame, StageLook.pop(facing, bar, height + bar, 0.04, 80, life), plane.point(across - width / 2, up, -0.07));
        show(frame, StageLook.pop(facing, bar, height + bar, 0.04, 80, life), plane.point(across + width / 2, up, -0.07));
        framed(across, up, width, height);
        Location edge = plane.point(across, up - height / 2 - FRAME_REACH, 0);
        if (foot == null || edge.getY() < foot.getY()) foot = edge;
        OPEN.computeIfAbsent(viewer.getUniqueId(), id -> ConcurrentHashMap.newKeySet()).add(this);
    }

    /**
     * Called once a {@link #table} and its frame are drawn: where a subclass adds its own decoration
     * to every table (lights along the frame, corner pieces). Does nothing by default.
     */
    protected void framed(double across, double up, double width, double height) {
    }

    /** The bottom edges, frame included, of the tables a player has in front of them. */
    public static @NotNull List<Location> feet(@NotNull Player player) {
        Set<Stage> open = OPEN.get(player.getUniqueId());
        if (open == null) return List.of();
        return open.stream().map(stage -> stage.foot).filter(Objects::nonNull).toList();
    }

    /**
     * Takes down the buttons of every stage a player has open: a finished screen's buttons have nothing
     * left to do, and must not stand under what comes after it.
     */
    public static void retire(@NotNull Player player) {
        retire(player, null);
    }

    /** As {@link #retire(Player)}, but one button stays: the one the next panel takes over. */
    public static void retire(@NotNull Player player, @Nullable StageButton keep) {
        Set<Stage> open = OPEN.get(player.getUniqueId());
        if (open == null) return;
        for (Stage stage : open) {
            for (StageButton button : stage.buttons) {
                if (button != keep) button.remove();
            }
            stage.buttons.removeIf(button -> button != keep);
        }
    }

    /**
     * A winning piece: outlined in {@code glow}, swelling a little past its pose and settling, then
     * beating by {@code grow} until the stage is taken down.
     */
    public void pulse(DisplayHandle piece, int glow, double grow) {
        if (piece == null) return;
        piece.glow(glow);
        DisplayKeyframe here = piece.pose();
        if (here != null) piece.then(StageLook.pulse(here, grow, 260L, 520L, life));
    }

    /** A button on this stage, taken down with it or by {@link #retire}. */
    public @NotNull StageButton button(Component label, double across, double up, double width, double height, long delay) {
        StageButton button = new StageButton(this, label, across, up, width, height, delay);
        buttons.add(button);
        return button;
    }

    /**
     * A win: the theme's item rains over the board and fireworks go off that only this player sees.
     *
     * @param up how high over the board's centre the rain starts
     */
    public void celebrate(double up) {
        Material material = theme.rainItem() == null || theme.rainItem().isBlank() ? null : Material.matchMaterial(theme.rainItem());
        if (material != null && material.isItem()) {
            ItemStack drop = new ItemStack(material);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < theme.rainCount(); i++) {
                double across = random.nextDouble(-1.1, 1.1);
                double scale = random.nextDouble(0.22, 0.38);
                DisplayMotion fall = DisplayMotion.builder().life(random.nextLong(1100, 1700))
                        .from(0, random.nextDouble(0, 0.5), 0).to(random.nextDouble(-0.3, 0.3), -1.4, random.nextDouble(0.2, 0.8))
                        .gravity(2.5)
                        .rotation(facing).scale(scale, scale)
                        .spin(random.nextDouble(-2, 2), random.nextDouble(-3, 3), random.nextDouble(-2, 2))
                        .build();
                show(DisplayModel.item(drop).light(15), fall, plane.point(across, up, 0.2));
            }
        }
        if (theme.fireworks()) {
            List<String> colours = theme.winColours().isEmpty() ? List.of("{highlight}") : theme.winColours();
            for (int i = 0; i < 2; i++) {
                var firework = Effects.firework().at(plane.point(i == 0 ? -0.9 : 0.9, up + 0.6, -0.6))
                        .shape(i == 0 ? "BALL_LARGE" : "BURST").instant();
                for (String colour : colours) firework.colour(StageLook.hex(colour));
                firework.fade(StageLook.hex(colours.getFirst()));
                audience.forEach(firework::launchAt);
            }
        }
    }

    /** A loss: a puff of smoke over the board. */
    public void deflate(double up) {
        var smoke = Effects.particle("SMOKE").at(plane.point(0, up, 0.3)).count(25).spread(0.6, 0.3, 0.1).speed(0.02);
        audience.forEach(smoke::show);
    }

    /** Takes one piece down before the rest. */
    public void remove(@Nullable DisplayHandle piece) {
        if (piece == null) return;
        piece.remove();
        pieces.remove(piece);
    }

    /** Takes everything down; the stage can be drawn on again afterwards. */
    public void close() {
        pieces.forEach(DisplayHandle::remove);
        pieces.clear();
        buttons.clear();
        header = null;
        title = null;
        info = null;
        forget(this);
        LIVE.computeIfPresent(viewer.getUniqueId(), (id, live) -> {
            live.remove(this);
            return live.isEmpty() ? null : live;
        });
    }

    /**
     * Draws a piece of this stage for its viewer, kept so {@link #close} takes it down. The primitive
     * every other method draws with, and what a plugin's own decorations draw with too.
     *
     * @return the piece, or {@code null} when it cannot be shown (no PacketEvents, budget full)
     */
    public @Nullable DisplayHandle show(DisplayModel model, DisplayMotion motion, Location at) {
        // See-through text skips the depth buffer, so up to 1.21.11 the table drawn after it paints over it;
        // and those clients draw a translucent panel sky blue over a black table, so text goes bare.
        if (model.kind() == DisplayModel.Kind.TEXT) model = model.seeThrough(false).background(0);
        DisplayHandle handle = displays.show(model, motion, at, audience);
        if (handle == null) return null;
        pieces.add(handle);
        return handle;
    }

    /** A text model for a stage: a FIXED billboard, fully lit. */
    public static @NotNull DisplayModel textModel(Component content) {
        return DisplayModel.text(content).billboard("FIXED").light(15);
    }

    private static void forget(Stage stage) {
        OPEN.computeIfPresent(stage.viewer.getUniqueId(), (id, open) -> {
            open.remove(stage);
            return open.isEmpty() ? null : open;
        });
    }

    /**
     * Forgets every stage a plugin left open, before its displays are released. Called by the library
     * when the plugin is disabled; consumers never call it.
     */
    public static void release(@NotNull Plugin plugin) {
        PluginDisplays mine = Displays.of(plugin);
        for (Set<Stage> open : OPEN.values()) {
            open.stream().filter(stage -> stage.displays == mine).toList().forEach(Stage::forget);
        }
    }

    /** Forgets every stage, for shutdown. */
    public static void releaseAll() {
        OPEN.clear();
        WATCHED.clear();
        LIVE.clear();
    }
}
