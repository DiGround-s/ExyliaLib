package net.exylia.lib.replay;

import net.exylia.lib.replay.internal.LiveWatch;
import net.exylia.lib.replay.internal.ReplayRuntime;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import net.exylia.lib.util.teleport.Teleports;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One person watching a staged recording: the camera they watch it through,
 * the controls, and the way back.
 *
 * <p>Everything a plugin's own screen needs and nothing it has to draw: the
 * buttons are the plugin's (an overlay, a menu, a command) and call the methods
 * here; the line above the hotbar is the plugin's text, fed the numbers it
 * needs every few ticks.
 *
 * <pre>{@code
 * replays.stage(replay).thenAccept(stage -> Tasks.at(viewer, () ->
 *     ReplayViewer.open(plugin, viewer, stage, focus)
 *         .thenAccept(watching -> {
 *             watching.hud(status -> lang.bar(status));
 *             watching.onLeave(() -> overlay.hide(viewer));
 *         })));
 * }</pre>
 *
 * <h2>A camera, not a ghost</h2>
 * The viewer flies in adventure mode and cannot be hurt. Spectator mode has no
 * hotbar, and the hotbar is where a replay is driven from. What they were
 * before &mdash; where, which game mode, whether they could fly &mdash; is put
 * back exactly when they leave, quit or the plugin stops.
 *
 * @since 1.241.0
 */
public final class ReplayViewer {

    /** The speeds {@link #faster()} and {@link #slower()} step through. */
    public static final double[] SPEEDS = {0.0625, 0.125, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0};

    /** How often the line above the hotbar is refreshed, in ticks. */
    private static final long HUD_TICKS = 4L;

    private static final Map<UUID, ReplayViewer> WATCHING = new ConcurrentHashMap<>();

    /** Left, and on the way back: a quit in the meantime puts them back on the spot. */
    private static final Map<UUID, ReplayViewer> RETURNING = new ConcurrentHashMap<>();

    private final String owner;
    private final Player viewer;
    private final TaskScheduler scheduler;
    private final ReplayStage stage;
    private final ReplayPlayback playback;
    private final Saved saved;
    private final @Nullable LiveWatch live;
    private final AtomicBoolean restored = new AtomicBoolean();
    private volatile TaskHandle hudTask;
    private volatile Function<Status, Component> hud;
    private volatile Runnable onLeave;
    private volatile boolean left;

    private ReplayViewer(String owner, Player viewer, TaskScheduler scheduler, ReplayStage stage,
                         ReplayPlayback playback, Saved saved, @Nullable LiveWatch live) {
        this.owner = owner;
        this.viewer = viewer;
        this.scheduler = scheduler;
        this.stage = stage;
        this.playback = playback;
        this.saved = saved;
        this.live = live;
    }

    /**
     * Watches a recording in the very place it happened, with nothing staged.
     *
     * <p>For a recording watched seconds after it was made, on the server it
     * was made on, such as a killcam: the ground is still the same, so nothing
     * is laid down and nothing is waited for. The viewer is moved to the real
     * place, the recorded bodies are drawn for them alone, every block that
     * changed during it is drawn for them alone too ({@link ReplayWorld#PACKET})
     * and taken off their screen afterwards, and whoever and whatever is really
     * there now is hidden from them while it plays. Meanwhile they are a ghost:
     * nobody sees them, nothing targets or hurts them, they touch nothing, and
     * no recording picks them up. On leaving they are put back exactly as they
     * were, and everything is shown again on both sides.
     *
     * <p>When a scene's world is not loaded here, it falls back to
     * {@link PluginReplays#stage(Replay) staging} the recording, which needs one
     * that carries its terrain; without terrain it cannot be shown at all. The
     * catch of watching live is the one of {@link ReplayWorld#PACKET}: the
     * ground is the place as it is now, and a block the recording changed is
     * there for the viewer's eyes but not for the server, so this is a camera
     * that flies and looks, not one that walks on what was built.
     *
     * <p>Call it on the viewer's own thread.
     *
     * @param plugin the plugin the viewer belongs to
     * @param viewer who watches
     * @param replay what they watch
     * @param focus  who to start next to, or {@code null} for the first actor
     * @return the viewer once they have arrived and it is running, with every
     *         control {@link #open} has; completed exceptionally when it cannot
     *         be shown
     * @since 1.402.0
     */
    public static @NotNull CompletableFuture<ReplayViewer> openLive(@NotNull Plugin plugin, @NotNull Player viewer,
                                                                    @NotNull Replay replay,
                                                                    @Nullable UUID focus) {
        ReplayStage here = ReplayRuntime.live(plugin.getName(), replay);
        if (here != null) return open(plugin, viewer, here, focus, true);
        if (!replay.hasTerrain()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The place it was recorded in is not loaded, and it carries no terrain to stage"));
        }
        TaskScheduler scheduler = Tasks.of(plugin);
        return Replays.of(plugin).stage(replay).thenCompose(stage -> {
            CompletableFuture<ReplayViewer> opened = new CompletableFuture<>();
            scheduler.runAtEntity(viewer, () -> open(plugin, viewer, stage, focus, false)
                    .whenComplete((watching, failure) -> {
                        if (failure != null) opened.completeExceptionally(failure);
                        else opened.complete(watching);
                    }), () -> {
                stage.close();
                opened.completeExceptionally(new IllegalStateException("The viewer left"));
            });
            return opened;
        });
    }

