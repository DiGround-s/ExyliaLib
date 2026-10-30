package net.exylia.lib.spatial.crate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * The reel's maths, kept free of the server so it is tested without one: which face stands where, how
 * far the reel has run at any moment, and how large each face is drawn as it slides past the marker.
 *
 * <p>Positions are in slots from the marker, positive to the right; the reel runs leftwards. It starts
 * with face {@code half} on the marker (the window full on both sides) and stops with face
 * {@link #landing} there: the prize.
 */
final class ReelPath {

    /** How often a face's pose is worked out while it is in the window. */
    static final long STEP_MILLIS = 50L;
    /** Past the window's edge a face shrinks away over this many slots. */
    static final double FADE = 0.5;
    /** The edge faces' size, as a share of the marker's. */
    static final double EDGE = 0.55;

    private final int length;
    private final int slots;
    private final long millis;

    /**
     * @param faces  how many faces go past before the prize
     * @param slots  how many show at once, odd
     * @param millis how long it runs
     */
    ReelPath(int faces, int slots, long millis) {
        this.slots = slots;
        this.length = faces + slots;
        this.millis = millis;
    }

    int length() {
        return length;
    }

    long millis() {
        return millis;
    }

    /** Half the window, in slots: the marker and this many either side. */
    int half() {
        return slots / 2;
    }

    /** The strip index the prize sits at: the window stays full to its right when it stops. */
    int landing() {
        return length - 1 - half();
    }

    /**
     * The strip: {@code pick} answers a face for every index; the prize at {@link #landing} and, when
     * {@code nearMiss} is not negative, that face right after it, so the best rarity is seen going past
     * the line. Presentation only: the prize was drawn before this was built.
     */
    List<Integer> strip(IntUnaryOperator pick, int prize, int nearMiss) {
        List<Integer> strip = new ArrayList<>(length);
        for (int i = 0; i < length; i++) strip.add(pick.applyAsInt(i));
        strip.set(landing(), prize);
        if (nearMiss >= 0 && landing() + 1 < length) strip.set(landing() + 1, nearMiss);
        return strip;
    }

    /** How far the reel has run, in faces, at {@code at} milliseconds: fast at first, easing to a stop. */
    double offset(long at) {
        double u = Math.clamp(at / (double) millis, 0, 1);
        double eased = 1 - Math.pow(1 - u, 3);
        return half() + (landing() - half()) * eased;
    }

    /** The face on the marker at {@code at}. */
    int onMarker(long at) {
        return (int) Math.round(offset(at));
    }

    /** Where face {@code index} is at {@code at}, in slots from the marker. */
    double position(int index, long at) {
        return index - offset(at);
    }

    /** How large a face is drawn at {@code position}, as a share of the marker's; 0 off the reel. */
    double size(double position) {
        double away = Math.abs(position);
        double half = half() + 0.5;
        if (away <= half) return EDGE + (1 - EDGE) * Math.cos(Math.PI / 2 * away / half);
        if (away >= half + FADE) return 0;
        return EDGE * (1 - (away - half) / FADE);
    }

    /** Whether face {@code index} is on the reel, or shrinking off it, at {@code at}. */
    boolean shown(int index, long at) {
        return size(position(index, at)) > 0;
    }
}
