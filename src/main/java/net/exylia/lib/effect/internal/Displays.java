package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.Timer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * The concrete effects that stay on screen.
 *
 * <p>Grouped in one file because each is only a few lines: all the shared
 * behaviour, including ticking and cleanup, lives in {@link ActiveDisplay}.
 */
final class Displays {

    private Displays() {
    }

    /**
     * A title, optionally driven by a timer.
     *
     * <p>The first draw carries the fade timings; later ones do not, or the
     * title would restart its fade on every redraw and pulse.
     */
    static final class TitleDisplay extends ActiveDisplay {

        private final Rendered subtitle;
        private final int fadeIn;
        private final int stay;
        private final int fadeOut;

        TitleDisplay(Player viewer, Rendered title, Rendered subtitle, Timer timer,
                     long period, int fadeIn, int stay, int fadeOut, String owner) {
            super(viewer, title, timer, period, owner);
            this.subtitle = subtitle;
            this.fadeIn = fadeIn;
            this.stay = stay;
            this.fadeOut = fadeOut;
        }

        @Override
        void draw(Player viewer, Rendered rendered, Timer timer) {
            Bars.title(viewer, rendered.build(viewer, timer), subtitle.build(viewer, timer),
                    fadeIn, stay, fadeOut);
        }

        @Override
        void redraw(Player viewer, Rendered rendered, Timer timer) {
            Bars.titleText(viewer, rendered.build(viewer, timer), subtitle.build(viewer, timer));
        }

        @Override
        void clear(Player viewer) {
            Bars.clearTitle(viewer);
        }

        @Override
        boolean repeats() {
            // A title expires after "stay" ticks, so a permanent one has to be
            // re-sent to stay on screen.
            return stay <= 0;
        }

        @Override
        boolean exclusive() {
            return true;
        }
    }

    /**
     * An action bar.
     *
     * <p>Always ticks, because the client fades an action bar out after about
     * three seconds whether or not the text changed.
     */
    static final class ActionBarDisplay extends ActiveDisplay {

        /**
         * How long an unchanged bar may go without being re-sent.
         *
         * <p>The client fades an action bar out after about three seconds,
         * so forty ticks keeps it on screen with no gap and is the same
         * keepalive a static bar already uses.
         */
        private static final long KEEPALIVE_TICKS = 40;

        private final long period;
        private Component lastSent;
        private long ticksSinceSent;

        ActionBarDisplay(Player viewer, Rendered text, Timer timer, long period, String owner) {
            super(viewer, text, timer, period, owner);
            this.period = Math.max(1, period);
        }

        @Override
        void draw(Player viewer, Rendered rendered, Timer timer) {
            send(viewer, rendered.build(viewer, timer));
        }

        @Override
        void redraw(Player viewer, Rendered rendered, Timer timer) {
            // A countdown ticks every tick so a decimal moves smoothly, but
            // most of those ticks the text reads exactly as it did: the same
            // component comes back, and the client already shows it.
            Component built = rendered.build(viewer, timer);
            ticksSinceSent += period;
            if (built == lastSent && ticksSinceSent < KEEPALIVE_TICKS) {
                return;
            }
            send(viewer, built);
        }

        private void send(Player viewer, Component built) {
            Bars.actionBar(viewer, built);
            lastSent = built;
            ticksSinceSent = 0;
        }

        @Override
        void clear(Player viewer) {
            Bars.clearActionBar(viewer);
        }

        @Override
        boolean repeats() {
            return true;
        }

        @Override
        boolean exclusive() {
            return true;
        }
    }

    /**
     * A boss bar.
     *
     * <p>Its progress follows the timer, so a countdown empties the bar and a
     * count-up towards a total fills it.
     */
    static final class BossBarDisplay extends ActiveDisplay {

        /**
         * How many steps the client can actually draw the bar in.
         *
         * <p>The vanilla boss bar texture is 182 pixels wide, so progress finer
         * than one part in 182 is a packet the player cannot see.
         */
        private static final int BAR_STEPS = 182;

        private final UUID id = UUID.randomUUID();

        private final String colour;
        private final String overlay;
        private Float fixedProgress;

        private Component lastTitle;
        /**
         * The progress last sent, quantised.
         *
         * <p>The client draws the bar about two hundred pixels wide, so a
         * sixty-second countdown moves it by a twentieth of a pixel per tick.
         * Sending that is a packet per player per tick that changes nothing
         * anybody can see; sending it when the drawn width actually changes is
         * the same bar for a fraction of the traffic.
         */
        private int lastSteps = Integer.MIN_VALUE;

        BossBarDisplay(Player viewer, Rendered text, Timer timer, long period,
                       String colour, String overlay, Float fixedProgress, String owner) {
            super(viewer, text, timer, period, owner);
            this.colour = colour;
            this.overlay = overlay;
            this.fixedProgress = fixedProgress;
        }

        private float progress(Timer timer) {
            if (fixedProgress != null) {
                return fixedProgress;
            }
            return timer != null ? timer.progress() : 1f;
        }

        /** The progress as the client will draw it, in whole steps of the bar. */
        private static int steps(float progress) {
            return Math.round(Math.clamp(progress, 0f, 1f) * BAR_STEPS);
        }

        @Override
        void draw(Player viewer, Rendered rendered, Timer timer) {
            Component title = rendered.build(viewer, timer);
            lastTitle = title;
            float progress = progress(timer);
            lastSteps = steps(progress);
            Bars.bossBarAdd(viewer, id, title, progress, colour, overlay);
        }

        @Override
        void redraw(Player viewer, Rendered rendered, Timer timer) {
            Component title = rendered.build(viewer, timer);
            // The title packet is only worth sending when the text really
            // changed: a bar whose progress moves but whose text does not costs
            // one small packet instead of two.
            boolean changed = !title.equals(lastTitle);
            lastTitle = title;

            float progress = progress(timer);
            int steps = steps(progress);
            if (steps == lastSteps) {
                // Nothing moved on screen. Without a new title there is nothing
                // to send at all, which is the whole redraw for a bar that ticks
                // faster than it changes.
                if (!changed) {
                    return;
                }
                Bars.bossBarTitleOnly(viewer, id, title);
                return;
            }
            lastSteps = steps;
            Bars.bossBarUpdate(viewer, id, title, progress, changed);
        }

        @Override
        void clear(Player viewer) {
            Bars.bossBarRemove(viewer, id);
            Bars.forget(id);
        }

        @Override
        public @NotNull Display progress(float progress) {
            this.fixedProgress = Math.clamp(progress, 0f, 1f);
            // Asked for explicitly, so it is sent whatever it rounds to.
            this.lastSteps = Integer.MIN_VALUE;
            rerender();
            return this;
        }
    }
}
