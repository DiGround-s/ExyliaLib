package net.exylia.lib.ragdoll.internal;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Letters, drawn as straight strokes, so a body can be laid out into them.
 *
 * <h2>Why a stroke font and not a texture</h2>
 * The pieces of a body are boxes. A box can be moved, turned and stretched, and
 * that is the whole alphabet available: every letter here is therefore a
 * handful of straight segments, and every segment is one or more pieces of
 * somebody laid end to end. Nothing is drawn that is not part of them.
 *
 * <p>Coordinates are in glyph units: {@code 0,0} is the bottom left of a
 * letter and {@code 1,1} its top right. The layout turns those into blocks.
 *
 * <p>Free of Bukkit, so the geometry can be asserted.
 */
@ApiStatus.Internal
public final class RagdollSign {

    /** How wide a letter is compared with its height. */
    private static final double ASPECT = 0.62;

    /** The gap between two letters, as a fraction of a letter's width. */
    private static final double TRACKING = 0.34;

    /** How thick a stroke is compared with the letter's height. */
    private static final double THICKNESS = 0.17;

    private RagdollSign() {
    }

    /** One straight piece of a letter, in glyph units. */
    private record Stroke(double x1, double y1, double x2, double y2) {
    }

    /** Where one piece of a body goes, in blocks, to be part of a letter. */
    public record Placement(double x, double y, double angle, double length, double thickness) {
    }

    /**
     * The alphabet.
     *
     * <p>Only the shapes worth spelling over somebody's body. Anything not in
     * here is skipped rather than refused: a sign that quietly loses a comma is
     * better than a kill effect that does not play.
     */
    private static Stroke[] glyph(char letter) {
        return switch (Character.toUpperCase(letter)) {
            case 'A' -> new Stroke[]{s(0, 0, .5, 1), s(.5, 1, 1, 0), s(.2, .4, .8, .4)};
            case 'B' -> new Stroke[]{s(0, 0, 0, 1), s(0, 1, .85, .78), s(.85, .78, 0, .5),
                    s(0, .5, .85, .25), s(.85, .25, 0, 0)};
            case 'C' -> new Stroke[]{s(1, 1, 0, 1), s(0, 1, 0, 0), s(0, 0, 1, 0)};
            case 'D' -> new Stroke[]{s(0, 0, 0, 1), s(0, 1, .85, .75), s(.85, .75, .85, .25),
                    s(.85, .25, 0, 0)};
            case 'E' -> new Stroke[]{s(0, 0, 0, 1), s(0, 1, 1, 1), s(0, .5, .82, .5), s(0, 0, 1, 0)};
            case 'F' -> new Stroke[]{s(0, 0, 0, 1), s(0, 1, 1, 1), s(0, .5, .82, .5)};
            case 'G' -> new Stroke[]{s(1, 1, 0, 1), s(0, 1, 0, 0), s(0, 0, 1, 0),
                    s(1, 0, 1, .5), s(1, .5, .5, .5)};
            case 'H' -> new Stroke[]{s(0, 0, 0, 1), s(1, 0, 1, 1), s(0, .5, 1, .5)};
            case 'I' -> new Stroke[]{s(.5, 0, .5, 1)};
            case 'K' -> new Stroke[]{s(0, 0, 0, 1), s(1, 1, 0, .5), s(0, .5, 1, 0)};
            case 'L' -> new Stroke[]{s(0, 1, 0, 0), s(0, 0, 1, 0)};
            case 'M' -> new Stroke[]{s(0, 0, 0, 1), s(0, 1, .5, .35), s(.5, .35, 1, 1), s(1, 1, 1, 0)};
            case 'N' -> new Stroke[]{s(0, 0, 0, 1), s(0, 1, 1, 0), s(1, 0, 1, 1)};
            case 'O' -> new Stroke[]{s(0, 0, 0, 1), s(0, 1, 1, 1), s(1, 1, 1, 0), s(1, 0, 0, 0)};
            case 'P' -> new Stroke[]{s(0, 0, 0, 1), s(0, 1, 1, 1), s(1, 1, 1, .5), s(1, .5, 0, .5)};
            case 'R' -> new Stroke[]{s(0, 0, 0, 1), s(0, 1, 1, 1), s(1, 1, 1, .5),
                    s(1, .5, 0, .5), s(0, .5, 1, 0)};
            case 'S' -> new Stroke[]{s(1, 1, 0, 1), s(0, 1, 0, .5), s(0, .5, 1, .5),
                    s(1, .5, 1, 0), s(1, 0, 0, 0)};
            case 'T' -> new Stroke[]{s(0, 1, 1, 1), s(.5, 1, .5, 0)};
            case 'U' -> new Stroke[]{s(0, 1, 0, 0), s(0, 0, 1, 0), s(1, 0, 1, 1)};
            case 'V' -> new Stroke[]{s(0, 1, .5, 0), s(.5, 0, 1, 1)};
            case 'W' -> new Stroke[]{s(0, 1, .25, 0), s(.25, 0, .5, .6), s(.5, .6, .75, 0),
                    s(.75, 0, 1, 1)};
            case 'X' -> new Stroke[]{s(0, 0, 1, 1), s(0, 1, 1, 0)};
            case 'Y' -> new Stroke[]{s(0, 1, .5, .5), s(1, 1, .5, .5), s(.5, .5, .5, 0)};
            case 'Z' -> new Stroke[]{s(0, 1, 1, 1), s(1, 1, 0, 0), s(0, 0, 1, 0)};
            case '!' -> new Stroke[]{s(.5, 1, .5, .3), s(.5, .1, .5, 0)};
            case '?' -> new Stroke[]{s(0, 1, 1, 1), s(1, 1, 1, .5), s(1, .5, .5, .5),
                    s(.5, .5, .5, .3), s(.5, .1, .5, 0)};
            default -> new Stroke[0];
        };
    }

