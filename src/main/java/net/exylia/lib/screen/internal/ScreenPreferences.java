package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.ScreenSettings;
import net.exylia.lib.settings.Setting;
import net.exylia.lib.settings.Settings;
import net.exylia.lib.settings.internal.SettingsRuntime;
import net.exylia.lib.settings.internal.SettingsRuntime.PluginStore;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.ui.MenuStyles;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Each player's own screen preferences: size, cursor speed, cursor mode and assist, reduced motion.
 *
 * <p>They are library settings ({@link Settings}), so they live in the library's database
 * ({@code plugins/ExyliaLib/database.yml}, table {@code exylia_player_settings}) and are network-wide: servers that
 * share that database share them, and with Redis on a change reaches the others at once. Reads are memory. A player
 * who never chose follows {@code screens.yml}: each setting's default is the server's value, registered again when the
 * file is reloaded, and only a value that differs from it is stored.
 *
 * <p>Until 1.556.0 they were kept in the player's data on each server. A joining player's old values are moved into
 * the database once, where it holds nothing for them yet, and then removed from their data.
 *
 * <p>Internal: free to change without notice.
 */
public final class ScreenPreferences {

    static final String SIZE = "screen-size";
    static final String SPEED = "screen-cursor-speed";
    static final String CURSOR = "screen-cursor-mode";
    static final String ASSIST = "screen-cursor-assist";
    static final String MOTION = "screen-reduced-motion";

    /** Cursor speed for a player who never chose one, and the range they may choose from. */
    static final double DEFAULT_SPEED = 3.0, MIN_SPEED = 0.1, MAX_SPEED = 10.0;
    /** How large a screen may be drawn, in percent. 100 fills three quarters of a window at FOV 70, 200 at FOV 110. */
    static final int SIZE_MIN = 50, SIZE_MAX = 250, SIZE_STEP = 10;

    /** Where a value was kept in the player's data before 1.556.0. */
    private record Legacy(NamespacedKey key, PersistentDataType<?, ?> type) {
    }

    /** By setting key: every preference the library kept in the player's data, the menu style included. */
    private static final Map<String, Legacy> LEGACY = Map.of(
            SPEED, new Legacy(legacyKey("screen_speed"), PersistentDataType.DOUBLE),
            CURSOR, new Legacy(legacyKey("screen_cursor"), PersistentDataType.STRING),
            ASSIST, new Legacy(legacyKey("screen_assist"), PersistentDataType.BOOLEAN),
            MOTION, new Legacy(legacyKey("screen_motion"), PersistentDataType.BOOLEAN),
            SIZE, new Legacy(legacyKey("screen_size"), PersistentDataType.INTEGER),
            MenuStyles.SETTING, new Legacy(legacyKey("menu_style"), PersistentDataType.STRING));

    private static volatile Plugin library;

    private ScreenPreferences() {
    }

    /**
     * Registers the preferences under Interface with the server's defaults, and moves the old values of everyone
     * online. Called by the library once {@code screens.yml} is read.
     */
    public static void init(Plugin lib) {
        library = lib;
        register();
        for (Player online : lib.getServer().getOnlinePlayers()) joined(online);
    }

    /** Registers the preferences again with {@code screens.yml}'s current defaults. Called when it is reloaded. */
    public static void register() {
        Plugin lib = library;
        if (lib == null) return;
        ScreenSettings server = ScreenSettings.get();
        Settings.register(lib, Setting.number(SPEED, DEFAULT_SPEED, MIN_SPEED, MAX_SPEED, 0.1)
                .category("interface").icon("COMPASS").name("Cursor speed")
                .description("How fast the cursor moves", "on full-screen menus.")
                .build());
        Settings.register(lib, Setting.number(SIZE, clampSize(server.screenSize()), SIZE_MIN, SIZE_MAX, SIZE_STEP)
                .category("interface").icon("SPYGLASS").name("Screen size")
                .description("How large full-screen menus", "are drawn, in percent.")
                .build());
        Setting.Builder cursor = Setting.choice(CURSOR, CursorLead.Mode.of(server.cursorMode()).name().toLowerCase(Locale.ROOT),
                        modeIds())
                .category("interface").icon("SPECTRAL_ARROW").name("Cursor")
                .description("How the cursor follows", "your mouse on full-screen menus.");
        for (CursorLead.Mode mode : CursorLead.Mode.values()) cursor.label(id(mode), mode.label);
        Settings.register(lib, cursor.build());
        Settings.register(lib, Setting.toggle(ASSIST, server.cursorAssist())
                .category("interface").icon("TARGET").name("Cursor assist")
                .description("Makes buttons easier to hit", "on full-screen menus.")
                .build());
        Settings.register(lib, Setting.toggle(MOTION, false)
                .category("interface").icon("FEATHER").name("Reduced motion")
                .description("Full-screen menus move only", "as much as they need to.")
                .build());
    }

