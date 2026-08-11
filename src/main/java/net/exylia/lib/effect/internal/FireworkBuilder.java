package net.exylia.lib.effect.internal;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a firework.
 *
 * <pre>{@code
 * Effects.firework().colour("#8a51c4").shape("BALL_LARGE").instant().at(location).launch();
 * }</pre>
 *
 * <p>The one effect here that is not a pure packet. A firework explosion is
 * driven by an entity, and while the explosion itself can be sent as a packet,
 * doing so across every supported version is more fragile than it is worth. What
 * this does instead is spawn a firework that detonates immediately and is gone
 * in the same tick, so nothing is left ticking afterwards.
 *
 * @since 1.4.0
 */
public final class FireworkBuilder {

    private Location location;
    private final List<Color> colours = new ArrayList<>(2);
    private final List<Color> fades = new ArrayList<>(2);
    private String shape = "BALL";
    private boolean flicker;
    private boolean trail;
    private boolean instant = true;
    private int power = 1;

    /**
     * Sets where the firework goes off.
     *
     * @param location the position
     * @return this builder
     */
    public @NotNull FireworkBuilder at(@NotNull Location location) {
        this.location = location;
        return this;
    }

    /**
     * Adds a colour, written as a hex value.
     *
     * @param hex a colour such as {@code #8a51c4}
     * @return this builder
     */
    public @NotNull FireworkBuilder colour(@NotNull String hex) {
        Color parsed = parse(hex);
        if (parsed != null) {
            colours.add(parsed);
        }
        return this;
    }

    /**
     * Adds a colour the explosion fades into.
     *
     * @param hex a colour such as {@code #aa76de}
     * @return this builder
     */
    public @NotNull FireworkBuilder fade(@NotNull String hex) {
        Color parsed = parse(hex);
        if (parsed != null) {
            fades.add(parsed);
        }
        return this;
    }

    /**
     * Sets the explosion shape.
     *
     * @param shape one of {@code BALL}, {@code BALL_LARGE}, {@code STAR},
     *              {@code BURST} or {@code CREEPER}
     * @return this builder
     */
    public @NotNull FireworkBuilder shape(@NotNull String shape) {
        this.shape = shape;
        return this;
    }

    /**
     * Adds the twinkling effect.
     *
     * @return this builder
     */
    public @NotNull FireworkBuilder flicker() {
        this.flicker = true;
        return this;
    }

    /**
     * Adds the trail of sparks.
     *
     * @return this builder
     */
    public @NotNull FireworkBuilder trail() {
        this.trail = true;
        return this;
    }

    /**
     * Lets the firework fly before exploding, instead of going off at once.
     *
     * @param power how high it climbs, roughly half a second per point
     * @return this builder
     */
    public @NotNull FireworkBuilder rise(int power) {
        this.instant = false;
        this.power = Math.clamp(power, 0, 3);
        return this;
    }

    /**
     * Makes the firework explode immediately, which is the default.
     *
     * @return this builder
     */
    public @NotNull FireworkBuilder instant() {
        this.instant = true;
        return this;
    }

    /**
     * Sets the firework off.
     *
     * @return whether it could be spawned
     */
    public boolean launch() {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        FireworkEffect.Builder effect = FireworkEffect.builder()
                .with(type())
                .flicker(flicker)
                .trail(trail);

        // A firework with no colour is invisible, so one is supplied rather than
        // letting a config typo produce nothing at all.
        effect.withColor(colours.isEmpty() ? List.of(Color.WHITE) : colours);
        if (!fades.isEmpty()) {
            effect.withFade(fades);
        }

        Firework firework = location.getWorld().spawn(location, Firework.class);
        var meta = firework.getFireworkMeta();
        meta.addEffect(effect.build());
        meta.setPower(instant ? 0 : power);
        firework.setFireworkMeta(meta);

        if (instant) {
            // Detonating in the same tick means the entity never survives to be
            // ticked, which is the closest this gets to being stateless.
            firework.detonate();
        }
        return true;
    }

    /**
     * Sets the firework off where a player is standing.
     *
     * @param viewer whose position to use
     * @return whether it could be spawned
     */
    public boolean launchAt(@NotNull Player viewer) {
        this.location = viewer.getLocation();
        return launch();
    }

    private FireworkEffect.Type type() {
        try {
            return FireworkEffect.Type.valueOf(shape.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return FireworkEffect.Type.BALL;
        }
    }

    /** Accepts {@code #rrggbb} and bare {@code rrggbb}. */
    private static Color parse(String hex) {
        String trimmed = hex.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        try {
            return Color.fromRGB(Integer.parseInt(trimmed, 16));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
