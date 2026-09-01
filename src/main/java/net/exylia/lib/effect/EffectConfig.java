package net.exylia.lib.effect;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Sparse;

import java.util.List;

/**
 * An effect declared in a config file.
 *
 * <p>This is the point of the module. A plugin should not decide what a win
 * looks like: it should say "the player won" and let the server owner decide
 * whether that is a title, a sound, fireworks, or all three.
 *
 * <pre>{@code
 * Effects.play(config.get().onWin(), player);
 * }</pre>
 *
 * <p>Written in YAML, that same effect is:
 *
 * <pre>
 * on-win:
 *   title:
 *     text: '{primary}VICTORY'
 *     subtitle: '{letters}Well played, %player_name%'
 *     fade-in: 0.5
 *     stay: 3.0
 *     fade-out: 0.5
 *   sound:
 *     name: ENTITY_PLAYER_LEVELUP
 *     volume: 0.8
 *     pitch: 1.2
 *   firework:
 *     colours: ['#8a51c4', '#ff6b9d']
 *     shape: BALL_LARGE
 * </pre>
 *
 * <p>Every part is optional. A section that is left out simply does not play, so
 * an owner who wants only a sound writes only the sound.
 *
 * <p>Being a record, this is read once at load and then only field access, and
 * it nests inside a plugin's own config record like any other section.
 *
 * <h2>What is not written here</h2>
 * How long a timer runs is the plugin's, not the file's. A countdown is always
 * the same number something else is already counting — the match, the warmup,
 * the combat tag — so a key that could set it to anything else could only ever
 * disagree with what is happening on the server. A plugin that counts down
 * builds the display itself, {@code Effects.title(text).countdown(seconds)},
 * with the seconds it knows. What the owner keeps is how it looks: the text,
 * the fades, and {@code time-style} for how {@code %time%} is written.
 *
 * @param title     the title to show, or {@code null}
 * @param actionBar the action bar to show, or {@code null}
 * @param bossBar   the boss bar to show, or {@code null}
 * @param sound     the sound to play, or {@code null}
 * @param particle  the particles to draw, or {@code null}
 * @param firework  the firework to set off, or {@code null}
 * @since 1.4.0
 */