    /**
     * Puts somebody on a stage, at the start of the recording, a little behind
     * the person it is about, and starts it.
     *
     * <p>Call it on the viewer's own thread. Somebody already watching
     * something leaves that first.
     *
     * @param plugin the plugin the viewer belongs to
     * @param viewer who watches
     * @param stage  what they watch
     * @param focus  who to start next to, or {@code null} for the first actor
     * @return the viewer once they have arrived and it is running; completed
     *         exceptionally when it cannot be shown
     */
    public static @NotNull CompletableFuture<ReplayViewer> open(@NotNull Plugin plugin, @NotNull Player viewer,
                                                                @NotNull ReplayStage stage,
                                                                @Nullable UUID focus) {
        return open(plugin, viewer, stage, focus, false);
    }

    /** @param here whether the stage is the real place, among the people playing there */
    private static CompletableFuture<ReplayViewer> open(Plugin plugin, Player viewer, ReplayStage stage,
                                                        @Nullable UUID focus, boolean here) {
        ReplayViewer previous = WATCHING.get(viewer.getUniqueId());
        // Going from one replay straight to another: where they really were is
        // what the first one saved, not the stage they are still standing on.
        // Or from one still on its way back: that way back is called off.
        ReplayViewer returning = RETURNING.remove(viewer.getUniqueId());
        Saved before = previous != null ? previous.saved : returning != null ? returning.saved : null;
        if (previous != null) previous.leave(false, false);
        if (returning != null && returning.restored.compareAndSet(false, true) && returning.live != null) {
            returning.live.end();
        }
        Replay replay = stage.replay();
        UUID who = focus != null ? focus : replay.actors().isEmpty() ? null : replay.actors().getFirst().id();
        Location start = who == null ? null : firstSeen(stage, who);
        if (start == null) start = stage.anchor(0).add(0, 2, 0);
        Location seat = behind(start);
        Saved saved = before != null ? before : Saved.of(viewer);
        TaskScheduler scheduler = Tasks.of(plugin);
        CompletableFuture<ReplayViewer> opened = new CompletableFuture<>();
        // A ghost before they go: nobody sees them appear over the fight.
        LiveWatch live = here ? LiveWatch.begin(viewer) : null;
        Teleports.of(plugin).to(viewer, seat).warmup(0).start().future()
                .thenAccept(arrived -> scheduler.runAtEntity(viewer, () -> {
            if (!arrived.isSuccess() || !viewer.isOnline()) {
                if (live != null) live.end();
                stage.close();
                opened.completeExceptionally(new IllegalStateException("The viewer could not be moved to the stage"));
                return;
            }
            camera(viewer);
            if (live != null) live.sweepNow();
            ReplayPlayback playback = stage.play(List.of(viewer));
            if (playback == null) {
                if (live != null) live.end();
                saved.restore(viewer, false);
                Teleports.of(ReplayRuntime.plugin()).to(viewer, saved.location()).warmup(0).start();
                stage.close();
                opened.completeExceptionally(new IllegalStateException("Replays cannot be shown on this server"));
                return;
            }
            playback.reveal(true);
            ReplayViewer watching = new ReplayViewer(plugin.getName(), viewer, scheduler, stage, playback, saved, live);
            WATCHING.put(viewer.getUniqueId(), watching);
            watching.hudTask = scheduler.runAtEntityTimer(viewer, 1L, HUD_TICKS, watching::refresh);
            opened.complete(watching);
        }, () -> {
            // They left before arriving: nothing to put back, and nobody to
            // show the stage to.
            if (live != null) live.end();
            stage.close();
            opened.completeExceptionally(new IllegalStateException("The viewer left"));
        }));
        return opened;
    }

