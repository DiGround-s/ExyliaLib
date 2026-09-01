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

        ActionBarDisplay(Player viewer, Rendered text, Timer timer, long period, String owner) {
            super(viewer, text, timer, period, owner);
        }

        @Override
        void draw(Player viewer, Rendered rendered, Timer timer) {
            Bars.actionBar(viewer, rendered.build(viewer, timer));
        }

        @Override
        void redraw(Player viewer, Rendered rendered, Timer timer) {
            Bars.actionBar(viewer, rendered.build(viewer, timer));
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

        private final UUID id = UUID.randomUUID();

        private final String colour;
        private final String overlay;
        private Float fixedProgress;

        private Component lastTitle;

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

        @Override
        void draw(Player viewer, Rendered rendered, Timer timer) {
            Component title = rendered.build(viewer, timer);
            lastTitle = title;
            Bars.bossBarAdd(viewer, id, title, progress(timer), colour, overlay);
        }

        @Override
        void redraw(Player viewer, Rendered rendered, Timer timer) {
            Component title = rendered.build(viewer, timer);
            // The title packet is only worth sending when the text really
            // changed: a bar whose progress moves but whose text does not costs
            // one small packet instead of two.
            boolean changed = !title.equals(lastTitle);
            lastTitle = title;
            Bars.bossBarUpdate(viewer, id, title, progress(timer), changed);
        }

        @Override
        void clear(Player viewer) {
            Bars.bossBarRemove(viewer, id);
            Bars.forget(id);
        }

        @Override
        public @NotNull Display progress(float progress) {
            this.fixedProgress = Math.clamp(progress, 0f, 1f);
            rerender();
            return this;
        }
    }
}
