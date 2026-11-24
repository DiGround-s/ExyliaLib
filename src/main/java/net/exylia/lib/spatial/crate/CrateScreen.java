package net.exylia.lib.spatial.crate;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.ScreenMarkup;
import net.exylia.lib.screen.ScreenScene;
import net.exylia.lib.screen.ScreenSession;
import net.exylia.lib.screen.ScreenSet;
import net.exylia.lib.screen.Screens;
import net.exylia.lib.screen.internal.ScreenRuntime;
import net.exylia.lib.screen.kit.ScreenReel;
import net.exylia.lib.stage.StageLook;
import net.exylia.lib.ui.MenuStyles;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The crate opening on a full screen: a reel of faces sliding leftwards under a marker and slowing onto the prize, the
 * prize growing in its place with its name, rarity and what became of it (new, or a duplicate and what it gave back), a
 * burst and a short sound scaled to its rarity; each prize of the opening in turn, then OPEN ANOTHER (while the show
 * says the player can pay for one) and DONE. The same {@link CrateShow} as {@link CrateScene}, drawn on a screen instead
 * of in the world, so nothing in front of the player cuts through it.
 *
 * <pre>{@code
 * if (!CrateScreen.of(plugin).play(player, show)) tellInChat(...);   // false: screens cannot be drawn for them
 * }</pre>
 *
 * <p>The look is {@code plugins/<Plugin>/crate/opening.html} and {@code opening.css} (words in
 * {@code lang/<code>/crate/opening.yml}), written from the library the first time and restyled like any screen.
 *
 * <p>Presentation only: the prizes arrive drawn and paid for. {@link CrateShow#reveal} hands each over once as its reel
 * lands; closing the screen (shift, DONE, another screen, death, a world change) hands every one not revealed yet over
 * at once. {@link CrateShow#ended} runs exactly once: {@code true} when the player quits, whose prizes the caller's quit
 * path pays. Previews ({@link CrateShow#preview}) are not drawn on a screen.
 *
 * @since 1.654.0
 */
public final class CrateScreen {

    private static final Map<String, CrateScreen> BY_PLUGIN = new ConcurrentHashMap<>();
    private static final Map<UUID, Run> PLAYING = new ConcurrentHashMap<>();

    /** The canvas, in screen pixels: its size is the markup's {@code canvas} rule. */
    static final double WIDTH = 376, HEIGHT = 104;
    static final int SLOTS = 7;
    static final double PITCH = 48, ICON = 30, HERO = 58;
    static final int SPIN_TICKS = 80, HOLD_TICKS = 70;
    private static final String TICK = "minecraft:block.note_block.hat|0.4|1.6";
    private static final List<String> SOUNDS = List.of(
            "minecraft:entity.experience_orb.pickup|0.6|1.2",
            "minecraft:block.note_block.chime|0.6|1.2",
            "minecraft:block.note_block.bell|0.6|1.4",
            "minecraft:block.amethyst_block.chime|0.7|1.6");

    private final Plugin plugin;
    private volatile ScreenMarkup markup;

    private CrateScreen(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(new Listener() {
            /** Before the screen runtime closes the screen: a leaving player is paid by the caller's quit path. */
            @EventHandler(priority = EventPriority.LOWEST)
            public void onQuit(PlayerQuitEvent event) {
                Run run = PLAYING.get(event.getPlayer().getUniqueId());
                if (run != null && run.owner == CrateScreen.this) run.end(true, false);
            }

            @EventHandler
            public void onDisable(PluginDisableEvent event) {
                if (event.getPlugin() != plugin) return;
                for (Run run : List.copyOf(PLAYING.values())) if (run.owner == CrateScreen.this) run.end(false, false);
                BY_PLUGIN.remove(plugin.getName(), CrateScreen.this);
            }
        }, plugin);
    }

    /** The plugin's crate screen, made on first use. */
    public static @NotNull CrateScreen of(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> new CrateScreen(plugin));
    }

    /** Whether a screen can be drawn for this player now: PacketEvents, a client that draws screens, no camera holding them. */
    public boolean available(@NotNull Player player) {
        return player.isOnline() && MenuStyles.canShowScreens(player) && !ScreenRuntime.cameraHeld(player);
    }

    /**
     * Plays an opening on a screen, on the player's thread. A crate screen already open for them is closed first, its
     * prizes handed over.
     *
     * @return {@code false} when it cannot be drawn (see {@link #available}, or the markup does not read): nothing of
     *         {@code show} was asked then, so the caller shows it another way
     */
    public boolean play(@NotNull Player player, @NotNull CrateShow show) {
        if (!available(player) || show.prizes().isEmpty()) return false;
        ScreenMarkup look = markup();
        if (look == null) return false;
        Run old = PLAYING.get(player.getUniqueId());
        if (old != null) old.end(false, false);
        Run run = new Run(this, player, show);
        PLAYING.put(player.getUniqueId(), run);
        Screen screen = look.screen().onInput(session -> run.hurry()).onClose(session -> run.end(false, true));
        ScreenSession session = Screens.of(plugin).open(player, screen);
        if (session == null) {
            PLAYING.remove(player.getUniqueId(), run);
            return false;
        }
        run.start(session);
        return true;
    }

    /** Ends the player's opening, handing over every prize not revealed yet. */
    public void close(@NotNull Player player) {
        Run run = PLAYING.get(player.getUniqueId());
        if (run != null && run.owner == this && run.session != null) run.session.close();
    }

    /** Whether the player is watching a crate opening on a screen, of any plugin's. */
    public static boolean isPlaying(@NotNull Player player) {
        return PLAYING.containsKey(player.getUniqueId());
    }

    /** The markup, installed and bound on first use; {@code null} when it does not read. */
    private @Nullable ScreenMarkup markup() {
        ScreenMarkup known = markup;
        if (known != null) return known;
        synchronized (this) {
            if (markup != null) return markup;
            try {
                ScreenSet files = Screens.of(plugin).files(CrateScreen.class, "crate");
                files.reload();
                ScreenMarkup read = files.markup("opening");
                bind(read);
                read.problems().forEach(problem -> Debug.of(plugin).warn("Screen " + problem));
                markup = read;
            } catch (RuntimeException broken) {
                Debug.of(plugin).warn("The crate opening screen could not be read: " + broken.getMessage());
            }
            return markup;
        }
    }

    private static void bind(ScreenMarkup markup) {
        markup.value("crate_current", session -> run(session).map(run -> run.current + 1).orElse(1))
                .value("crate_total", session -> run(session).map(run -> run.prizes.size()).orElse(1))
                .value("crate_name", session -> run(session).map(Run::prize).map(CrateFace::name).orElse(""))
                .value("crate_rarity", session -> run(session).map(Run::prize).map(CrateFace::rarity).orElse(""))
                .value("crate_revealed", session -> run(session).map(run -> run.phase != Phase.SPIN).orElse(false))
                .value("crate_duplicate", session -> run(session).map(run -> run.result() != null && run.result().duplicate()).orElse(false))
                .value("crate_refund", session -> run(session).map(run -> run.result() == null ? 0 : run.result().refund()).orElse(0))
                .value("crate_done", session -> run(session).map(run -> run.phase == Phase.DONE).orElse(false))
                .value("crate_again", session -> run(session).map(run -> run.again).orElse(false))
                .action("crate_again", (event, args) -> run(event.session()).ifPresent(run -> {
                    if (run.phase != Phase.DONE || !run.again) return;
                    run.another = true;
                    event.session().close();
                }))
                .action("crate_done", (event, args) -> event.session().close());
    }

    private static java.util.Optional<Run> run(ScreenSession session) {
        return java.util.Optional.ofNullable(PLAYING.get(session.viewer().getUniqueId()));
    }

    enum Phase { SPIN, HOLD, DONE }

    /** One opening on one screen. Everything but {@link #end} from a quit runs on the viewer's thread. */
    static final class Run {
        final CrateScreen owner;
        final Player viewer;
        final CrateShow show;
        final List<CrateFace> prizes;
        final List<CrateFace> faces;
        final CrateReveal[] results;
        ScreenSession session;
        ScreenScene scene;
        ScreenReel<CrateFace> reel;
        ScreenScene.Timeline timeline;
        Phase phase = Phase.SPIN;
        int current;
        boolean again, another, over;

        Run(CrateScreen owner, Player viewer, CrateShow show) {
            this.owner = owner;
            this.viewer = viewer;
            this.show = show;
            this.prizes = List.copyOf(show.prizes());
            this.faces = show.faces().isEmpty() ? prizes : List.copyOf(show.faces());
            this.results = new CrateReveal[prizes.size()];
        }

        CrateFace prize() {
            return prizes.get(current);
        }

        @Nullable CrateReveal result() {
            return results[current];
        }

        void start(ScreenSession session) {
            this.session = session;
            scene = session.scene("reel");
            double left = (WIDTH - SLOTS * PITCH) / 2;
            double top = (HEIGHT - PITCH) / 2;
            scene.sprite("strip").rect(SLOTS * PITCH, PITCH, "{letters_black}14").at(left, top).layer(-1);
            marker("{highlight}");
            reel = ScreenReel.across(scene, "face", left + (PITCH - ICON) / 2, top + (PITCH - ICON) / 2, PITCH, SLOTS,
                    (sprite, face) -> sprite.size(ICON, ICON).item(face.icon()));
            spin();
        }

        /** The frame round the middle slot, in a colour. */
        private void marker(String colour) {
            double x = (WIDTH - PITCH) / 2, y = (HEIGHT - PITCH) / 2;
            scene.sprite("marker").size(PITCH, PITCH).content(() -> Node.box().size(PITCH, PITCH).outline(colour, 2))
                    .at(x, y).layer(3);
        }

        /** The reel at rest on random faces, then, two ticks later (so it glides), running to the prize. */
        private void spin() {
            phase = Phase.SPIN;
            scene.remove("hero");
            marker("{highlight}");
            reel.rest(randomRow());
            session.redraw();
            CrateFace prize = prize();
            List<CrateFace> landing = new ArrayList<>(randomRow());
            landing.set(SLOTS / 2, prize);
            landing.set(SLOTS / 2 + 1, nearMiss(prize));
            timeline = scene.timeline().at(2, () -> timeline = reel.spin(SPIN_TICKS, this::anyFace, landing,
                    through -> StageLook.play(viewer, TICK, 1 + 0.35 * through), this::landed)).play();
        }

        private List<CrateFace> randomRow() {
            List<CrateFace> row = new ArrayList<>(SLOTS);
            for (int i = 0; i < SLOTS; i++) row.add(anyFace());
            return row;
        }

        private CrateFace anyFace() {
            return faces.get(ThreadLocalRandom.current().nextInt(faces.size()));
        }

        /** A face of the best rarity there is, just past the prize: the near miss. The prize itself when it is the best. */
        private CrateFace nearMiss(CrateFace prize) {
            int best = faces.stream().mapToInt(CrateFace::rank).max().orElse(prize.rank());
            if (prize.rank() >= best) return anyFace();
            List<CrateFace> top = faces.stream().filter(face -> face.rank() == best).toList();
            return top.get(ThreadLocalRandom.current().nextInt(top.size()));
        }

        /** The reel stopped: the prize is handed over and grows in its place. */
        private void landed() {
            if (over) return;
            CrateFace prize = prize();
            reveal(current);
            phase = Phase.HOLD;
            double weight = prize.weight();
            for (int i = 0; i < SLOTS + 2; i++) scene.sprite("face_" + i).scale(0, 4, Motion.Ease.IN);
            marker(prize.colour());
            double x = (WIDTH - HERO) / 2, y = (HEIGHT - HERO) / 2;
            scene.sprite("hero").size(HERO, HERO).item3d(prize.icon(), 30).at(x, y).scale(0, 0, Motion.Ease.LINEAR).layer(4);
            scene.timeline().at(1, () -> scene.sprite("hero").pop(1.15 + 0.25 * weight, 6)).play();
            scene.burst(WIDTH / 2, HEIGHT / 2, 6 + (int) Math.round(14 * weight), 34 + 30 * weight, 10 + (int) Math.round(6 * weight),
                    prize.colour(), "{letters}");
            StageLook.play(viewer, SOUNDS.get((int) Math.round(weight * (SOUNDS.size() - 1))));
            session.redraw();
            timeline = scene.timeline().at(HOLD_TICKS, this::next).play();
        }

        /** A click while a prize is held moves on at once. */
        void hurry() {
            if (phase != Phase.HOLD || over) return;
            if (timeline != null) timeline.cancel();
            next();
        }

        private void next() {
            if (over || phase != Phase.HOLD) return;
            if (current + 1 < prizes.size()) {
                current++;
                spin();
                return;
            }
            phase = Phase.DONE;
            again = show.canOpenAnother();
            session.redraw();
        }

        /** Hands prize {@code index} over, once. */
        private void reveal(int index) {
            if (results[index] == null) results[index] = show.reveal(index);
        }

        /**
         * The opening is over: every prize not revealed yet handed over (not when the player quits), then
         * {@link CrateShow#ended} once, then open another when it was pressed.
         */
        void end(boolean quit, boolean closed) {
            if (over) return;
            over = true;
            PLAYING.remove(viewer.getUniqueId(), this);
            if (timeline != null) timeline.cancel();
            if (!quit) for (int i = 0; i < prizes.size(); i++) reveal(i);
            show.ended(quit);
            if (!quit && another) show.openAnother();
            else if (!closed && !quit && session != null && session.open()) session.close();
        }
    }
}
