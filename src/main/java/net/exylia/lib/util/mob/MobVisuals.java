package net.exylia.lib.util.mob;

/**
 * How much a plugin's mobs draw, server-wide: the switch for their reactions,
 * how far their floating numbers are seen, how hard the screen shakes and how
 * many large effects may play at once.
 *
 * <pre>{@code
 * mobs.visuals(new MobVisuals(24, 24, true, 1.0));
 * // A quiet server: no entrances, hits or deaths drawn, no shaking.
 * mobs.visuals(MobVisuals.DEFAULT.withReactions(false).withShake(0));
 * }</pre>
 *
 * <p>Only what is seen changes. With reactions off a mob still spawns, takes
 * damage, dies and drops exactly as it did; past {@code maxCasts} a new
 * entrance or death simply is not drawn. Gameplay never waits on a visual.
 *
 * @param indicatorRange how far away damage, healing and hits-left numbers are
 *                       seen, in blocks; 24 by default, 0 hides them
 * @param maxCasts       the most large effects &mdash; entrances and deaths, and
 *                       later skill styles &mdash; this plugin's mobs play at once;
 *                       further ones are skipped, never queued
 * @param reactions      whether mobs react at all: entrances, hits, healing,
 *                       deaths, the low-health look and the numbers
 * @param shake          how much of each screen shake plays: {@code 1} as
 *                       designed, {@code 0} none, {@code 2} twice the beats.
 *                       The tilt itself is each player's own damage-tilt
 *                       setting, so strength is how many beats come
 * @since 1.199.0
 */
public record MobVisuals(double indicatorRange, int maxCasts, boolean reactions, double shake) {

    /** 24 blocks of numbers, 24 large effects at once, reactions on, shake as designed. */
    public static final MobVisuals DEFAULT = new MobVisuals(24, 24, true, 1.0);

    /** The farthest numbers are seen from, whatever is asked. */
    public static final double MAX_INDICATOR_RANGE = 64;

    /** The most a shake is multiplied by. */
    public static final double MAX_SHAKE = 3;

    public MobVisuals {
        indicatorRange = Double.isFinite(indicatorRange) ? Math.clamp(indicatorRange, 0, MAX_INDICATOR_RANGE) : 24;
        maxCasts = Math.max(0, maxCasts);
        shake = Double.isFinite(shake) ? Math.clamp(shake, 0, MAX_SHAKE) : 1;
    }

    public MobVisuals withIndicatorRange(double indicatorRange) {
        return new MobVisuals(indicatorRange, maxCasts, reactions, shake);
    }

    public MobVisuals withMaxCasts(int maxCasts) {
        return new MobVisuals(indicatorRange, maxCasts, reactions, shake);
    }

    public MobVisuals withReactions(boolean reactions) {
        return new MobVisuals(indicatorRange, maxCasts, reactions, shake);
    }

    public MobVisuals withShake(double shake) {
        return new MobVisuals(indicatorRange, maxCasts, reactions, shake);
    }

    /**
     * How many beats a shake designed with {@code designed} beats plays here.
     *
     * @param designed the beats the effect was drawn with
     * @return the beats to play; {@code 0} plays no shake
     */
    public int shakes(int designed) {
        return (int) Math.round(Math.max(0, designed) * shake);
    }
}
