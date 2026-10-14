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
    static final int ROTATION_LEFT = 13;
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

    /** Every entity: the flags byte, and the bit that draws the glow outline. */
    static final int ENTITY_FLAGS = 0;
    static final byte FLAG_GLOWING = 0x40;

    /** Interaction entity, the invisible box a click lands on. */
    static final int INTERACTION_WIDTH = 8;
    static final int INTERACTION_HEIGHT = 9;
    static final int INTERACTION_RESPONSIVE = 10;

    /**
     * Where the model sits relative to the entity, how big it is, and how it is
     * turned: the display's transformation, as the protocol splits it.
     */
    record Transform(float tx, float ty, float tz, float sx, float sy, float sz,
                     float qy, float qw) {

        boolean hasTranslation() {
            return tx != 0 || ty != 0 || tz != 0;
        }

        boolean hasRotation() {
            return qy != 0 || qw != 1;
        }

        /**
         * This transformation as one viewer is shown it: grown by
         * {@code grow} and moved by the rest.
         */
        Transform view(double grow, double x, double y, double z) {
            return new Transform((float) (tx * grow + x), (float) (ty * grow + y),
                    (float) (tz * grow + z), (float) (sx * grow), (float) (sy * grow),
                    (float) (sz * grow), qy, qw);
        }
    }

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
    private final LineStyle style;

    /** The transformation viewers were last given. */
    private volatile Transform transform;

    /** The line this display draws, already parsed. Text displays only. */
    private Component text;

    /** What an ITEM or BLOCK display shows, resolved once at creation. */
    private final org.bukkit.Material material;

    /** The item a head line resolved to; set once its texture is known. */
    private volatile org.bukkit.inventory.ItemStack item;

    DisplayState(int entityId, HologramConfig.Kind kind, HologramConfig.Properties properties) {
        this(entityId, kind, properties, org.bukkit.Material.STONE);
    }

    DisplayState(int entityId, HologramConfig.Kind kind, HologramConfig.Properties properties,
                 org.bukkit.Material material) {
        this(entityId, kind, properties, material, LineStyle.PLAIN);
    }

    DisplayState(int entityId, HologramConfig.Kind kind, HologramConfig.Properties properties,
                 org.bukkit.Material material, LineStyle style) {
        this.entityId = entityId;
        this.kind = kind;
        this.properties = properties;
        this.material = material;
        this.style = style;
        this.text = Component.empty();
        this.transform = transformAt(0);
    }

    LineStyle style() {
        return style;
    }

    Transform transform() {
        return transform;
    }

    void transform(Transform transform) {
        this.transform = transform;
    }

    /**
     * The transformation at a moment.
     *
     * <p>Worked out from the clock alone, so every viewer is shown the same
     * frame and a player who walks up mid-spin joins it in step. A block is
     * shifted by half its size so it turns around its own centre instead of
     * its corner; that shift turns along with it.
     */
    Transform transformAt(long millis) {
        double seconds = millis / 1000.0;
        double grow = 1 + style.pulse() * Math.sin(seconds * 2 * Math.PI / style.pulsePeriod());
        double sx = properties.scaleX() * grow;
        double sy = properties.scaleY() * grow;
        double sz = properties.scaleZ() * grow;
        double turned = style.spin() * seconds
                + style.sway() * Math.sin(seconds * 2 * Math.PI / style.swayPeriod());
        double angle = Math.toRadians(turned % 360);
        double ty = style.bob() * Math.sin(seconds * 2 * Math.PI / style.bobPeriod());
        double tx = 0;
        double tz = 0;
        if (kind == HologramConfig.Kind.BLOCK && style.block()) {
            double x = -sx / 2;
            double z = -sz / 2;
            tx = x * Math.cos(angle) + z * Math.sin(angle);
            tz = -x * Math.sin(angle) + z * Math.cos(angle);
        }
        return new Transform((float) tx, (float) ty, (float) tz,
                (float) sx, (float) sy, (float) sz,
                (float) Math.sin(angle / 2), (float) Math.cos(angle / 2));
    }

    /** The glow outline colour as RGB, -1 for the team colour, or null for no glow. */
    Integer glowRgb() {
        String glow = style.glow();
        if (glow == null || kind == HologramConfig.Kind.TEXT) {
            return null;
        }
        String value = glow.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        try {
            return value.isEmpty() ? -1 : Integer.parseInt(value, 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            return -1;
        }
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

    /**
     * The exact item an ITEM display shows, such as a textured head, or
     * {@code null} for a plain stack of {@link #material()}.
     */
    org.bukkit.inventory.ItemStack item() {
        return item;
    }

    void item(org.bukkit.inventory.ItemStack item) {
        this.item = item;
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