public record EffectConfig(
        @Comment("Big text in the middle of the screen. Remove to show nothing.")
        Title title,

        @Comment("Text above the hotbar.")
        ActionBar actionBar,

        @Comment("Bar at the top of the screen.")
        BossBar bossBar,

        @Comment("A sound to play.")
        Sound sound,

        @Comment("Particles to draw.")
        Particle particle,

        @Comment("A firework to set off.")
        Firework firework) {

    /**
     * An effect that does nothing, which is what an unwritten section means.
     *
     * <p>Required by the config module, and useful on its own: a plugin can
     * hand this to {@link Effects#play} and nothing happens, instead of every
     * caller checking for null.
     */
    public EffectConfig() {
        this(new Title(), new ActionBar(), new BossBar(), new Sound(), new Particle(), new Firework());
    }

    /**
     * A title, as written in config.
     *
     * @param text     the big line
     * @param subtitle the smaller line below it
     * @param fadeIn   seconds to fade in
     * @param stay     seconds fully visible; 0 keeps it up until stopped
     * @param fadeOut  seconds to fade out
     * @param timeStyle how %time% is written
     */
    public record Title(
            @Comment("Supports colours like {primary} and placeholders like %player_name%.")
            String text,

            String subtitle,

            double fadeIn,

            @Comment("Seconds fully visible. 0 keeps it up until something stops it.")
            double stay,

            double fadeOut,

            @Comment("auto, seconds, tenths, hundredths, clock or full.")
            String timeStyle) implements Sparse {

        /** An empty title, which shows nothing. */
        public Title() {
            // No fade in: a title that has to appear is nearly always one
            // reacting to something that just happened, and half a second of
            // it fading up reads as lag rather than as polish.
            this("", "", 0, 3.0, 1.0, "auto");
        }

        /** Returns whether neither line says anything. */
        @Override
        public boolean isEmpty() {
            return text.isEmpty() && subtitle.isEmpty();
        }

        /** Defaults matching what a plain title looks like. */
        public Title {
            if (text == null) {
                text = "";
            }
            if (subtitle == null) {
                subtitle = "";
            }
            if (timeStyle == null) {
                timeStyle = "auto";
            }
        }
    }

    /**
     * An action bar, as written in config.
     *
     * @param text      the text
     * @param duration  seconds to show it; 0 keeps it up until stopped
     * @param timeStyle how %time% is written
     */
    public record ActionBar(
            @Comment("Supports colours and placeholders.")
            String text,

            @Comment("Seconds to show it. 0 keeps it up until something stops it.")
            double duration,

            @Comment("auto, seconds, tenths, hundredths, clock or full.")
            String timeStyle) implements Sparse {

        /** An empty action bar, which shows nothing. */
        public ActionBar() {
            this("", 3.0, "auto");
        }

        /** Returns whether there is nothing to show. */
        @Override
        public boolean isEmpty() {
            return text.isEmpty();
        }

        public ActionBar {
            if (text == null) {
                text = "";
            }
            if (timeStyle == null) {
                timeStyle = "auto";
            }
        }
    }

    /**
     * A boss bar, as written in config.
     *
     * @param text      the bar title
     * @param colour    the bar colour
     * @param overlay   whether the bar is segmented
     * @param countUp   seconds to count up towards, filling the bar
     * @param progress  a fixed fill from 0 to 1, when not timed
     * @param timeStyle how %time% is written
     */
    public record BossBar(
            @Comment("Supports colours and placeholders.")
            String text,

            @Comment("PINK, BLUE, RED, GREEN, YELLOW, PURPLE or WHITE.")
            String colour,

            @Comment("PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12 or NOTCHED_20.")
            String overlay,

            @Comment("Counts up towards this many seconds, filling the bar.")
            double countUp,

            @Comment("Fill from 0.0 to 1.0, used when the bar is not counting.")
            double progress,

            @Comment("auto, seconds, tenths, hundredths, clock or full.")
            String timeStyle) implements Sparse {

        /** An empty boss bar, which shows nothing. */
        public BossBar() {
            this("", "PURPLE", "PROGRESS", 0, 1.0, "auto");
        }

        /** Returns whether the bar has no title, and so is never shown. */
        @Override
        public boolean isEmpty() {
            return text.isEmpty();
        }

        public BossBar {
            if (text == null) {
                text = "";
            }
            if (colour == null) {
                colour = "PURPLE";
            }
            if (overlay == null) {
                overlay = "PROGRESS";
            }
            if (timeStyle == null) {
                timeStyle = "auto";
            }
        }
    }

    /**
     * A sound, as written in config.
     *
     * @param name     the sound name or key
     * @param volume   how loud, where above 1 carries further
     * @param pitch    from 0.5 to 2
     * @param category which volume slider controls it
     */
    public record Sound(
            @Comment("ENTITY_PLAYER_LEVELUP, or a key such as minecraft:entity.player.levelup.")
            String name,

            @Comment("Above 1.0 does not get louder, it carries further.")
            double volume,

            @Comment("From 0.5 to 2.0.")
            double pitch,

            @Comment("MASTER, MUSIC, RECORDS, WEATHER, BLOCKS, HOSTILE, NEUTRAL, PLAYERS, AMBIENT or VOICE.")
            String category) implements Sparse {

        /** No sound. */
        public Sound() {
            this("", 1.0, 1.0, "MASTER");
        }

        /** Returns whether no sound was named. */
        @Override
        public boolean isEmpty() {
            return name.isEmpty();
        }

        public Sound {
            if (name == null) {
                name = "";
            }
            if (category == null) {
                category = "MASTER";
            }
        }
    }

    /**
     * Particles, as written in config.
     *
     * @param name   the particle name or key
     * @param count  how many to draw
     * @param spread how far they scatter, in blocks
     * @param speed  how fast they move
     */
    public record Particle(
            @Comment("FLAME, or a key such as minecraft:flame.")
            String name,

            int count,

            @Comment("How far they scatter from the centre, in blocks.")
            double spread,

            @Comment("How fast they move, which means something different per particle.")
            double speed) implements Sparse {

        /** No particles. */
        public Particle() {
            this("", 1, 0, 0);
        }

        /** Returns whether no particle was named. */
        @Override
        public boolean isEmpty() {
            return name.isEmpty();
        }

        public Particle {
            if (name == null) {
                name = "";
            }
        }
    }

    /**
     * A firework, as written in config.
     *
     * @param colours the explosion colours
     * @param fades   the colours it fades into
     * @param shape   the explosion shape
     * @param flicker whether it twinkles
     * @param trail   whether it leaves a trail
     */
    public record Firework(
            @Comment("Explosion colours, as hex values such as '#8a51c4'.")
            List<String> colours,

            @Comment("Colours the explosion fades into, as hex values.")
            List<String> fades,

            @Comment("BALL, BALL_LARGE, STAR, BURST or CREEPER.")
            String shape,

            boolean flicker,

            boolean trail) implements Sparse {

        /** No firework. */
        public Firework() {
            this(List.of(), List.of(), "BALL", false, false);
        }

        /** Returns whether there is no explosion to colour. */
        @Override
        public boolean isEmpty() {
            return colours.isEmpty();
        }

        public Firework {
            if (colours == null) {
                colours = List.of();
            }
            if (fades == null) {
                fades = List.of();
            }
            if (shape == null) {
                shape = "BALL";
            }
        }
    }
}
