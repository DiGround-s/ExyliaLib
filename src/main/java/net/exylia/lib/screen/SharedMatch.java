package net.exylia.lib.screen;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/**
 * One game shared by several players, each on a screen of their own. A screen has one viewer, so what a player may not
 * see cannot reach their client as long as it is never handed to their {@link Seat}: a seat holds its own values, its
 * own scene and its own record, and nothing passes from one seat to another. What everyone may see goes through
 * {@link #everyone}, on purpose and by name.
 *
 * <p>The seats of a match need not be all of its players: someone playing the same match in the world has no seat here
 * and never hears of it, so forms mix. See docs/screens.md, "Shared matches".
 *
 * <pre>{@code
 * SharedMatch match = SharedMatch.of(plugin, markup, (seat, stay) -> askToLeave(seat.viewer(), stay));
 * SharedMatch.Seat mine = match.seat(player, Map.of("game", name));     // on the player's thread; null: no screen, use the world
 * mine.drawing(this::draw);                                             // brings the scene back after the player closed it
 * match.everyone("pot", "$200");
 * mine.set("hand", "A♠ K♠");                                            // this viewer's alone
 * match.end();
 * }</pre>
 *
 * @since 1.533.0
 */
public final class SharedMatch {

    /**
     * A seat's screen was closed by something other than this match: Shift, damage, a camera. Nothing is decided by
     * that alone. Runs on the viewer's thread.
     */
    @FunctionalInterface
    public interface Closed {

        /**
         * @param seat the seat whose screen went
         * @param stay brings the screen back, as it stood; run it when the player chooses to stay
         */
        void closed(@NotNull Seat seat, @NotNull Runnable stay);
    }

    private final ScreenMarkup markup;
    private final Closed closed;
    private final BiFunction<Player, Screen, ScreenSession> opener;
    private final List<Seat> seats = new CopyOnWriteArrayList<>();
    /** What everyone may see, so a seat raised late or coming back has it too. */
    private final Map<String, Object> shared = new ConcurrentHashMap<>();
    private volatile boolean ended;

    SharedMatch(ScreenMarkup markup, Closed closed, BiFunction<Player, Screen, ScreenSession> opener) {
        this.markup = GameFrame.install(markup);
        this.closed = closed;
        this.opener = opener;
    }

    /**
     * @param markup the game's markup; {@link GameFrame} is installed on it
     * @param closed what a seat's screen closing means in this game
     */
    public static @NotNull SharedMatch of(@NotNull Plugin plugin, @NotNull ScreenMarkup markup, @NotNull Closed closed) {
        Screens screens = Screens.of(plugin);
        return new SharedMatch(markup, closed, screens::open);
    }

    /**
     * Opens a player's screen for this match. Runs on the player's thread.
     *
     * @param state what the screen starts with, on top of what {@link #everyone} has been told
     * @return the seat, or {@code null} when they cannot have a screen now: they play in the world instead
     */
    public @Nullable Seat seat(@NotNull Player viewer, @NotNull Map<String, ?> state) {
        Seat seat = new Seat(viewer, state);
        if (!seat.raise()) return null;
        seats.add(seat);
        return seat;
    }

    /** Tells every seat a value anyone at the table may see. Never a hand, a ship, a word: those go on one {@link Seat}. */
    public void everyone(@NotNull String key, @NotNull Object value) {
        shared.put(key, value);
        for (Seat seat : seats) seat.set(key, value);
    }

    /** The seats on screens right now, whatever others play in the world. */
    public @NotNull List<Seat> seats() {
        return List.copyOf(seats);
    }

    /** The match is over: every screen closes, and none of it counts as a player closing theirs. Safe to call twice. */
    public void end() {
        ended = true;
        for (Seat seat : seats) seat.close();
        seats.clear();
    }

    /** One player's place in the match: their screen, and everything only they may see. */
    public final class Seat {

        private final Player viewer;
        private final Map<String, Object> values = new HashMap<>();
        private volatile ScreenSession session;
        private volatile GameFrame frame;
        private volatile Runnable drawing;
        private volatile boolean closing;

        private Seat(Player viewer, Map<String, ?> state) {
            this.viewer = viewer;
            values.putAll(state);
        }

        private boolean raise() {
            Map<String, Object> state = new HashMap<>(GameFrame.state());
            state.putAll(shared);
            synchronized (values) {
                state.putAll(values);
            }
            ScreenSession opened = opener.apply(viewer, markup.screen(state).onClose(gone -> {
                if (gone != session || closing || ended) return;
                closed.closed(this, this::comeBack);
            }));
            if (opened == null) return false;
            session = opened;
            frame = GameFrame.of(opened, markup);
            return true;
        }

        /** The player chose to stay: the screen as it stands now, its scene drawn from nothing. */
        private void comeBack() {
            if (closing || ended || !raise()) return;
            Runnable draw = drawing;
            if (draw != null) draw.run();
        }

        public @NotNull Player viewer() {
            return viewer;
        }

        /** Sets a value on this viewer's screen alone; it survives the screen closing and coming back. Any thread. */
        public void set(@NotNull String key, @Nullable Object value) {
            synchronized (values) {
                if (value == null) values.remove(key);
                else values.put(key, value);
            }
            ScreenSession live = session;
            if (live != null && live.open()) live.set(key, value);
        }

        /** Whether the screen is up: while the player is answering whether to stay it is not, and the match goes on. */
        public boolean open() {
            ScreenSession live = session;
            return live != null && live.open();
        }

        /** The scene of this viewer's screen, for sprites only they get. A closed screen draws nothing. */
        public @NotNull ScreenScene scene(@NotNull String id) {
            return session.scene(id);
        }

        /** The frame of the screen up now: the result, in place. */
        public @NotNull GameFrame frame() {
            return frame;
        }

        /**
         * What draws the scene from nothing, from the viewer's own record, when the screen comes back. A scene is
         * lost with its screen; the values are not.
         */
        public void drawing(@NotNull Runnable draw) {
            drawing = draw;
        }

        /** This player is out of the match: the screen closes, and that is not them closing it. Safe to call twice. */
        public void close() {
            closing = true;
            ScreenSession live = session;
            if (live != null && live.open()) live.close();
        }
    }
}
