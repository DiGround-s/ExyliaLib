package net.exylia.lib.ui;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * How a player sees a plugin's menus: as chest windows, or as full-screen screens with a mouse cursor
 * ({@link net.exylia.lib.screen.Screens}). Which one a player gets is decided by {@link MenuStyles}.
 *
 * @since 1.495.0
 */
public enum MenuStyle {

    /** Chest windows, as every menu has always opened. The default. */
    CLASSIC,

    /**
     * Screens: a menu with a screen of its own opens that screen, and every other chest menu is drawn as cards
     * on a screen, its clicks still handled by the chest menu.
     */
    SCREEN;

    /**
     * Reads a style as a config file writes it: {@code CLASSIC} or {@code SCREEN}, in any case. A
     * {@code MenuStyle} is returned as it is.
     *
     * @return the style, or {@code null} for {@code DEFAULT}, a blank value, {@code null} or anything else, which
     *         all mean "let the next rule decide"
     */
    public static @Nullable MenuStyle parse(@Nullable Object value) {
        if (value instanceof MenuStyle style) return style;
        if (value == null) return null;
        return switch (value.toString().trim().toUpperCase(Locale.ROOT)) {
            case "CLASSIC", "CHEST", "MENU" -> CLASSIC;
            case "SCREEN", "SCREENS" -> SCREEN;
            default -> null;
        };
    }
}
