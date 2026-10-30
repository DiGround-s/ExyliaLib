package net.exylia.lib.spatial.crate;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Displays;
import net.exylia.lib.display.PluginDisplays;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.item.Item;
import net.exylia.lib.npc.NpcHandle;
import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcMotion;
import net.exylia.lib.npc.Npcs;
import net.exylia.lib.overlay.Overlays;
import net.exylia.lib.overlay.PluginOverlays;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.ragdoll.RagdollModel;
import net.exylia.lib.ragdoll.internal.SkinCache;
import net.exylia.lib.spatial.Hotspot;
import net.exylia.lib.spatial.SpatialInput;
import net.exylia.lib.spatial.SpatialMenus;
import net.exylia.lib.spatial.SpatialPointer;
import net.exylia.lib.spatial.SpatialPreview;
import net.exylia.lib.spatial.SpatialTheme;
import net.exylia.lib.spatial.internal.Geometry;
import net.exylia.lib.stage.Stage;
import net.exylia.lib.stage.StageLook;
import net.exylia.lib.stage.StagePlane;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.sequence.SequenceRun;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.exylia.lib.util.sequence.Sequences;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One crate opening playing for one player: built in front of them, run from their pointer's poll (every
 * two ticks, on their thread), taken down when it ends. Every prize is handed over exactly once: as its
 * reel lands, or at once when the scene ends early.
 */
final class SceneSession {

    static final double EYES = 1.62;
    /** The longest any piece lives; a scene nobody ends still goes by itself. */
    private static final long LIFE = 10 * 60_000L;
    private static final int GUI_CONTEXT = 6;
    private static final long POP_MILLIS = 450L;
    private static final long PREVIEW_AFTER = 700L;
    /** How far in front of the board the crate stands, and the reel's faces. */
    private static final double CRATE_FORWARD = 0.35;
    private static final double FACE_FORWARD = 0.1;
    private static final double BULGE = 0.16;

    private enum Phase { INTRO, SPIN, REVEAL, DONE }

    final CrateScene owner;
    private final Player viewer;
    private final CrateShow show;
    private final CrateSceneLayout layout;
    private final SpatialTheme theme;
    private final double distance;
    private final StagePlane board;
    private final Rotation facing;
    private final PluginDisplays displays;
    private final List<CrateFace> faces;
    private final List<CrateFace> prizes;
    private final CrateReveal[] revealed;
    private SpatialPointer pointer;
    private String hotbarId = "";

    /** Every piece drawn, so nothing is left behind. */
    private final List<DisplayHandle> pieces = new ArrayList<>();
    private final List<DisplayHandle> crate = new ArrayList<>();
    private final List<DisplayHandle> reel = new ArrayList<>();
    private final List<DisplayHandle> words = new ArrayList<>();
    private final List<Hotspot> hotspots = new ArrayList<>();
    private @Nullable DisplayHandle body;
    private @Nullable DisplayHandle lid;
    private @Nullable DisplayHandle counter;
    private @Nullable DisplayHandle winner;
    private final List<DisplayHandle> markers = new ArrayList<>();
    private final Stand stand = new Stand();

    private ReelPath path;
    private List<Integer> strip = List.of();
    private int onMarker = -1;
    private Phase phase = Phase.INTRO;
    private long phaseStart;
    private long previewAt;
    private int current;
    private boolean closed;

