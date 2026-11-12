package net.exylia.lib.screen.kit;

import net.exylia.lib.screen.ScreenScene;
import net.exylia.lib.stage.kit.PlayingCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The game kit: what lands where, and what is not sent early. */
class ScreenKitTest {

    private final ScreenScene scene = new ScreenScene("board", () -> { });

    @Test
    void aReelLandsTopToBottomAndCallsBack() {
        Map<String, String> shows = new HashMap<>();
        List<String> ever = new ArrayList<>();
        ScreenReel<String> reel = new ScreenReel<>(scene, "reel", 0, 0, 40, 3, (sprite, symbol) -> {
            shows.put(sprite.name(), symbol);
            ever.add(symbol);
            sprite.rect(32, 32, "{primary}");
        });
        reel.rest(List.of("a", "b", "c"));
        ever.clear();
        boolean[] landed = {false};
        reel.spin(30, () -> "decoy", List.of("x", "y", "z"), () -> landed[0] = true);
        assertTrue(landed[0]);
        assertEquals(List.of("x", "y", "z"), List.of(at(reel, 0), at(reel, 40), at(reel, 80)).stream().map(shows::get).toList());
        // Nothing of the landing is shown before the steps that bring it in.
        assertEquals("decoy", ever.getFirst());
        assertEquals(List.of("z", "y", "x"), ever.stream().filter(symbol -> !symbol.equals("decoy")).toList());
    }

    private String at(ScreenReel<String> reel, double y) {
        for (int i = 0; i < 5; i++) {
            ScreenScene.Sprite sprite = scene.sprite("reel_" + i);
            if (Math.abs(sprite.y() - y) < 0.01) return sprite.name();
        }
        throw new AssertionError("nothing at " + y);
    }

    @Test
    void reelStepsAddUpToAboutTheTicksAskedAndSlowAtTheEnd() {
        List<Integer> lengths = ScreenReel.lengths(30, 3);
        int total = lengths.stream().mapToInt(Integer::intValue).sum();
        assertTrue(Math.abs(total - 30) <= 2, "ran " + total);
        assertEquals(2, lengths.getFirst());
        assertEquals(5, lengths.getLast());
    }

    @Test
    void anOrbitLandsTheChosenSpriteAtTheTop() {
        ScreenOrbit wheel = new ScreenOrbit(scene, 100, 100, 60);
        ScreenScene.Sprite three = scene.sprite("three").rect(10, 10, "{primary}");
        wheel.add(three, 90, 10, 10);
        assertEquals(155, three.x(), 0.001);
        double to = wheel.landing(90, 2);
        assertEquals(990, to, 0.001);
        boolean[] landed = {false};
        wheel.spin(to, 30, 3, 60, null, () -> landed[0] = true);
        assertTrue(landed[0]);
        assertEquals(95, three.x(), 0.001);
        assertEquals(35, three.y(), 0.001);
    }

    @Test
    void anOrbitBallDropsToTheRadiusAsked() {
        ScreenOrbit ball = new ScreenOrbit(scene, 50, 50, 40);
        ScreenScene.Sprite sprite = scene.sprite("ball").rect(4, 4, "{letters}");
        ball.add(sprite, 0, 4, 4);
        ball.spin(ball.landing(0, 3), 30, 2, 30, null, null);
        assertEquals(18, sprite.y(), 0.001);
    }

    @Test
    void aDieEndsOnItsValue() {
        ScreenDice dice = new ScreenDice(ScreenDice.Look.standard());
        for (int value = 1; value <= 6; value++) {
            ScreenScene.Sprite sprite = scene.sprite("die" + value);
            dice.roll(sprite, scene, 10, 20, value, 16);
            assertEquals(10, sprite.x(), 0.001);
            assertEquals(20, sprite.y(), 0.001);
            assertEquals(new int[]{1, 2, 3, 4, 5, 6}[value - 1], pips(dice, value));
        }
    }

    private static int pips(ScreenDice dice, int value) {
        return dice.face(value).children().size();
    }

    @Test
    void aCardShowsOnlyAfterItIsTurned() {
        ScreenCards cards = new ScreenCards(ScreenCards.Look.standard());
        ScreenScene.Sprite sprite = scene.sprite("card");
        cards.dealt(sprite, scene, 100, 0, 20, 10, 6);
        assertEquals(20, sprite.x(), 0.001);
        assertFalse(cards.back().children().isEmpty());
        cards.turn(sprite, new PlayingCard(14, PlayingCard.Suit.HEARTS), 6);
        assertEquals(2, cards.face(new PlayingCard(14, PlayingCard.Suit.HEARTS)).children().size());
    }

    @Test
    void aTrailThinsAndKeepsTheLastPoint() {
        ScreenTrail trail = new ScreenTrail(scene, "dot", 4, 2);
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i <= 9; i++) points.add(new double[]{i * 10, 0});
        trail.draw(points, "{success}", 2);
        assertEquals(89, scene.sprite("dot3").x(), 0.001);
        assertFalse(scene.has("dot4"));
        trail.draw(points.subList(0, 2), "{success}", 2);
        assertFalse(scene.has("dot2"));
        trail.clear();
        assertFalse(scene.has("dot0"));
    }

    @Test
    void anOrbitWithoutMotionSendsTheLandingOnlyWhenTheSpinWouldEnd() {
        List<Runnable> waiting = new ArrayList<>();
        ScreenScene still = new ScreenScene("still", new ScreenScene.Host() {
            @Override
            public void redraw() {
            }

            @Override
            public void later(int ticks, Runnable step) {
                waiting.add(step);
            }

            @Override
            public boolean reduced() {
                return true;
            }

            @Override
            public boolean spare() {
                return true;
            }
        });
        ScreenOrbit ball = new ScreenOrbit(still, 50, 50, 40);
        ScreenScene.Sprite sprite = still.sprite("ball").rect(4, 4, "{letters}");
        ball.add(sprite, 0, 4, 4);
        double x = sprite.x(), y = sprite.y();
        boolean[] landed = {false};
        ball.spin(ball.landing(90, 3), 30, 2, 30, null, () -> landed[0] = true);
        assertEquals(x, sprite.x(), 0.001);
        assertEquals(y, sprite.y(), 0.001);
        assertFalse(landed[0]);
        new ArrayList<>(waiting).forEach(Runnable::run);
        assertTrue(landed[0]);
        assertEquals(18, sprite.x(), 0.001);
    }

    @Test
    void aHandSpreadsWhileItFitsThenOverlapsWithinItsStrip() {
        ScreenHand hand = new ScreenHand(10, 280, 26);
        assertEquals(28, hand.pitch(3));
        assertEquals(10 + (280 - 82) / 2.0, hand.x(0, 3));
        for (int count : new int[]{1, 5, 10, 20, 40}) {
            assertTrue(hand.x(0, count) >= 10);
            assertEquals(10 + 280, hand.x(count - 1, count) + 26 + (hand.x(0, count) - 10), 1e-9);
            assertTrue(hand.pitch(count) > 0);
        }
        assertTrue(hand.pitch(20) < hand.pitch(10));
    }
}
