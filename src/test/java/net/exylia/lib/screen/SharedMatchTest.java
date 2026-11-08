package net.exylia.lib.screen;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A seat's values never reach another, and a screen closing decides nothing by itself. */
class SharedMatchTest {

    private static Player player(String name) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (self, method, args) ->
                switch (method.getName()) {
                    case "getName", "toString" -> name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> self == args[0];
                    default -> null;
                });
    }

    /** One client: what it was ever sent. */
    private static final class Client implements ScreenSession {
        final Player viewer;
        final Screen screen;
        final Map<String, Object> sent = new HashMap<>();
        boolean up = true;

        Client(Player viewer, Screen screen) {
            this.viewer = viewer;
            this.screen = screen;
        }

        @Override
        public @NotNull Player viewer() {
            return viewer;
        }

        @Override
        public @NotNull Screen screen() {
            return screen;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> @NotNull T state(@NotNull String key, @NotNull T fallback) {
            return (T) sent.getOrDefault(key, fallback);
        }

        @Override
        public void set(@NotNull String key, @Nullable Object value) {
            sent.put(key, value);
        }

        @Override
        public void redraw() {
        }

        @Override
        public boolean open() {
            return up;
        }

        @Override
        public void close() {
            up = false;
        }

        /** What the player or the world does: Shift, damage. */
        void gone() {
            up = false;
            screen.onClose().accept(this);
        }
    }

    private final List<Client> clients = new ArrayList<>();
    private final BiFunction<Player, Screen, ScreenSession> opener = (viewer, screen) -> {
        Client client = new Client(viewer, screen);
        client.sent.putAll(screen.state());
        clients.add(client);
        return client;
    };

    private final ScreenMarkup markup = ScreenMarkup.parse("t", "<screen><text>x</text></screen>");

    @Test
    void whatOneSeatIsToldNeverReachesAnother() {
        SharedMatch match = new SharedMatch(markup, (seat, stay) -> { }, opener);
        SharedMatch.Seat first = match.seat(player("A"), Map.of());
        SharedMatch.Seat second = match.seat(player("B"), Map.of());
        first.set("hand", "A♠");
        second.set("hand", "K♦");
        match.everyone("pot", "$200");
        assertEquals("A♠", clients.get(0).sent.get("hand"));
        assertEquals("K♦", clients.get(1).sent.get("hand"));
        assertEquals("$200", clients.get(0).sent.get("pot"));
        assertEquals("$200", clients.get(1).sent.get("pot"));
    }

    @Test
    void noScreenIsNoSeat() {
        SharedMatch match = new SharedMatch(markup, (seat, stay) -> { }, (viewer, screen) -> null);
        assertNull(match.seat(player("A"), Map.of()));
        assertEquals(List.of(), match.seats());
    }

    @Test
    void closingAsksAndStayingBringsItBackAsItStood() {
        List<Runnable> stays = new ArrayList<>();
        SharedMatch match = new SharedMatch(markup, (seat, stay) -> stays.add(stay), opener);
        SharedMatch.Seat seat = match.seat(player("A"), Map.of("game", "Chess"));
        int[] drawn = {0};
        seat.drawing(() -> drawn[0]++);
        seat.set("total", "17");
        clients.getFirst().gone();
        assertEquals(1, stays.size(), "the rule was asked once");
        assertFalse(seat.open());
        seat.set("total", "19");
        stays.getFirst().run();
        assertTrue(seat.open());
        assertEquals(2, clients.size());
        assertNotSame(clients.get(0), clients.get(1));
        assertEquals(1, drawn[0]);
        assertEquals("19", clients.get(1).sent.get("total"), "changed while it was closed");
        assertEquals("Chess", clients.get(1).sent.get("game"));
    }

    @Test
    void theEndAndALeaverCloseWithoutAsking() {
        int[] asked = {0};
        SharedMatch match = new SharedMatch(markup, (seat, stay) -> asked[0]++, opener);
        SharedMatch.Seat first = match.seat(player("A"), Map.of());
        match.seat(player("B"), Map.of());
        first.close();
        clients.getFirst().gone();
        assertEquals(0, asked[0], "a seat that left");
        match.end();
        clients.get(1).gone();
        assertEquals(0, asked[0], "the end of the match");
        assertEquals(List.of(), match.seats());
        match.end();
    }
}