    /**
     * Moves a joining player's old values from their data into the database, a tick on so the library has started
     * reading their settings. Their data keeps them until the database has them.
     */
    static void joined(Player player) {
        Plugin lib = library;
        if (lib == null || !lib.isEnabled()) return;
        Tasks.of(lib).runAtEntity(player, () -> {
            PluginStore store = SettingsRuntime.find(lib).orElse(null);
            if (store == null) return;
            PersistentDataContainer data = player.getPersistentDataContainer();
            Map<String, String> old = new HashMap<>();
            LEGACY.forEach((setting, legacy) -> {
                Object value = data.has(legacy.key()) ? data.get(legacy.key(), legacy.type()) : null;
                if (value != null) old.put(setting, value.toString().toLowerCase(Locale.ROOT));
            });
            if (old.isEmpty()) return;
            store.adopt(player.getUniqueId(), old).thenAccept(moved -> {
                if (moved && lib.isEnabled()) {
                    Tasks.of(lib).runAtEntity(player, () -> LEGACY.values().forEach(legacy -> data.remove(legacy.key())));
                }
            });
        });
    }

    // ------------------------------------------------------------------ reads, from memory on any thread

    public static double sensitivity(UUID player) {
        String value = value(player, SPEED);
        try {
            return value == null ? DEFAULT_SPEED : Double.parseDouble(value);
        } catch (NumberFormatException broken) {
            return DEFAULT_SPEED;
        }
    }

    public static int size(UUID player) {
        String value = value(player, SIZE);
        try {
            return clampSize(value == null ? ScreenSettings.get().screenSize() : (int) Math.round(Double.parseDouble(value)));
        } catch (NumberFormatException broken) {
            return clampSize(ScreenSettings.get().screenSize());
        }
    }

    static CursorLead.Mode cursorMode(UUID player) {
        String value = value(player, CURSOR);
        return CursorLead.Mode.of(value == null ? ScreenSettings.get().cursorMode() : value);
    }

    static boolean cursorAssist(UUID player) {
        String value = value(player, ASSIST);
        return value == null ? ScreenSettings.get().cursorAssist() : Boolean.parseBoolean(value);
    }

    /** Whether the player themselves asked for reduced motion. */
    static boolean reducedMotionChosen(UUID player) {
        return Boolean.parseBoolean(value(player, MOTION));
    }

    /** Whether the player asked for reduced motion, or the server did for everyone. */
    public static boolean reducedMotion(UUID player) {
        return reducedMotionChosen(player) || ScreenSettings.get().reducedMotion();
    }

    // ------------------------------------------------------------------ writes: memory now, the database after

    /** Sets a player's cursor speed, kept between 0.1 and 10. */
    public static void sensitivity(Player player, double speed) {
        set(player, SPEED, Double.toString(Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed))));
    }

    /** Sets a player's own size, applied at once to a screen they have open; {@code null} goes back to the server's. */
    public static void size(Player player, Integer percent) {
        set(player, SIZE, percent == null ? null : Integer.toString(clampSize(percent)));
        LiveScreen live = ScreenRuntime.live(player.getUniqueId());
        if (live != null) live.rescale();
    }

    static void cursorMode(Player player, CursorLead.Mode mode) {
        set(player, CURSOR, id(mode));
    }

    static void cursorAssist(Player player, boolean on) {
        set(player, ASSIST, Boolean.toString(on));
    }

    public static void reducedMotion(Player player, boolean reduced) {
        set(player, MOTION, Boolean.toString(reduced));
    }

    /** Back to what the server says: every screen preference and the menu style. */
    static void reset(Player player) {
        for (String key : new String[]{SPEED, CURSOR, ASSIST, MOTION}) set(player, key, null);
        size(player, null);
        MenuStyles.choose(player, null);
    }

    /** A size in percent, inside what screens can be drawn at. */
    static int clampSize(int percent) {
        return Math.max(SIZE_MIN, Math.min(SIZE_MAX, percent));
    }

    // ------------------------------------------------------------------

    /** The player's stored value, the server's default while it is not read, or {@code null} without the library. */
    private static String value(UUID player, String key) {
        Plugin lib = library;
        PluginStore store = lib == null ? null : SettingsRuntime.find(lib).orElse(null);
        Setting setting = store == null ? null : store.setting(key);
        return setting == null ? null : store.value(player, setting);
    }

    /** Stores a value; {@code null} is the default, which removes the player's row. */
    private static void set(Player player, String key, String value) {
        Plugin lib = library;
        PluginStore store = lib == null ? null : SettingsRuntime.find(lib).orElse(null);
        Setting setting = store == null ? null : store.setting(key);
        if (setting == null) return;
        Settings.set(player, lib, key, value == null ? setting.defaultValue() : value);
    }

    private static String id(CursorLead.Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    private static String[] modeIds() {
        CursorLead.Mode[] modes = CursorLead.Mode.values();
        String[] ids = new String[modes.length];
        for (int i = 0; i < modes.length; i++) ids[i] = id(modes[i]);
        return ids;
    }

    private static NamespacedKey legacyKey(String name) {
        return new NamespacedKey("exylialib", name);
    }
}
