package net.exylia.lib.api.events.custom;

/**
 * What one of a minigame's settings holds.
 *
 * <p>The kind decides three things at once: how the value is stored, how the
 * generated settings screen draws it, and what an admin clicking it is asked
 * for. A {@link #DURATION} is an {@link #INTEGER} in the file and a
 * {@code 30s} box on the screen, which is the whole reason the two are told
 * apart.
 *
 * @since 1.7.0
 */
public enum MinigameSettingKind {

    /** A switch. Stored as a boolean, drawn as on or off, clicked to flip. */
    FLAG,

    /** A whole number. Clicked to step it, shift-clicked to type one. */
    INTEGER,

    /** A number with decimals. Clicked to type one. */
    DECIMAL,

    /** A length of time in <b>seconds</b>, typed as {@code 30s} or {@code 5m}. */
    DURATION,

    /** Free text, such as a material name. Clicked to type it. */
    TEXT
}