    /** What somebody is watching, or {@code null}. */
    public static @Nullable ReplayViewer of(@NotNull Player viewer) {
        return WATCHING.get(viewer.getUniqueId());
    }

    /**
     * Sends everybody watching anything back where they were. Called by the
     * library when it stops, and by a plugin that stops while people watch.
     */
    public static void leaveAll() {
        new ArrayList<>(WATCHING.values()).forEach(watching -> watching.leave(true, true));
        new ArrayList<>(RETURNING.values()).forEach(ReplayViewer::backNow);
    }

    /** Sends back everybody one plugin put on a stage, when it is disabled. */
    public static void release(@NotNull String pluginName) {
        new ArrayList<>(WATCHING.values()).stream()
                .filter(watching -> watching.owner.equals(pluginName))
                .forEach(watching -> watching.leave(true, true));
    }

    /** Leaves for somebody who is quitting: put back now, on this thread. */
    public static void quit(@NotNull Player viewer) {
        ReplayViewer watching = WATCHING.get(viewer.getUniqueId());
        if (watching != null) watching.leave(true, true);
        ReplayViewer returning = RETURNING.get(viewer.getUniqueId());
        if (returning != null) returning.backNow();
    }

    // --------------------------------------------------------------- controls

    /** Pauses it, or lets it run on. At the end, it starts again. */
    public void toggle() {
        if (!playback.isPaused()) {
            playback.pause();
            return;
        }
        if (playback.tick() >= playback.frames() - 1) playback.seek(0);
        playback.resume();
    }

    /** Moves by a number of seconds, backwards when negative. */
    public void skip(int seconds) {
        playback.seek(playback.tick() + seconds * 20);
    }

    /** One frame on or back, and held there. */
    public void frame(int ticks) {
        playback.step(ticks);
    }

    /** The next speed up. */
    public void faster() {
        double now = playback.speed();
        for (double speed : SPEEDS) {
            if (speed > now + 1e-9) {
                playback.speed(speed);
                return;
            }
        }
    }

    /** The next speed down. */
    public void slower() {
        double now = playback.speed();
        for (int index = SPEEDS.length - 1; index >= 0; index--) {
            if (SPEEDS[index] < now - 1e-9) {
                playback.speed(SPEEDS[index]);
                return;
            }
        }
    }

    /** From the beginning. */
    public void restart() {
        playback.seek(0);
        playback.resume();
    }

    /**
     * Jumps to the next mark of one of these kinds, a second before it so the
     * moment can be seen coming.
     *
     * @param kinds which, such as {@link ReplayMark#ATTACK} and {@link ReplayMark#DEATH}
     * @return whether there was one
     */
    public boolean next(@NotNull Set<String> kinds) {
        int now = playback.tick();
        for (ReplayMark mark : playback.replay().marks()) {
            if (mark.tick() > now + 20 && kinds.contains(mark.kind())) {
                playback.seek(Math.max(0, mark.tick() - 20));
                return true;
            }
        }
        return false;
    }

    /** The same, backwards. */
    public boolean previous(@NotNull Set<String> kinds) {
        int now = playback.tick();
        List<ReplayMark> marks = playback.replay().marks();
        for (int index = marks.size() - 1; index >= 0; index--) {
            ReplayMark mark = marks.get(index);
            if (mark.tick() < now - 20 && kinds.contains(mark.kind())) {
                playback.seek(Math.max(0, mark.tick() - 20));
                return true;
            }
        }
        return false;
    }

    /**
     * Looks out of the next player's eyes, in the order they are in the
     * recording, and then back out of the viewer's own.
     *
     * @return who is being followed now, or {@code null} for nobody
     */
    public @Nullable ReplayActor cycleFollow() {
        List<ReplayActor> players = present();
        UUID current = playback.following();
        int at = -1;
        for (int index = 0; index < players.size(); index++) {
            if (players.get(index).id().equals(current)) at = index;
        }
        ReplayActor next = at + 1 < players.size() ? players.get(at + 1) : null;
        playback.follow(next == null ? null : next.id());
        return next;
    }

    /**
     * The players on screen on the frame being shown, in the order they are
     * in the recording: who {@link #goTo} and {@link #follow} can be pointed at.
     *
     * @since 1.244.0
     */
    public @NotNull List<ReplayActor> present() {
        List<ReplayActor> players = new ArrayList<>();
        int tick = playback.tick();
        for (ReplayActor actor : playback.replay().actors()) {
            if (actor.isPlayer() && playback.replay().at(tick, actor.id()).present()) players.add(actor);
        }
        return players;
    }

