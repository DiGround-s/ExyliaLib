package net.exylia.lib.hologram.internal;

import net.exylia.lib.hologram.HologramConfig;
import net.kyori.adventure.text.Component;

import java.util.Locale;

/**
 * One display entity's state, as plain values.
 *
 * <p>Everything the client needs to draw a display, worked out from config once
 * and then only compared. Keeping it free of PacketEvents types is what lets
 * the engine be tested without a server, and what confines the optional
 * dependency to {@link DisplayPackets}.
 *
 * <p>Mutable on purpose: a hologram refresh changes the text of an existing
 * display rather than building a new one, and the whole point is to send only
 * what changed.
 */
public final class DisplayState {

    /** Metadata indexes shared by every display, from the vanilla protocol. */
    static final int INTERPOLATION_DELAY = 8;
    static final int TRANSFORMATION_INTERPOLATION_DURATION = 9;
    static final int POS_ROT_INTERPOLATION_DURATION = 10;
    static final int TRANSLATION = 11;
    static final int SCALE = 12;
    static final int BILLBOARD = 15;
    static final int BRIGHTNESS = 16;
    static final int VIEW_RANGE = 17;
    static final int GLOW_COLOR = 22;

    /** Text display only. */
    static final int TEXT = 23;
    static final int LINE_WIDTH = 24;
    static final int BACKGROUND_COLOR = 25;
    static final int TEXT_OPACITY = 26;
    static final int TEXT_FLAGS = 27;

    /** Item display only. */
    static final int ITEM = 23;
    static final int ITEM_TRANSFORM = 24;

    /** Block display only. */
    static final int BLOCK_STATE = 23;

    /** Bit flags packed into {@link #TEXT_FLAGS}, from the vanilla protocol. */
    static final byte FLAG_SHADOW = 0x01;
    static final byte FLAG_SEE_THROUGH = 0x02;
    static final byte FLAG_DEFAULT_BACKGROUND = 0x04;
    static final byte ALIGN_CENTER = 0x00;
    static final byte ALIGN_LEFT = 0x08;
    static final byte ALIGN_RIGHT = 0x10;

    private final int entityId;
    private final HologramConfig.Kind kind;
    private final HologramConfig.Properties properties;

    /** The line this display draws, already parsed. Text displays only. */
    private Component text;

    /** What an ITEM or BLOCK display shows, resolved once at creation. */
    private final org.bukkit.Material material;

    DisplayState(int entityId, HologramConfig.Kind kind, HologramConfig.Properties properties) {
        this(entityId, kind, properties, org.bukkit.Material.STONE);
    }

    DisplayState(int entityId, HologramConfig.Kind kind, HologramConfig.Properties properties,
                 org.bukkit.Material material) {
        this.entityId = entityId;
        this.kind = kind;
        this.properties = properties;
        this.material = material;
        this.text = Component.empty();
    }

    int entityId() {
        return entityId;
    }

    HologramConfig.Kind kind() {
        return kind;
    }

    HologramConfig.Properties properties() {
        return properties;
    }

    /** What an ITEM or BLOCK display shows. */
    org.bukkit.Material material() {
        return material;
    }

    Component text() {
        return text;
    }

    void text(Component text) {
        this.text = text;
    }

    /** The billboard constraint as the protocol numbers it. */
    byte billboard() {
        return switch (properties.billboard().toUpperCase(Locale.ROOT)) {
            case "FIXED" -> 0;
            case "VERTICAL" -> 1;
            case "HORIZONTAL" -> 2;
            default -> 3;
        };
    }

    /** Shadow, see-through, background and alignment, packed into one byte. */
    byte textFlags() {
        byte flags = 0;
        if (properties.shadow()) {
            flags |= FLAG_SHADOW;
        }
        if (properties.seeThrough()) {
            flags |= FLAG_SEE_THROUGH;
        }
        if (properties.defaultBackground()) {
            flags |= FLAG_DEFAULT_BACKGROUND;
        }
        flags |= switch (properties.alignment().toUpperCase(Locale.ROOT)) {
            case "LEFT" -> ALIGN_LEFT;
            case "RIGHT" -> ALIGN_RIGHT;
            default -> ALIGN_CENTER;
        };
        return flags;
    }

    /**
     * The background as the packed ARGB integer the protocol wants.
     *
     * <p>An unreadable colour is not worth refusing to draw a hologram over, so
     * anything unparseable falls back to fully transparent, which is what the
     * ExyliaCommons default was.
     */
    int backgroundArgb() {
        String value = properties.backgroundColor().trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        try {
            long parsed = Long.parseLong(value, 16);
            // Six digits means no alpha was written, so it is opaque.
            return value.length() <= 6
                    ? (int) (0xFF000000L | parsed)
                    : (int) parsed;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** The light level override, or -1 to use the light where it stands. */
    int packedBrightness() {
        int brightness = properties.brightness();
        if (brightness < 0) {
            return -1;
        }
        int level = Math.clamp(brightness, 0, 15);
        // Block light in the low bits, sky light in the high ones.
        return (level << 4) | (level << 20);
    }
}
