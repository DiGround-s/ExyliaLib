package net.exylia.lib.replay;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
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

    private final String owner;
    private final Player viewer;
    private final TaskScheduler scheduler;
    private final ReplayStage stage;
    private final ReplayPlayback playback;
    private final Saved saved;
    private volatile TaskHandle hudTask;
    private volatile Function<Status, Component> hud;
    private volatile Runnable onLeave;
    private volatile boolean left;

    private ReplayViewer(String owner, Player viewer, TaskScheduler scheduler, ReplayStage stage,
                         ReplayPlayback playback, Saved saved) {
        this.owner = owner;
        this.viewer = viewer;
        this.scheduler = scheduler;
        this.stage = stage;
        this.playback = playback;
        this.saved = saved;
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
        ReplayViewer previous = WATCHING.get(viewer.getUniqueId());
        // Going from one replay straight to another: where they really were is
        // what the first one saved, not the stage they are still standing on.
        Saved before = previous == null ? null : previous.saved;
        if (previous != null) previous.leave(false);
        Replay replay = stage.replay();
        UUID who = focus != null ? focus : replay.actors().isEmpty() ? null : replay.actors().getFirst().id();
        Location start = who == null ? null : firstSeen(stage, who);
        if (start == null) start = stage.anchor(0).add(0, 2, 0);
        Location seat = behind(start);
        Saved saved = before != null ? before : Saved.of(viewer);
        TaskScheduler scheduler = Tasks.of(plugin);
        CompletableFuture<ReplayViewer> opened = new CompletableFuture<>();
        viewer.teleportAsync(seat).whenComplete((arrived, failure) -> scheduler.runAtEntity(viewer, () -> {
            if (failure != null || !Boolean.TRUE.equals(arrived) || !viewer.isOnline()) {
                stage.close();
                opened.completeExceptionally(new IllegalStateException("The viewer could not be moved to the stage"));
                return;
            }
            camera(viewer);
            ReplayPlayback playback = stage.play(List.of(viewer));
            if (playback == null) {
                saved.restore(viewer);
                stage.close();
                opened.completeExceptionally(new IllegalStateException("Replays cannot be shown on this server"));
                return;
            }
            playback.reveal(true);
            ReplayViewer watching = new ReplayViewer(plugin.getName(), viewer, scheduler, stage, playback, saved);
            WATCHING.put(viewer.getUniqueId(), watching);
            watching.hudTask = scheduler.runAtEntityTimer(viewer, 1L, HUD_TICKS, watching::refresh);
            opened.complete(watching);
        }, () -> {
            // They left before arriving: nothing to put back, and nobody to
            // show the stage to.
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
        new ArrayList<>(WATCHING.values()).forEach(ReplayViewer::leave);
    }

    /** Sends back everybody one plugin put on a stage, when it is disabled. */
    public static void release(@NotNull String pluginName) {
        new ArrayList<>(WATCHING.values()).stream()
                .filter(watching -> watching.owner.equals(pluginName))
                .forEach(ReplayViewer::leave);
    }

    /** Leaves for somebody who is quitting: put back now, on this thread. */
    public static void quit(@NotNull Player viewer) {
        ReplayViewer watching = WATCHING.get(viewer.getUniqueId());
        if (watching != null) watching.leave();
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
        List<ReplayActor> players = new ArrayList<>();
        int tick = playback.tick();
        for (ReplayActor actor : playback.replay().actors()) {
            if (actor.isPlayer() && playback.replay().at(tick, actor.id()).present()) players.add(actor);
        }
        UUID current = playback.following();
        int at = -1;
        for (int index = 0; index < players.size(); index++) {
            if (players.get(index).id().equals(current)) at = index;
        }
        ReplayActor next = at + 1 < players.size() ? players.get(at + 1) : null;
        playback.follow(next == null ? null : next.id());
        return next;
    }

    /** Stops following anybody. */
    public void unfollow() {
        playback.follow(null);
    }

    /** Flies over to where somebody is right now. */
    public void goTo(@NotNull UUID actor) {
        Location there = playback.locationOf(actor);
        if (there != null) viewer.teleportAsync(behind(there));
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
        leave(true);
    }

    /**
     * @param restore whether to put the viewer back where they were; not when
     *                they are going straight into another replay
     */
    private void leave(boolean restore) {
        if (left) return;
        left = true;
        WATCHING.remove(viewer.getUniqueId(), this);
        TaskHandle task = hudTask;
        if (task != null) task.cancel();
        playback.stop();
        stage.close();
        if (restore && viewer.isOnline()) {
            // On the viewer's own region: a plugin stopping, or another
            // player's click, may be what asked for this.
            if (org.bukkit.Bukkit.isOwnedByCurrentRegion(viewer)) {
                saved.restore(viewer);
                viewer.sendActionBar(Component.empty());
            } else {
                scheduler.runAtEntity(viewer, () -> {
                    saved.restore(viewer);
                    viewer.sendActionBar(Component.empty());
                });
            }
        }
        Runnable listener = onLeave;
        if (listener != null) listener.run();
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

        void restore(Player viewer) {
            viewer.setGameMode(mode);
            viewer.setAllowFlight(allowFlight);
            viewer.setFlying(allowFlight && flying);
            viewer.setInvulnerable(invulnerable);
            viewer.setFallDistance(0f);
            // Synchronously where the platform allows it: this also runs while
            // they are quitting, and a teleport still in flight when the
            // connection closes is a player saved standing on the stage.
            if (net.exylia.lib.platform.Platform.isFolia()) viewer.teleportAsync(location);
            else viewer.teleport(location);
        }
    }
}
