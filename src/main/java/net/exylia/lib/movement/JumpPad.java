package net.exylia.lib.movement;

import net.exylia.lib.effect.EffectConfig;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One jump pad: what sets it off and how it throws the player.
 *
 * @param id       the plugin's name for it, unique within one {@link JumpPads}
 * @param trigger  the block or the materials that launch
 * @param power    horizontal speed
 * @param vertical upward speed
 * @param yaw      the fixed direction, in Minecraft yaw degrees (0 south, 90 west,
 *                 180 north, -90 east); {@code null} throws the player the way they look
 * @param effect   what a launch looks and sounds like, to the player launched
 * @since 1.559.0
 */
public record JumpPad(@NotNull String id, @NotNull PadTrigger trigger, double power, double vertical,
                      @Nullable Float yaw, @NotNull EffectConfig effect) {

    public JumpPad {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(effect, "effect");
    }

    /** A pad that throws the player the way they look, with no effect. */
    public static @NotNull JumpPad of(@NotNull String id, @NotNull PadTrigger trigger, double power, double vertical) {
        return new JumpPad(id, trigger, power, vertical, null, new EffectConfig());
    }

    /** The same pad thrown towards a fixed yaw. */
    public @NotNull JumpPad towards(float yaw) {
        return new JumpPad(id, trigger, power, vertical, yaw, effect);
    }

    /** The same pad with an effect. */
    public @NotNull JumpPad withEffect(@NotNull EffectConfig effect) {
        return new JumpPad(id, trigger, power, vertical, yaw, effect);
    }

    /** The same pad at another trigger, for an editor that moves it. */
    public @NotNull JumpPad withTrigger(@NotNull PadTrigger trigger) {
        return new JumpPad(id, trigger, power, vertical, yaw, effect);
    }

    /** Whether it throws the way the player looks rather than a fixed way. */
    public boolean followsLook() {
        return yaw == null;
    }

    /** The launch for a player looking towards {@code playerYaw}. */
    public @NotNull Vector velocity(float playerYaw) {
        double radians = Math.toRadians(yaw != null ? yaw : playerYaw);
        return new Vector(-Math.sin(radians) * power, vertical, Math.cos(radians) * power);
    }
}