    SceneSession(CrateScene owner, Player viewer, CrateShow show, CrateSceneLayout layout, Location feet, double distance) {
        this.owner = owner;
        this.viewer = viewer;
        this.show = show;
        this.layout = layout;
        this.theme = layout.theme();
        this.distance = distance;
        this.board = StagePlane.inFrontOf(feet, EYES, distance, 0, 1, 1, 1, 1);
        this.facing = StageLook.facing(board);
        this.displays = Displays.of(owner.plugin);
        this.prizes = List.copyOf(show.prizes());
        this.faces = show.faces().isEmpty() ? prizes : List.copyOf(show.faces());
        this.revealed = new CrateReveal[prizes.size()];
        CrateSceneLayout.Reel r = layout.reel();
        this.path = new ReelPath(r.faces(), r.slots(), (long) (r.seconds() * 1000));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    void open() {
        SpatialInput input = SpatialInput.of(owner.plugin);
        pointer = input.attach(viewer).sounds(theme.hoverSound(), theme.selectSound())
                .onSneak(this::skip)
                .onLeave(this::skip)
                .onClick(click -> hurry())
                .onTick(this::tick)
                .onEnd(end -> {
                    // Quit: paid by the caller's quit path. Anything else (death, a world change, a menu
                    // opened over it, the plugin going): handed over now, taken down at once.
                    if (end == SpatialPointer.End.QUIT) quit();
                    else close(false);
                });
        if (layout.freeze()) Packets.of(owner.plugin).movement().freeze(viewer);
        CrateSceneLayout.SkipItem skip = layout.skipItem();
        net.exylia.lib.overlay.OverlayDefinition hotbar = input.hotbar(Item.of(skip.material())
                .name(fill(skip.name())).lore(skip.lore().stream().map(this::fill).toList()).build());
        hotbarId = hotbar.id();
        Overlays.of(owner.plugin).show(viewer, hotbar);
        if (layout.hideOthers()) SpatialMenus.hideOthers(viewer, true);

        drawHeader();
        drawReelFrame();
        drawCrate(120L);
        StageLook.play(viewer, theme.openSound());
        phaseStart = now();
    }

    /** Sneak, the leave item, or anything that ends the pointer but a quit: everything is handed over now. */
    void skip() {
        close(true);
    }

    /** The player left: their prizes are paid by the caller's quit path, nothing is asked of them here. */
    private void quit() {
        if (closed) return;
        end(false, true);
    }

    void close(boolean animated) {
        if (closed) return;
        for (int i = 0; i < prizes.size(); i++) reveal(i);
        end(animated, false);
    }

    private void end(boolean animated, boolean quit) {
        closed = true;
        CrateScene.PLAYING.remove(viewer.getUniqueId(), this);
        if (pointer != null && pointer.isAttached()) pointer.detach();
        stand.remove();
        boolean online = viewer.isOnline();
        takeDown(animated && online);
        if (online) {
            if (animated) StageLook.play(viewer, theme.closeSound());
            if (layout.freeze()) Packets.of(owner.plugin).movement().unfreeze(viewer);
            PluginOverlays overlays = Overlays.of(owner.plugin);
            if (overlays.showing(viewer).filter(shown -> shown.id().equals(hotbarId)).isPresent()) {
                overlays.hide(viewer);
            }
        }
        if (layout.hideOthers()) SpatialMenus.hideOthers(viewer, false);
        show.ended(quit);
    }

    /** Hands prize {@code index} over unless it already was. */
    private CrateReveal reveal(int index) {
        if (revealed[index] == null) revealed[index] = show.reveal(index);
        return revealed[index];
    }

    private void takeDown(boolean deflate) {
        List<DisplayHandle> drawn = List.copyOf(pieces);
        pieces.clear();
        if (!deflate) {
            drawn.forEach(DisplayHandle::remove);
            return;
        }
        drawn.forEach(piece -> shrink(piece, 170L));
        owner.tasks.runAtEntityLater(viewer, 5L, () -> drawn.forEach(DisplayHandle::remove),
                () -> drawn.forEach(DisplayHandle::remove));
    }

    // ── The clock ─────────────────────────────────────────────────────────

    private static long now() {
        return System.currentTimeMillis();
    }

    private void tick() {
        if (closed) return;
        long now = now();
        long in = now - phaseStart;
        switch (phase) {
            case INTRO -> {
                if (in >= POP_MILLIS + (long) (layout.reel().pauseSeconds() * 1000)) spin();
            }
            case SPIN -> {
                int marker = path.onMarker(in);
                if (marker != onMarker && marker >= 0 && marker < strip.size()) {
                    onMarker = marker;
                    double progress = Math.min(1, in / (double) path.millis());
                    StageLook.play(viewer, layout.reel().tickSound(), 1 + 0.35 * progress);
                    if (body != null && marker < stripFaces.size()) body.glow(rgb(stripFaces.get(marker).colour()));
                }
                if (in >= path.millis()) land();
            }
            case REVEAL -> {
                if (previewAt > 0 && now >= previewAt) {
                    previewAt = 0;
                    tryOn();
                }
                if (in >= (long) (layout.reveal().holdSeconds() * 1000)) {
                    if (current + 1 < prizes.size()) next();
                    else done();
                }
            }
            case DONE -> { }
        }
        stand.keep(now);
    }

    /** A click while a prize is shown: on to the next one now. */
    private void hurry() {
        if (phase == Phase.REVEAL && now() - phaseStart > 600) phaseStart = 0;
    }

    // ── The reel ──────────────────────────────────────────────────────────

    private void spin() {
        CrateSceneLayout.Reel look = layout.reel();
        CrateFace prize = prizes.get(current);
        int prizeIndex = faces.indexOf(prize);
        List<CrateFace> shown = new ArrayList<>(faces);
        if (prizeIndex < 0) {
            shown.add(prize);
            prizeIndex = shown.size() - 1;
        }
        int nearMiss = -1;
        if (look.nearMiss()) {
            List<Integer> better = new ArrayList<>();
            int top = shown.stream().mapToInt(CrateFace::rank).max().orElse(prize.rank());
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).rank() == top && top > prize.rank()) better.add(i);
            }
            if (!better.isEmpty()) nearMiss = better.get(ThreadLocalRandom.current().nextInt(better.size()));
        }
        int size = shown.size();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        path = new ReelPath(look.faces(), look.slots(), (long) (look.seconds() * 1000));
        strip = path.strip(i -> random.nextInt(size), prizeIndex, nearMiss);
        List<CrateFace> from = shown;
        Location centre = board.point(0, look.up(), 0);
        for (int i = 0; i < strip.size(); i++) {
            CrateFace face = from.get(strip.get(i));
            DisplayModel model = DisplayModel.item(face.icon()).held(GUI_CONTEXT).light(15).glow(rgb(face.colour()));
            reel.add(track(displays.show(model, DisplayMotion.of(facePath(i), LIFE), centre, List.of(viewer))));
        }
        // The strip's faces as the marker reads them, wherever they came from.
        List<CrateFace> resolved = new ArrayList<>(strip.size());
        for (int index : strip) resolved.add(from.get(index));
        stripFaces = resolved;
        onMarker = -1;
        phase = Phase.SPIN;
        phaseStart = now();
    }

    private List<CrateFace> stripFaces = List.of();

    /** One face's poses: hidden until it enters the window, sliding through it, shrinking out of it. */
    private List<DisplayKeyframe> facePath(int index) {
        long millis = path.millis();
        int steps = (int) (millis / ReelPath.STEP_MILLIS);
        int first = -1;
        int last = -1;
        for (int step = 0; step <= steps; step++) {
            if (path.shown(index, time(step, steps))) {
                if (first < 0) first = step;
                last = step;
            }
        }
        List<DisplayKeyframe> frames = new ArrayList<>();
        if (first < 0) {
            frames.add(facePose(0, path.position(index, 0), 0));
            return frames;
        }
        int from = Math.max(0, first - 1);
        if (from > 0) frames.add(facePose(0, path.position(index, time(from, steps)), 0));
        for (int step = from; step <= Math.min(steps, last + 1); step++) {
            long at = time(step, steps);
            double position = path.position(index, at);
            frames.add(facePose(at, position, path.size(position)));
        }
        return frames;
    }

    private long time(int step, int steps) {
        return step == steps ? path.millis() : step * ReelPath.STEP_MILLIS;
    }

    private DisplayKeyframe facePose(long at, double position, double size) {
        CrateSceneLayout.Reel look = layout.reel();
        double across = position * look.spacing();
        double half = path.half() + 0.5;
        double forward = FACE_FORWARD + BULGE * Math.max(0, 1 - Math.pow(position / half, 2));
        Rotation turn = Rotation.around(Rotation.Axis.Y, Math.atan2(-across, Math.max(0.5, distance - forward))).then(facing);
        float[] offset = facing.apply(new float[]{(float) across, 0f, (float) forward});
        float scale = (float) Math.max(0.001, look.icon() * size);
        return new DisplayKeyframe(at, offset[0], offset[1], offset[2], turn, scale, scale, scale);
    }

    /** The reel stops: the prize is handed over, then shown off. */
    private void land() {
        CrateFace prize = prizes.get(current);
        CrateReveal outcome = reveal(current);
        int landing = path.landing();
        for (int i = 0; i < reel.size(); i++) {
            DisplayHandle face = reel.get(i);
            if (face == null || i == landing) continue;
            if (path.shown(i, path.millis())) shrink(face, 200L);
            else face.remove();
            pieces.remove(face);
        }
        winner = reel.get(landing);
        reel.clear();
        if (winner != null) {
            pieces.add(winner);
            DisplayKeyframe here = winner.pose();
            if (here != null) winner.then(winnerSpin(here));
        }
        int colour = rgb(prize.colour());
        for (DisplayHandle marker : markers) marker.restyle(sheetModel(Geometry.argb(255, colour)));
        if (body != null) body.glow(colour);
        openLid();
        StageLook.play(viewer, layout.crate().openSound());
        List<String> sounds = layout.reveal().sounds();
        if (!sounds.isEmpty()) {
            StageLook.play(viewer, sounds.get((int) Math.round(prize.weight() * (sounds.size() - 1))));
        }
        if (layout.reveal().burst()) burst(prize);

        CrateSceneLayout.Reveal look = layout.reveal();
        String status = outcome.duplicate() ? look.duplicate() : look.won();
        words.add(write(fill(look.name(), prize, outcome), false, 0, look.up(), 0.15, 3.0, theme.titleScale(), 0L));
        words.add(write(fill(look.rarity(), prize, outcome), true, 0, look.up() - 0.17, 0.15, 2.6, theme.textScale(), 90L));
        words.add(write(fill(status, prize, outcome), true, 0, look.up() - 0.31, 0.15, 3.0, theme.smallScale(), 180L));
        previewAt = now() + PREVIEW_AFTER;
        phase = Phase.REVEAL;
        phaseStart = now();
    }

    /** From where it stopped: grown, brought nearer, then turning slowly on the spot. */
    private DisplayMotion winnerSpin(DisplayKeyframe here) {
        float size = (float) (layout.reel().icon() * 1.85);
        float[] at = facing.apply(new float[]{0f, 0f, 0.3f});
        List<DisplayKeyframe> frames = new ArrayList<>();
        frames.add(here.at(0));
        frames.add(new DisplayKeyframe(170L, at[0], at[1], at[2], facing, size * 1.14f, size * 1.14f, size * 1.14f));
        long rest = 320L;
        long cycle = 4800L;
        for (int step = 0; step <= 12; step++) {
            Rotation turn = Rotation.around(Rotation.Axis.Y, Math.PI * 2 * step / 12).then(facing);
            frames.add(new DisplayKeyframe(rest + cycle * step / 12, at[0], at[1], at[2], turn, size, size, size));
        }
        return DisplayMotion.of(frames, LIFE).looping(rest, rest + cycle, 1.0, 1.0);
    }

    /** Stars thrown out of the prize and a few sparks, for the viewer alone; more the rarer it is. No fireworks. */
    private void burst(CrateFace prize) {
        double weight = prize.weight();
        Location at = board.point(0, layout.reel().up(), 0.3);
        int colour = rgb(prize.colour());
        viewer.spawnParticle(Particle.DUST, at, 8 + (int) (16 * weight), 0.35, 0.25, 0.1, 0,
                new Particle.DustOptions(Color.fromRGB(colour), 1.2f));
        viewer.spawnParticle(Particle.END_ROD, at, 4 + (int) (8 * weight), 0.1, 0.1, 0.05, 0.06 + 0.06 * weight);
        int stars = 6 + (int) Math.round(10 * weight);
        double reach = 0.7 + 0.8 * weight;
        Component star = Text.component(prize.colour() + "✦");
        for (int i = 0; i < stars; i++) {
            double angle = Math.PI * 2 * i / stars + ThreadLocalRandom.current().nextDouble(-0.2, 0.2);
            float[] to = facing.apply(new float[]{(float) (Math.cos(angle) * reach), (float) (Math.sin(angle) * reach * 0.7), 0.05f});
            float size = (float) (0.55 + 0.3 * weight);
            DisplayMotion fly = DisplayMotion.of(List.of(
                    new DisplayKeyframe(0L, 0f, 0f, 0f, facing, size, size, size),
                    new DisplayKeyframe(650L, to[0], to[1], to[2], facing, 0.02f, 0.02f, 0.02f)), 700L);
            displays.show(Stage.textModel(star).background(0).seeThrough(false).textShadow(false), fly,
                    at.clone().add(0, -Stage.TEXT_HEIGHT * size / 2, 0), List.of(viewer));
        }
    }

    /** The prize tried on where the crate stood; with no preview the crate stays open round its icon. */
    private void tryOn() {
        SpatialPreview preview = show.preview(current);
        if (preview == null || preview instanceof SpatialPreview.None) return;
        crate.forEach(piece -> {
            shrink(piece, 220L);
            pieces.remove(piece);
        });
        crate.clear();
        body = null;
        lid = null;
        CrateSceneLayout.Crate look = layout.crate();
        crate.add(block(theme.pedestalBlock(), 0, look.up() + 0.06, CRATE_FORWARD, 0.78, 0.12, 0.78, 120L));
        stand.show(preview, show.previewVanishes());
    }

    /** The next crate: the reveal goes, a closed crate comes back, the reel runs again. */
    private void next() {
        clearReveal();
        current++;
        if (counter != null) {
            pieces.remove(counter);
            shrink(counter, 150L);
        }
        counter = drawCounter(0L);
        drawCrate(0L);
        phase = Phase.INTRO;
        phaseStart = now();
    }

    private void clearReveal() {
        stand.remove();
        List<DisplayHandle> going = new ArrayList<>(words);
        going.addAll(crate);
        if (winner != null) going.add(winner);
        for (DisplayHandle piece : going) {
            if (piece == null) continue;
            pieces.remove(piece);
            shrink(piece, 200L);
        }
        words.clear();
        crate.clear();
        winner = null;
        body = null;
        lid = null;
        int idle = rgb(layout.reel().marker());
        for (DisplayHandle marker : markers) marker.restyle(sheetModel(Geometry.argb(255, idle)));
    }

    /** After the last reveal: open another, or done. */
    private void done() {
        phase = Phase.DONE;
        CrateSceneLayout.Buttons look = layout.buttons();
        boolean again = show.canOpenAnother() && !look.again().isBlank();
        if (again) {
            button(fill(look.again()), -look.spread(), look.up(), look.width(), () -> {
                close(true);
                show.openAnother();
            });
        }
        button(fill(look.done()), again ? look.spread() : 0, look.up(), look.width(), () -> close(true));
    }

    // ── The pieces ────────────────────────────────────────────────────────

    private void drawHeader() {
        CrateSceneLayout.Header look = layout.header();
        write(fill(look.title()), false, 0, look.up(), 0, 4.0, theme.titleScale(), 0L);
        counter = drawCounter(60L);
        write(fill(look.hint()), true, 0, look.up() - 0.33, 0, 3.0, theme.smallScale(), 120L);
    }

    private @Nullable DisplayHandle drawCounter(long delay) {
        if (prizes.size() < 2) return null;
        return write(fill(layout.header().counter()), true, 0, layout.header().up() - 0.19, 0, 3.0, theme.textScale(), delay);
    }

    /** The reel's glass, its accent bar and the marker at its middle. */
    private void drawReelFrame() {
        CrateSceneLayout.Reel look = layout.reel();
        double width = look.slots() * look.spacing() + 0.24;
        double height = look.icon() * 1.95;
        int glass = Geometry.mix(0x000000, rgb(theme.glassTint()), theme.glassTintStrength());
        sheet(board.point(0, look.up(), -0.02), width + theme.backingReach() * 2, height + theme.backingReach() * 2,
                Geometry.argb(theme.backingAlpha(), Geometry.mix(0x000000, glass, 0.5)), 0L);
        sheet(board.point(0, look.up(), 0), width, height, Geometry.argb(theme.glassAlpha(), glass), 0L);
        sheet(board.point(0, look.up() + height / 2 - theme.accentHeight() / 2, 0.004), width, theme.accentHeight(),
                Geometry.argb(255, rgb(theme.accent())), 90L);
        int marker = Geometry.argb(255, rgb(look.marker()));
        double tick = height * 0.2;
        markers.add(sheet(board.point(0, look.up() + height / 2 - tick / 2 - theme.accentHeight(), 0.006), 0.035, tick, marker, 160L));
        markers.add(sheet(board.point(0, look.up() - height / 2 + tick / 2, 0.006), 0.035, tick, marker, 160L));
        markers.removeIf(java.util.Objects::isNull);
    }

    /** A closed crate: its body, gilded bands and corners, and a lid hinged along its back. */
    private void drawCrate(long delay) {
        CrateSceneLayout.Crate look = layout.crate();
        double w = look.width();
        double h = look.height();
        double d = w * 0.7;
        double base = look.up();
        double f = CRATE_FORWARD;
        body = block(look.body(), 0, base + h / 2, f, w, h, d, delay);
        crate.add(body);
        crate.add(block(look.trim(), 0, base + 0.03, f, w + 0.03, 0.06, d + 0.03, delay + 40));
        crate.add(block(look.trim(), 0, base + h - 0.03, f, w + 0.03, 0.06, d + 0.03, delay + 40));
        for (int x = -1; x <= 1; x += 2) {
            for (int z = -1; z <= 1; z += 2) {
                crate.add(block(look.trim(), x * w / 2, base + h / 2, f + z * d / 2, 0.06, h, 0.06, delay + 70));
            }
        }
        crate.add(block(look.trim(), 0, base + h * 0.62, f + d / 2 + 0.02, 0.14, 0.16, 0.04, delay + 110));
        lid = lid(look, 0, delay + 150);
        crate.add(lid);
        crate.removeIf(java.util.Objects::isNull);
    }

    /** The lid at {@code degrees} open (0 shut), turned about its hinge along the body's back top edge. */
    private @Nullable DisplayHandle lid(CrateSceneLayout.Crate look, double degrees, long delay) {
        DisplayModel model = DisplayModel.block(StageLook.block(look.lid(), Material.POLISHED_BLACKSTONE_BRICKS)).light(15);
        DisplayKeyframe shut = lidPose(0, look, 0, 1);
        DisplayMotion motion = DisplayMotion.of(List.of(lidPose(0, look, degrees, 0.01), lidPose(delay, look, degrees, 0.01),
                lidPose(delay + 150, look, degrees, 1.08), lidPose(delay + 280, look, degrees, 1)), LIFE);
        Location hinge = board.point(0, look.up() + look.height(), CRATE_FORWARD - look.width() * 0.7 / 2);
        return track(displays.show(model, delay < 0 ? DisplayMotion.of(List.of(shut), LIFE) : motion, hinge, List.of(viewer)));
    }

    private DisplayKeyframe lidPose(long at, CrateSceneLayout.Crate look, double degrees, double grow) {
        double w = look.width() + 0.05;
        double d = look.width() * 0.7 + 0.05;
        double t = look.height() * 0.32;
        Rotation open = Rotation.around(Rotation.Axis.X, -Math.toRadians(degrees));
        float[] centre = facing.apply(open.apply(new float[]{0f, (float) (t / 2), (float) (d / 2 - 0.025)}));
        return new DisplayKeyframe(at, centre[0], centre[1], centre[2], open.then(facing),
                (float) (w * grow), (float) (t * grow), (float) (d * grow));
    }

    private void openLid() {
        if (lid == null) return;
        CrateSceneLayout.Crate look = layout.crate();
        List<DisplayKeyframe> frames = new ArrayList<>();
        DisplayKeyframe here = lid.pose();
        frames.add(here != null ? here.at(0) : lidPose(0, look, 0, 1));
        for (int step = 1; step <= 7; step++) {
            double u = step / 7.0;
            double eased = 1 - Math.pow(1 - u, 3);
            frames.add(lidPose((long) (420 * u), look, 108 * eased, 1));
        }
        lid.then(DisplayMotion.of(frames, LIFE));
    }

    private @Nullable DisplayHandle block(String name, double across, double up, double forward, double x, double y,
                                          double z, long delay) {
        DisplayModel model = DisplayModel.block(StageLook.block(name, Material.POLISHED_BLACKSTONE)).light(15);
        return track(displays.show(model, StageLook.pop(facing, x, y, z, delay, LIFE), board.point(across, up, forward),
                List.of(viewer)));
    }

    /** A sheet: a text display's background, centred on {@code at}. */
    private @Nullable DisplayHandle sheet(Location at, double width, double height, int argb, long delay) {
        DisplayMotion motion = DisplayMotion.of(Geometry.glassPop(facing, width, height, delay), LIFE);
        return track(displays.show(sheetModel(argb), motion, at, List.of(viewer)));
    }

    private static DisplayModel sheetModel(int argb) {
        return DisplayModel.text(Component.text(Geometry.GLASS_TEXT)).billboard("FIXED").light(15)
                .background(argb).seeThrough(false).textShadow(false).lineWidth(1000);
    }

    /**
     * A line centred on a point of the board, fitted into {@code room} at up to {@code max} but never
     * under what the theme's {@code min-glyph-degrees} reads from the eyes; cut with an ellipsis when it
     * is still too long. Titles and buttons ({@code body} false) follow the server's small-text setting.
     */
    private @Nullable DisplayHandle write(String filled, boolean body, double across, double up, double forward,
                                          double room, double max, long delay) {
        if (filled == null || filled.isBlank()) return null;
        Location at = board.point(across, up, forward);
        boolean asWritten = body && !theme.smallCapsBody();
        boolean small = !asWritten && net.exylia.lib.text.internal.SmallText.enabled();
        double floor = Geometry.readableScale(at.distance(eyes()), theme.minGlyphDegrees(),
                small ? Geometry.SMALL_CAPITAL_PIXELS : Geometry.CAPITAL_PIXELS);
        double width = Math.max(0.18, Geometry.visibleWidth(filled));
        double scale = Math.max(floor, Math.min(max, room / width));
        String shown = width * scale > room + 1e-6 ? Geometry.truncate(filled, room / scale) : filled;
        Component text = asWritten ? Text.verbatim(shown) : Text.component(shown);
        DisplayModel model = Stage.textModel(text).background(0).seeThrough(false).textShadow(true).lineWidth(1000);
        return track(displays.show(model, StageLook.pop(facing, scale, scale, scale, delay, LIFE),
                board.point(across, up - Stage.TEXT_HEIGHT * scale / 2, forward), List.of(viewer)));
    }

    /** A plate with a label that lights under the crosshair and runs {@code action} when clicked. */
    private void button(String label, double across, double up, double width, Runnable action) {
        double height = 0.24;
        StagePlane plane = Geometry.panel(board.point(0, 0, 0).getWorld(), board, 0, 0, 0.2, 0);
        int idle = Geometry.argb(255, Geometry.mix(0x000000, rgb(theme.plateIdle()), theme.glassTintStrength() * 1.6));
        int lit = Geometry.argb(255, Geometry.mix(Geometry.mix(0x000000, rgb(theme.plateIdle()), theme.glassTintStrength() * 1.6),
                rgb(theme.plateHover()), 0.45));
        DisplayHandle plate = sheet(plane.point(across, up, 0), width, height, idle, 0L);
        write(label, false, across, up, 0.21, width - 0.12, theme.textScale(), 80L);
        Hotspot hotspot = Hotspot.rect(plane, across, up, width, height)
                .onHover(on -> {
                    if (plate != null) {
                        plate.restyle(sheetModel(on ? lit : idle));
                        plate.glow(on ? rgb(theme.hoverGlow()) : -1);
                    }
                })
                .onPress(click -> action.run());
        hotspots.add(pointer.add(hotspot));
    }

    private @Nullable DisplayHandle track(@Nullable DisplayHandle handle) {
        if (handle != null) pieces.add(handle);
        return handle;
    }

    /** Shrinks a piece away; it goes by itself when the motion ends. */
    private static void shrink(@Nullable DisplayHandle piece, long millis) {
        if (piece == null) return;
        DisplayKeyframe here = piece.pose();
        if (here == null) {
            piece.remove();
            return;
        }
        float tiny = 0.01f;
        piece.then(DisplayMotion.of(List.of(here.at(0), new DisplayKeyframe(millis, here.x(), here.y(), here.z(),
                here.rotation(), here.scaleX() * tiny, here.scaleY() * tiny, here.scaleZ() * tiny)), millis + 150));
    }

    private Location eyes() {
        return board.point(0, 0, distance);
    }

    private static int rgb(String token) {
        return StageLook.rgb(token, 0xFFD700);
    }

    // ── Words ─────────────────────────────────────────────────────────────

    /** A layout line in the server's language (when left as written), then the opening's values. */
    private String fill(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return Phrases.tr(raw).replace("%current%", String.valueOf(current + 1))
                .replace("%total%", String.valueOf(prizes.size()));
    }

    private String fill(String raw, CrateFace prize, CrateReveal outcome) {
        return fill(raw).replace("%name%", prize.name()).replace("%rarity%", prize.rarity())
                .replace("%refund%", String.valueOf(outcome.refund()));
    }

    // ── The preview ───────────────────────────────────────────────────────

    /** What stands where the crate stood: an NPC, an item, or an effect on a mannequin. */
    private final class Stand {
        private SpatialPreview current = SpatialPreview.none();
        private boolean vanish;
        private @Nullable NpcHandle npc;
        private @Nullable DisplayHandle item;
        private @Nullable SequenceRun run;
        private long nextPlay;
        private long npcUntil;

        private Location base() {
            Location at = board.point(0, layout.crate().up() + 0.12, CRATE_FORWARD);
            at.setYaw(board.yaw() + 180);
            return at;
        }

        void show(SpatialPreview preview, boolean vanishes) {
            remove();
            // A crate prize stands still on its plinth: a flight shows the projectile itself.
            current = preview instanceof SpatialPreview.Flight flight ? SpatialPreview.item(flight.projectile()) : preview;
            vanish = vanishes;
            draw();
        }

        private void draw() {
            CrateSceneLayout.Preview look = layout.preview();
            switch (current) {
                case SpatialPreview.Npc body -> {
                    NpcModel model = NpcModel.of(body.skin()).scale(look.scale()).nameless();
                    if (body.equipment() == null) model = model.wearing(body.skin());
                    spawn(model);
                    if (npc != null && body.equipment() != null) {
                        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                                EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
                            npc.equip(slot, body.equipment().get(slot));
                        }
                    }
                }
                case SpatialPreview.Item shown -> {
                    double size = 0.62 * look.scale();
                    long cycle = (long) (360_000 / Math.max(1, Math.abs(look.turnSpeed())));
                    List<DisplayKeyframe> frames = new ArrayList<>();
                    for (int pose = 0; pose <= 12; pose++) {
                        frames.add(new DisplayKeyframe(cycle * pose / 12, 0f, 0f, 0f,
                                Rotation.around(Rotation.Axis.Y, Math.PI * 2 * pose / 12).then(facing),
                                (float) size, (float) size, (float) size));
                    }
                    item = track(displays.show(DisplayModel.item(shown.item()).light(15),
                            DisplayMotion.of(frames, LIFE).looping(0, cycle, 1.0, 1.0),
                            base().add(0, 0.42 * look.scale(), 0), List.of(viewer)));
                }
                case SpatialPreview.Effect effect -> {
                    // Seen standing first, so a kill that takes it down reads as a death.
                    mannequin(effect);
                    nextPlay = now() + 1200L;
                }
                case SpatialPreview.Flight flight -> { }
                case SpatialPreview.None none -> { }
            }
        }

        private void mannequin(SpatialPreview.Effect effect) {
            NpcModel model = effect.skin() == null ? NpcModel.of("Mannequin") : NpcModel.of(effect.skin());
            spawn(model.scale(layout.preview().effectScale()).nameless());
        }

        private void spawn(NpcModel model) {
            double speed = layout.preview().turnSpeed();
            NpcMotion turn = speed == 0 ? NpcMotion.still() : NpcMotion.builder().spin(speed).build();
            npc = Npcs.of(owner.plugin).show(model, turn, base(), 120_000L, List.of(viewer));
            npcUntil = now() + 115_000L;
        }

        /** Every poll: an NPC lives two minutes at most; an effect plays again once it is over. */
        void keep(long now) {
            if (current instanceof SpatialPreview.None) return;
            if (npc != null && (now > npcUntil || !npc.isShowing()) && !(current instanceof SpatialPreview.Effect)) {
                SpatialPreview again = current;
                remove();
                current = again;
                draw();
                return;
            }
            if (!(current instanceof SpatialPreview.Effect effect) || now < nextPlay) return;
            if (npc != null && now > npcUntil) {
                npc.remove();
                npc = null;
            }
            if (run != null && !run.isFinished()) return;
            if (npc == null) mannequin(effect);
            // Not before the client has the mannequin: one taken down unseen never reads as a death.
            if (run == null && vanish && npc != null && !npc.isShowing() && now < nextPlay + 3000L) return;
            if (run != null) {
                // Over: the mannequin stands a moment before it plays again.
                run = null;
                nextPlay = now + 1000L;
                return;
            }
            RagdollModel body = effect.skin() == null ? RagdollModel.of(SkinCache.fallback(), null) : RagdollModel.of(effect.skin());
            // Played by the viewer, with nobody else there, so an [NPC] {victim} line has a body; no target,
            // so a [POTION] or a victim's title never lands on them. A miniature at the pedestal's top.
            run = Sequences.of(owner.plugin).play(effect.sequence(), SequenceTarget.at(base()).by(viewer).onlyTo(viewer)
                    .wearing(body).scale(layout.preview().effectScale()));
            if (vanish && npc != null) {
                npc.remove();
                npc = null;
            }
        }

        void remove() {
            if (npc != null) npc.remove();
            npc = null;
            if (item != null) {
                pieces.remove(item);
                item.remove();
            }
            item = null;
            if (run != null) run.cancel();
            run = null;
            current = SpatialPreview.none();
        }
    }
}
