package net.exylia.lib.api.events.custom;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Locale;

/**
 * One value an admin can change about a minigame, declared once.
 *
 * <p>A setting declared here is four things at the same time, and that is the
 * point: it is the default a fresh arena is created with, the row the generated
 * settings screen draws, the box the click opens, and the number
 * {@link MinigameArena#settingInt(String)} and friends read back. Declaring it
 * in one place is what stops those four from drifting apart.
 *
 * <p>Build one with a factory and refine it with the withers:
 *
 * <pre>{@code
 * MinigameSetting.duration("grace-period", 5)
 *         .label("{info}&lGRACE PERIOD")
 *         .lore("Nobody can be hit while it lasts")
 *         .icon("CLOCK");
 * }</pre>
 *
 * @param key          the key the value is stored and read under, in
 *                     {@code kebab-case}
 * @param kind         what it holds
 * @param defaultValue what a fresh arena starts with; a {@link Boolean} for a
 *                     {@link MinigameSettingKind#FLAG}, an {@link Integer} for
 *                     an {@link MinigameSettingKind#INTEGER} or a
 *                     {@link MinigameSettingKind#DURATION} (in seconds), a
 *                     {@link Double} for a {@link MinigameSettingKind#DECIMAL},
 *                     a {@link String} for {@link MinigameSettingKind#TEXT}
 * @param label        what the settings screen calls it, colour codes and
 *                     ExyliaLib palette tokens included
 * @param lore         the lines under the name explaining what it does; the
 *                     current value and the click hint are added for you
 * @param icon         the material the row is drawn with
 * @since 1.7.0
 */
public record MinigameSetting(
        @NotNull String key,
        @NotNull MinigameSettingKind kind,
        @NotNull Object defaultValue,
        @NotNull String label,
        @NotNull @Unmodifiable List<String> lore,
        @NotNull String icon) {

    private static final String DEFAULT_ICON = "PAPER";

    /** Validates and freezes what was handed in. */
    public MinigameSetting {
        key = require(key, "key");
        if (kind == null) throw new IllegalArgumentException("A setting needs a kind.");
        if (defaultValue == null) throw new IllegalArgumentException("Setting '" + key + "' needs a default.");
        label = label == null || label.isBlank() ? titleOf(key) : label;
        lore = lore == null ? List.of() : List.copyOf(lore);
        icon = icon == null || icon.isBlank() ? DEFAULT_ICON : icon.toUpperCase(Locale.ROOT);
    }

    /** A switch. */
    public static @NotNull MinigameSetting flag(@NotNull String key, boolean defaultValue) {
        return new MinigameSetting(key, MinigameSettingKind.FLAG, defaultValue, null, null, "LEVER");
    }

    /** A whole number. */
    public static @NotNull MinigameSetting integer(@NotNull String key, int defaultValue) {
        return new MinigameSetting(key, MinigameSettingKind.INTEGER, defaultValue, null, null, null);
    }

    /** A number with decimals. */
    public static @NotNull MinigameSetting decimal(@NotNull String key, double defaultValue) {
        return new MinigameSetting(key, MinigameSettingKind.DECIMAL, defaultValue, null, null, null);
    }

    /**
     * A length of time, declared and read back in <b>seconds</b>.
     *
     * <p>The admin types {@code 5m}; {@link MinigameArena#settingInt(String)}
     * still answers {@code 300}.
     */
    public static @NotNull MinigameSetting duration(@NotNull String key, int defaultSeconds) {
        return new MinigameSetting(key, MinigameSettingKind.DURATION, defaultSeconds, null, null, "CLOCK");
    }

    /** Free text, such as a material name. */
    public static @NotNull MinigameSetting text(@NotNull String key, @NotNull String defaultValue) {
        return new MinigameSetting(key, MinigameSettingKind.TEXT, defaultValue, null, null, "NAME_TAG");
    }

    /** What the settings screen calls it. */
    public @NotNull MinigameSetting label(@NotNull String label) {
        return new MinigameSetting(key, kind, defaultValue, label, lore, icon);
    }

    /** What the settings screen says it does, one line per entry. */
    public @NotNull MinigameSetting lore(@NotNull String... lines) {
        return new MinigameSetting(key, kind, defaultValue, label, List.of(lines), icon);
    }

    /** The material the settings screen draws it with. */
    public @NotNull MinigameSetting icon(@NotNull String material) {
        return new MinigameSetting(key, kind, defaultValue, label, lore, material);
    }

    /**
     * The placeholder the settings screen prints the value into.
     *
     * <p>The key with its dashes turned into underscores, so
     * {@code grace-period} is drawn as {@code %grace_period%}. Derived rather
     * than declared: a name that can only be one thing is not worth asking for.
     *
     * @return the placeholder name, without the percent signs
     */
    public @NotNull String placeholder() {
        return key.replace('-', '_');
    }

    /** The default as a boolean, or {@code false} when it is not one. */
    public boolean asFlag() {
        return defaultValue instanceof Boolean flag && flag;
    }

    /** The default as a whole number, or {@code 0} when it is not one. */
    public int asInt() {
        return defaultValue instanceof Number number ? number.intValue() : 0;
    }

    /** The default as a decimal, or {@code 0} when it is not one. */
    public double asDouble() {
        return defaultValue instanceof Number number ? number.doubleValue() : 0d;
    }

    /** The default as text. */
    public @NotNull String asText() {
        return String.valueOf(defaultValue);
    }

    /**
     * The key, checked.
     *
     * <p>Strict for the same reason the id is: the key becomes a YAML path in
     * the generated settings screen, a column value and the argument of the
     * action that edits it. A dot would quietly nest the screen's item under
     * another one, and a space would end the action's argument early.
     */
    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A setting needs a " + what + ".");
        }
        String normalised = value.toLowerCase(Locale.ROOT);
        if (!normalised.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("The setting " + what + " '" + value
                    + "' may only hold lowercase letters, digits and dashes.");
        }
        return normalised;
    }

    /** {@code grace-period} drawn as {@code GRACE PERIOD}, for a label nobody wrote. */
    private static String titleOf(String key) {
        return "{primary}&l" + key.replace('-', ' ').toUpperCase(Locale.ROOT);
    }
}
