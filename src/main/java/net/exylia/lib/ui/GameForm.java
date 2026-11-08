package net.exylia.lib.ui;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * How a player sees a game: drawn inside a screen, or standing in the world. Asked of {@link MenuStyles#gameForm} once,
 * at the start of a round. Separate from {@link MenuStyle} because a game has no chest form and menus have no world
 * form, and so that no {@code switch} over {@link MenuStyle} ever meets a value it does not know.
 *
 * @since 1.531.0
 */
public enum GameForm {

    /** The game inside a screen, with its rules in tabs. */
    SCREEN,

    /** The game standing in the world, in 3D: the only form a client that cannot draw screens has. */
    WORLD;

    /**
     * Reads a form as a config file or a command writes it: {@code SCREEN}, or {@code WORLD}, {@code 3D} or
     * {@code STAGE}, in any case. A {@code GameForm} is returned as it is.
     *
     * @return the form, or {@code null} for {@code AUTO}, {@code DEFAULT}, a blank value, {@code null} or anything
     *         else, which all mean "let the next rule decide"
     */
    public static @Nullable GameForm parse(@Nullable Object value) {
        if (value instanceof GameForm form) return form;
        if (value == null) return null;
        return switch (value.toString().trim().toUpperCase(Locale.ROOT)) {
            case "SCREEN", "SCREENS" -> SCREEN;
            case "WORLD", "3D", "STAGE" -> WORLD;
            default -> null;
        };
    }
}
