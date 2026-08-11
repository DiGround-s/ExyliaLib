package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.EffectConfig;
import org.bukkit.entity.Player;

/**
 * Plays an effect that was written in a config file.
 *
 * <p>The bridge from YAML to screen. Each section is optional and independent,
 * so an owner who wants only a sound gets only a sound, and one who wants a
 * title, a sound and fireworks gets all three from the same block.
 *
 * <p>Where several parts stay on screen, the boss bar is returned as the handle:
 * it is the one an owner is most likely to want to stop early, and returning
 * several would make the common case awkward for the sake of the rare one.
 */
final class ConfigPlayer {

    private ConfigPlayer() {
    }

    static Display play(EffectConfig effect, Player viewer) {
        if (effect == null || viewer == null) {
            return null;
        }

        Display handle = null;

        EffectConfig.BossBar bossBar = effect.bossBar();
        if (bossBar != null && !bossBar.text().isEmpty()) {
            handle = bossBar(bossBar).show(viewer);
        }

        EffectConfig.Title title = effect.title();
        if (title != null && (!title.text().isEmpty() || !title.subtitle().isEmpty())) {
            Display shown = title(title).show(viewer);
            if (handle == null) {
                handle = shown;
            }
        }

        EffectConfig.ActionBar actionBar = effect.actionBar();
        if (actionBar != null && !actionBar.text().isEmpty()) {
            Display shown = actionBar(actionBar).show(viewer);
            if (handle == null) {
                handle = shown;
            }
        }

        EffectConfig.Sound sound = effect.sound();
        if (sound != null && !sound.name().isEmpty()) {
            new SoundBuilder(sound.name())
                    .volume(sound.volume() > 0 ? sound.volume() : 1)
                    .pitch(sound.pitch() > 0 ? sound.pitch() : 1)
                    .category(sound.category())
                    .show(viewer);
        }

        EffectConfig.Particle particle = effect.particle();
        if (particle != null && !particle.name().isEmpty()) {
            new ParticleBuilder(particle.name())
                    .count(particle.count() > 0 ? particle.count() : 1)
                    .spread(particle.spread())
                    .speed(particle.speed())
                    .at(viewer.getLocation())
                    .show(viewer);
        }

        EffectConfig.Firework firework = effect.firework();
        if (firework != null && !firework.colours().isEmpty()) {
            FireworkBuilder builder = new FireworkBuilder().at(viewer.getLocation());
            firework.colours().forEach(builder::colour);
            firework.fades().forEach(builder::fade);
            builder.shape(firework.shape());
            if (firework.flicker()) {
                builder.flicker();
            }
            if (firework.trail()) {
                builder.trail();
            }
            builder.launch();
        }

        return handle;
    }

    private static TitleBuilder title(EffectConfig.Title config) {
        TitleBuilder builder = new TitleBuilder(config.text())
                .subtitle(config.subtitle())
                .timeStyle(config.timeStyle());

        if (config.countdown() > 0) {
            builder.countdown(config.countdown());
        } else if (config.stay() <= 0) {
            // A stay of zero is how an owner asks for a title that does not go
            // away by itself.
            builder.permanent();
        } else {
            builder.times(config.fadeIn(), config.stay(), config.fadeOut());
        }
        return builder;
    }

    private static ActionBarBuilder actionBar(EffectConfig.ActionBar config) {
        ActionBarBuilder builder = new ActionBarBuilder(config.text())
                .timeStyle(config.timeStyle());

        if (config.countdown() > 0) {
            builder.countdown(config.countdown());
        } else if (config.duration() > 0) {
            builder.duration(config.duration());
        } else {
            builder.permanent();
        }
        return builder;
    }

    private static BossBarBuilder bossBar(EffectConfig.BossBar config) {
        BossBarBuilder builder = new BossBarBuilder(config.text())
                .colour(config.colour())
                .overlay(config.overlay())
                .timeStyle(config.timeStyle());

        if (config.countdown() > 0) {
            builder.countdown(config.countdown());
        } else if (config.countUp() > 0) {
            builder.countUp(config.countUp());
        } else if (config.progress() > 0) {
            builder.progress((float) config.progress());
        }
        return builder;
    }
}