    private static Stroke s(double x1, double y1, double x2, double y2) {
        return new Stroke(x1, y1, x2, y2);
    }

    /**
     * Every stroke of a whole word, in blocks, centred on the middle of the sign.
     *
     * @param text   what it spells
     * @param height how tall a letter is, in blocks
     * @return the strokes, left to right
     */
    private static List<Stroke> laidOut(String text, double height) {
        String word = text.trim().toUpperCase(Locale.ROOT);
        double width = height * ASPECT;
        double step = width * (1 + TRACKING);
        List<Stroke> all = new ArrayList<>();
        double pen = 0;
        for (char letter : word.toCharArray()) {
            if (letter == ' ') {
                pen += step * 0.7;
                continue;
            }
            for (Stroke stroke : glyph(letter)) {
                all.add(new Stroke(
                        pen + stroke.x1() * width, stroke.y1() * height,
                        pen + stroke.x2() * width, stroke.y2() * height));
            }
            pen += step;
        }
        if (all.isEmpty()) {
            return all;
        }
        // Centred on the body it came out of, rather than starting there.
        double middle = (pen - step * TRACKING) / 2;
        List<Stroke> centred = new ArrayList<>(all.size());
        for (Stroke stroke : all) {
            centred.add(new Stroke(stroke.x1() - middle, stroke.y1(),
                    stroke.x2() - middle, stroke.y2()));
        }
        return centred;
    }

    /** How many strokes a word is made of. */
    public static int strokeCount(String text, double height) {
        return laidOut(text, height).size();
    }

    /**
     * Where one piece of a body goes.
     *
     * <p>The pieces are shared out along the strokes in order, so a word with
     * seven strokes and twenty pieces is drawn three pieces to a stroke, laid
     * end to end. Letters come out solid rather than dotted, and every piece is
     * still the colour of the part of them it came from.
     *
     * @param text   what the sign spells
     * @param height how tall a letter is, in blocks
     * @param index  which piece of the body this is
     * @param pieces how many pieces there are
     * @return where it goes, or {@code null} when the word has no strokes
     */
    public static Placement place(String text, double height, int index, int pieces) {
        List<Stroke> strokes = laidOut(text, height);
        if (strokes.isEmpty() || pieces <= 0) {
            return null;
        }
        int count = strokes.size();
        int stroke = Math.min(count - 1, index * count / pieces);
        // How many pieces landed on this same stroke, and which of them this is.
        int first = (int) Math.ceil((double) stroke * pieces / count);
        int next = (int) Math.ceil((double) (stroke + 1) * pieces / count);
        int share = Math.max(1, next - first);
        int slot = Math.clamp(index - first, 0, share - 1);

        Stroke line = strokes.get(stroke);
        double dx = line.x2() - line.x1();
        double dy = line.y2() - line.y1();
        double length = Math.hypot(dx, dy);
        double at = (slot + 0.5) / share;
        return new Placement(
                line.x1() + dx * at,
                line.y1() + dy * at,
                Math.atan2(dy, dx),
                // A touch of overlap, so segments meet rather than nearly meet.
                length / share * 1.08,
                height * THICKNESS);
    }
}
