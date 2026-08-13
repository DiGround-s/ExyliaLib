package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The sounds a menu makes.
 *
 * <p>Sensible defaults, overridable per menu, and silenceable by writing an
 * empty value. A server owner who wants menus to sound like the rest of their
 * server changes one file; one who wants a particular menu to be silent writes
 * {@code sounds: {open: ""}}.
 *
 * <p>Every value is an inline effect string, the same one {@code Effects}
 * already understands: {@code BLOCK_NOTE_BLOCK_PLING|1|1.6}.
 *
 * @since 1.22.0
 */
public record UiSounds(@Nullable String open, @Nullable String close, @Nullable String click,
                       @Nullable String denied, @Nullable String failed,
                       @Nullable String back, @Nullable String page) {

    /** What menus sound like unless somebody says otherwise. */
    public static final UiSounds DEFAULTS = new UiSounds(
            "BLOCK_BARREL_OPEN|0.6|1.4",
            "BLOCK_BARREL_CLOSE|0.6|1.4",
            "UI_BUTTON_CLICK|0.5|1.6",
            "BLOCK_NOTE_BLOCK_BASS|0.6|0.8",
            "ENTITY_VILLAGER_NO|0.6|1",
            "UI_BUTTON_CLICK|0.5|1.2",
            "ITEM_BOOK_PAGE_TURN|0.7|1.2");

    /** Nothing at all. */
    public static final UiSounds SILENT = new UiSounds(null, null, null, null, null, null, null);

    /**
     * Reads overrides from a configuration section, keeping the defaults for
     * anything not mentioned.
     *
     * <p>A key present but empty means silence, which is different from a key
     * that is absent.
     *
     * @param values the section's values
     * @param base   what to fall back to
     * @return the resulting sounds
     */
    public static @NotNull UiSounds of(@NotNull Map<String, Object> values,
                                       @NotNull UiSounds base) {
        return new UiSounds(
                pick(values, "open", base.open()),
                pick(values, "close", base.close()),
                pick(values, "click", base.click()),
                pick(values, "denied", base.denied()),
                pick(values, "failed", base.failed()),
                pick(values, "back", base.back()),
                pick(values, "page", base.page()));
    }

    private static String pick(Map<String, Object> values, String key, String fallback) {
        if (!values.containsKey(key)) {
            return fallback;
        }
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