    /**
     * Looks out of one player's eyes.
     *
     * @param actor who, or {@code null} for the viewer's own
     * @since 1.244.0
     */
    public void follow(@Nullable UUID actor) {
        playback.follow(actor);
    }

    /** Stops following anybody. */
    public void unfollow() {
        playback.follow(null);
    }

    /** Flies over to where somebody is right now. */
    public void goTo(@NotNull UUID actor) {
        Location there = playback.locationOf(actor);
        if (there != null) Teleports.of(ReplayRuntime.plugin())
                .to(viewer, behind(there)).warmup(0).start();
    }

    /** Turns the noise on or off. */
    public void sounds(boolean audible) {
        playback.sounds(audible);
    }

    /**
     * Ends it, sends them back where they were as they were, and lets the
     * stage go. Safe to call twice.
     */
    public void leave() {
        leave(true, false);
    }

    /**
     * @param restore whether to put the viewer back where they were; not when
     *                they are going straight into another replay
     * @param now     on the spot rather than through the teleport module: they
     *                are quitting, or the plugin or the server is stopping
     */
    private void leave(boolean restore, boolean now) {
        if (left) return;
        left = true;
        WATCHING.remove(viewer.getUniqueId(), this);
        TaskHandle task = hudTask;
        if (task != null) task.cancel();
        playback.stop();
        stage.close();
        if (!restore || !viewer.isOnline()) {
            restored.set(true);
            // Gone already: no thread of theirs will ever run it.
            if (live != null && !viewer.isOnline()) live.end();
            else if (live != null) onViewerThread(live::end);
        } else if (now) {
            // On the viewer's own region: a plugin stopping, or another
            // player's click, may be what asked for this.
            onViewerThread(this::backNow);
        } else {
            RETURNING.put(viewer.getUniqueId(), this);
            Location back = saved.location();
            Teleports.of(ReplayRuntime.plugin()).to(viewer, back).warmup(0).start().future()
                    .whenComplete((arrived, failure) -> Tasks.of(ReplayRuntime.plugin()).runAtEntity(viewer, () -> {
                        if (arrived != null && arrived.isSuccess()) {
                            back(false);
                        } else if (net.exylia.lib.util.world.TemporaryWorld.is(viewer.getWorld())) {
                            // Refused, or their world is gone: never left on a
                            // stage, where the void is all there is to stand on.
                            Teleports.of(ReplayRuntime.plugin())
                                    .to(viewer, org.bukkit.Bukkit.getWorlds().getFirst().getSpawnLocation())
                                    .warmup(0).start().future()
                                    .whenComplete((spawned, ignored) -> Tasks.of(ReplayRuntime.plugin())
                                            .runAtEntity(viewer, () -> back(false)));
                        } else {
                            back(false);
                        }
                    }));
        }
        Runnable listener = onLeave;
        if (listener != null) listener.run();
    }

    /** Puts them back right here and now, the place included. */
    private void backNow() {
        back(true);
    }

    /**
     * Everything that was changed about them, put back once; and only then is
     * anybody shown again, so nobody sees them appear over the fight first.
     */
    private void back(boolean place) {
        if (!restored.compareAndSet(false, true)) return;
        RETURNING.remove(viewer.getUniqueId(), this);
        if (live != null) live.end();
        if (!viewer.isOnline()) return;
        saved.restore(viewer, place);
        viewer.sendActionBar(Component.empty());
    }

    private void onViewerThread(Runnable action) {
        if (org.bukkit.Bukkit.isOwnedByCurrentRegion(viewer)) {
            action.run();
        } else {
            scheduler.runAtEntity(viewer, action);
        }
    }

    // ----------------------------------------------------------------- status

    /**
     * What the line above the hotbar says, refreshed every few ticks on the
     * viewer's thread.
     *
     * @param format turns the numbers into the plugin's own text
     */
    public void hud(@NotNull Function<Status, Component> format) {
        this.hud = format;
    }

    /** Called once they have left, for whatever the plugin lent them. */
    public void onLeave(@NotNull Runnable listener) {
        this.onLeave = listener;
    }

    /** Called for the marks a plugin wants to show, such as chat and deaths. */
    public void onMark(@NotNull Consumer<ReplayMark> listener) {
        playback.onMark(listener);
    }

