package net.exylia.lib.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says that a number is a length of time, and in which unit it was written.
 *
 * <p>A component marked with this still reads the number it always did, and
 * also reads the notation every other Exylia field accepts:
 *
 * <pre>{@code
 * record Settings(@Time int timeoutSeconds) { }
 *
 * timeout-seconds: 300      // five minutes, as before
 * timeout-seconds: 5m       // the same five minutes
 * timeout-seconds: 1m30s    // ninety seconds
 * }</pre>
 *
 * <h2>The bare number never changes meaning</h2>
 * {@link #value()} is what a number with no unit on it means, and it is the
 * unit the key has always been written in — seconds for a {@code -seconds} key,
 * ticks for a menu's {@code interval}, milliseconds for a cache TTL. That is
 * the whole reason the unit is declared rather than assumed: a file that says
 * {@code interval: 20} must keep meaning twenty ticks, not twenty seconds.
 *
 * <p>The value handed to the record is in that same unit, so nothing downstream
 * changes either: {@code 5m} on a seconds field arrives as {@code 300}.
 *
 * <p>A component declared as a {@link java.time.Duration} needs no annotation:
 * it is a length of time by its type, its bare number is seconds, and it is
 * written back as {@code 1m30s}. That is the better shape for anything new.
 *
 * @since 1.170.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Time {

    /**
     * What a number with no unit on it means, and the unit the value is handed
     * over in.
     *
     * @return the unit; seconds unless said otherwise
     */
    Unit value() default Unit.SECONDS;

    /** The units a configured length of time is written in. */
    enum Unit {

        /** Milliseconds, for a cache TTL or a lease. */
        MILLIS(1L),

        /** Server ticks, fiftieths of a second. */
        TICKS(50L),

        /** Seconds, which is what most of the library's files are written in. */
        SECONDS(1_000L),

        /** Minutes, for an interval nobody would write in seconds. */
        MINUTES(60_000L),

        /** Hours. */
        HOURS(3_600_000L),

        /** Days, for a retention window. */
        DAYS(86_400_000L);

        private final long millis;

        Unit(long millis) {
            this.millis = millis;
        }

        /** How many milliseconds one of these is. */
        public long millis() {
            return millis;
        }
    }
}
