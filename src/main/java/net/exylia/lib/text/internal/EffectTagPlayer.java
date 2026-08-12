package net.exylia.lib.text.internal;

import net.exylia.lib.effect.Effects;
import org.bukkit.entity.Player;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plays what a message's effect tag asked for.
 *
 * <p>The parsing lives in {@link EffectTag} and the playing lives in the
 * effect module; this only joins them. Nothing here knows how to make a
 * sound — it asks {@code Effects}, the same as any plugin would.
 */
public final class EffectTagPlayer {

    private static volatile Logger logger = Logger.getLogger("ExyliaLib");

    private EffectTagPlayer() {
        throw new AssertionError("No instances.");
    }

    /** Where malformed entries are reported. */
    public static void logger(Logger replacement) {
        logger = replacement;
    }

    /**
     * Plays every effect in a parsed message for one player.
     *
     * <p>A malformed entry is reported once and skipped: the message is the
     * point, and losing it because a sound name has a typo would be the
     * wrong trade.
     *
     * @param parsed what the message asked for
     * @param viewer who to play it for
     */
    public static void play(EffectTag.Parsed parsed, Player viewer) {
        for (String entry : parsed.sounds()) {
            try {
                playSound(entry, viewer);
            } catch (Throwable failure) {
                report("sound", entry, failure);
            }
        }
        for (String entry : parsed.particles()) {
            try {
                playParticle(entry, viewer);
            } catch (Throwable failure) {
                report("particle", entry, failure);
            }
        }
        for (String entry : parsed.fireworks()) {
            try {
                playFirework(entry, viewer);
            } catch (Throwable failure) {
                report("firework", entry, failure);
            }
        }
    }

    /** {@code NAME|volume|pitch} */
    private static void playSound(String entry, Player viewer) {
        String[] parts = EffectTag.arguments(entry);
        Effects.sound(parts[0])
                .volume(EffectTag.number(parts, 1, 1.0))
                .pitch(EffectTag.number(parts, 2, 1.0))
                .show(viewer);
    }

    /** {@code NAME|count|offsetX|offsetY|offsetZ|speed} */
    private static void playParticle(String entry, Player viewer) {
        String[] parts = EffectTag.arguments(entry);
        var particle = Effects.particle(parts[0])
                .count((int) EffectTag.number(parts, 1, 1));

        if (parts.length >= 5) {
            particle.spread(EffectTag.number(parts, 2, 0),
                    EffectTag.number(parts, 3, 0),
                    EffectTag.number(parts, 4, 0));
        }
        if (parts.length >= 6) {
            particle.speed(EffectTag.number(parts, 5, 0));
        }
        particle.show(viewer);
    }

    /** {@code SHAPE|colour|fade|flicker|trail|power} */
    private static void playFirework(String entry, Player viewer) {
        String[] parts = EffectTag.arguments(entry);
        var firework = Effects.firework();

        if (!parts[0].isEmpty()) {
            firework.shape(parts[0]);
        }
        if (parts.length >= 2 && !parts[1].isEmpty()) {
            firework.colour(parts[1]);
        }
        if (parts.length >= 3 && !parts[2].isEmpty()) {
            firework.fade(parts[2]);
        }
        if (parts.length >= 4 && isTrue(parts[3])) {
            firework.flicker();
        }
        if (parts.length >= 5 && isTrue(parts[4])) {
            firework.trail();
        }
        if (parts.length >= 6) {
            firework.rise((int) EffectTag.number(parts, 5, 0));
        }
        // At the player who received the message, which is the only place a
        // message-driven firework could sensibly go off.
        firework.launchAt(viewer);
    }

    private static boolean isTrue(String value) {
        return value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes")
                || value.equals("1");
    }

    private static void report(String kind, String entry, Throwable failure) {
        logger.log(Level.WARNING,
                "Ignoring a malformed " + kind + " in a message: '" + entry + "'",
                failure);
    }
}