    public @NotNull ReplayPlayback playback() {
        return playback;
    }

    public @NotNull Player viewer() {
        return viewer;
    }

    public @NotNull Status status() {
        UUID following = playback.following();
        ReplayActor followed = following == null ? null : playback.replay().actor(following);
        return new Status(playback.tick(), playback.frames(), playback.speed(), playback.isPaused(),
                playback.scene(), playback.replay().scenes().size(),
                followed == null ? null : followed.name());
    }

    private void refresh() {
        if (left) return;
        if (!viewer.isOnline()) {
            leave();
            return;
        }
        if (!playback.isPlaying()) {
            leave();
            return;
        }
        if (live != null) live.sweep();
        Function<Status, Component> format = hud;
        if (format != null) viewer.sendActionBar(format.apply(status()));
    }

    /**
     * Where the playback stands, for the plugin's own line of text.
     *
     * @param tick      which tick is on screen
     * @param frames    how many there are
     * @param speed     how fast it runs
     * @param paused    whether it is held
     * @param scene     which scene, from zero
     * @param scenes    how many scenes there are
     * @param following whose eyes the viewer is looking out of, or {@code null}
     */
    public record Status(int tick, int frames, double speed, boolean paused, int scene, int scenes,
                         @Nullable String following) {

        /** Seconds in, as {@code m:ss}. */
        public @NotNull String elapsed() {
            return clock(tick / 20);
        }

        /** The whole length, as {@code m:ss}. */
        public @NotNull String length() {
            return clock(frames / 20);
        }

        /** The speed, as {@code 0.25x}. */
        public @NotNull String speedText() {
            return (speed == Math.floor(speed) ? String.valueOf((int) speed)
                    : String.valueOf(speed).replaceAll("0+$", "")) + "x";
        }

        /**
         * A progress bar made of two strings, so the plugin chooses the glyph
         * and the colours.
         *
         * @param width how many cells
         * @param done  what a played cell is, colour included
         * @param left  what an unplayed cell is
         */
        public @NotNull String bar(int width, @NotNull String done, @NotNull String left) {
            int filled = frames <= 1 ? width : (int) Math.round((double) tick / (frames - 1) * width);
            return done.repeat(Math.clamp(filled, 0, width)) + left.repeat(Math.clamp(width - filled, 0, width));
        }

        private static String clock(int seconds) {
            return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static @Nullable Location firstSeen(ReplayStage stage, UUID actor) {
        Replay replay = stage.replay();
        for (int tick = 0; tick < replay.frames(); tick++) {
            Location at = stage.locate(tick, actor);
            if (at != null) return at;
        }
        return null;
    }

    /** Three blocks behind and two above somebody, looking at them. */
    private static Location behind(Location at) {
        double yaw = Math.toRadians(at.getYaw());
        Location seat = at.clone().add(Math.sin(yaw) * 3, 2, -Math.cos(yaw) * 3);
        seat.setPitch(25f);
        return seat;
    }

    /** The viewer as a flying, untouchable camera with a hotbar. */
    private static void camera(Player viewer) {
        viewer.setGameMode(GameMode.ADVENTURE);
        viewer.setAllowFlight(true);
        viewer.setFlying(true);
        viewer.setInvulnerable(true);
        viewer.setFallDistance(0f);
    }

    /** Everything changed about a viewer, to put back. */
    private record Saved(Location location, GameMode mode, boolean allowFlight, boolean flying,
                         boolean invulnerable) {

        static Saved of(Player viewer) {
            return new Saved(viewer.getLocation(), viewer.getGameMode(), viewer.getAllowFlight(),
                    viewer.isFlying(), viewer.isInvulnerable());
        }

        /**
         * @param place whether to move them too; only when they are quitting or
         *              the server is stopping, everything else has already
         *              brought them back through the teleport module
         */
        void restore(Player viewer, boolean place) {
            viewer.setGameMode(mode);
            viewer.setAllowFlight(allowFlight);
            viewer.setFlying(allowFlight && flying);
            viewer.setInvulnerable(invulnerable);
            viewer.setFallDistance(0f);
            if (!place) return;
            // Directly, and synchronously where the platform allows it: this
            // runs while they are quitting or the server stops, when the
            // teleport module would still be waiting for a tick, and a
            // teleport still in flight when the connection closes is a player
            // saved standing on the stage.
            if (net.exylia.lib.platform.Platform.isFolia()) viewer.teleportAsync(location);
            else viewer.teleport(location);
        }
    }
}
