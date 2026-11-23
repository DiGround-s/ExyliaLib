package net.exylia.lib.api.killeffect;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;
import java.util.Objects;

/**
 * How a killer's kills are filmed in a killcam: a cosmetic the killer owns and
 * the victim sees.
 *
 * <p>The settings are the style's own keys as its file writes them (tone,
 * slow motion, impact frame, finale...). The killcam reads the ones it knows
 * over its own default style and ignores the rest, so a style written for a
 * newer killcam still plays on an older one.
 *
 * @param id       the style id, normalised as effect ids are
 * @param settings the style's keys, never {@code null}
 * @since 3.8.0
 */
public record KillCamStyle(@NotNull String id, @NotNull @Unmodifiable Map<String, Object> settings) {

    public KillCamStyle {
        Objects.requireNonNull(id, "id");
        settings = Map.copyOf(settings);
    }
}
