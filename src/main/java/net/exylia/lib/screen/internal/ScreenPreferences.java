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
    static final String LIGHT = "screen-light";
    static final String FOV = "view-fov";
    static final String ASPECT = "view-aspect";
    static final String FITTED = "view-fitted";

    /** The field of view and window shape screens and the HUD are drawn for until a player fits them. */
    static final int DEFAULT_FOV = 70, MIN_FOV = 30, MAX_FOV = 110;
    static final double DEFAULT_ASPECT = 1.78, MIN_ASPECT = 1.0, MAX_ASPECT = 3.6;

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
        Settings.register(lib, Setting.number(FOV, DEFAULT_FOV, MIN_FOV, MAX_FOV, 1)
                .category("interface").icon("ENDER_EYE").name("Field of view")
                .description("Your game's FOV, so screens", "and the HUD fit your window.")
                .build());
        Settings.register(lib, Setting.number(ASPECT, DEFAULT_ASPECT, MIN_ASPECT, MAX_ASPECT, 0.01)
                .category("interface").icon("PAINTING").name("Window shape")
                .description("Width over height of your", "game window: 1.78 is 16:9.")
                .build());
        Settings.register(lib, Setting.toggle(FITTED, false)
                .category("interface").icon("ITEM_FRAME").name("Screens fitted")
                .description("On once screens and the HUD", "are fitted to your window.")
                .build());
        Settings.register(lib, Setting.toggle(MOTION, false)
                .category("interface").icon("FEATHER").name("Reduced motion")
                .description("Full-screen menus move only", "as much as they need to.")
                .build());
        Settings.register(lib, Setting.toggle(LIGHT, false)
                .category("interface").icon("GLASS_PANE").name("Lighter screens")
                .description("Full-screen menus draw less", "detail, for a faster game.")
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

    /** The field of view the player fitted, in degrees: 70 until they do. */
    public static double fov(UUID player) {
        return number(player, FOV, DEFAULT_FOV, MIN_FOV, MAX_FOV);
    }

    /** The player's window, width over height: 16:9 until they fit it. */
    public static double aspect(UUID player) {
        return number(player, ASPECT, DEFAULT_ASPECT, MIN_ASPECT, MAX_ASPECT);
    }

    /**
     * How much larger than at FOV 70 something must be drawn to fill the same share of this player's window: what
     * screens and the HUD are scaled by.
     */
    public static double fovScale(UUID player) {
        return fovScale(fov(player));
    }

    public static double fovScale(double fov) {
        return Math.tan(Math.toRadians(fov / 2)) / Math.tan(Math.toRadians(DEFAULT_FOV / 2.0));
    }

    /** Whether the player fitted screens to their window: network-wide. */
    public static boolean fitted(UUID player) {
        return Boolean.parseBoolean(value(player, FITTED));
    }

    /** Stores the fitted view, rescaling a screen they have open; {@code done} marks them fitted. */
    static void view(Player player, double fov, double aspect, boolean done) {
        set(player, FOV, Long.toString(Math.round(Math.max(MIN_FOV, Math.min(MAX_FOV, fov)))));
        set(player, ASPECT, Double.toString(Math.round(Math.max(MIN_ASPECT, Math.min(MAX_ASPECT, aspect)) * 100) / 100.0));
        if (done) set(player, FITTED, "true");
        LiveScreen live = ScreenRuntime.live(player.getUniqueId());
        if (live != null) live.rescale();
    }

    /**
     * Keeps a size the player chose for their old FOV looking the same at the new one: screens now grow with the FOV
     * themselves, so a size picked to make up for a wide FOV would count twice.
     */
    static void keepSize(Player player, double from, double to) {
        int size = size(player.getUniqueId());
        if (from == to || size == clampSize(ScreenSettings.get().screenSize())) return;
        long kept = Math.round(size * fovScale(from) / fovScale(to) / SIZE_STEP) * SIZE_STEP;
        set(player, SIZE, Integer.toString(clampSize((int) kept)));
    }

    private static double number(UUID player, String key, double fallback, double min, double max) {
        String value = value(player, key);
        try {
            return value == null ? fallback : Math.max(min, Math.min(max, Double.parseDouble(value)));
        } catch (NumberFormatException broken) {
            return fallback;
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

    /** Whether the player asked full-screen menus to draw at their lowest detail, for frame rate. */
    public static boolean light(UUID player) {
        return Boolean.parseBoolean(value(player, LIGHT));
    }

    static void light(Player player, boolean on) {
        set(player, LIGHT, Boolean.toString(on));
    }

    public static void reducedMotion(Player player, boolean reduced) {
        set(player, MOTION, Boolean.toString(reduced));
    }

    /** Back to what the server says: every screen preference and the menu style. */
    static void reset(Player player) {
        for (String key : new String[]{SPEED, CURSOR, ASSIST, MOTION, LIGHT}) set(player, key, null);
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

    private static final NamespacedKey ASKED = legacyKey("view_fit_asked");

    /**
     * Whether to ask the player to fit screens to their window now, marking it asked: on the viewer's thread. Never
     * once they fitted (network-wide); once per server for a player who chose later, kept in their data.
     */
    static boolean askFit(Player player) {
        if (library == null || !ScreenSettings.get().firstScreenSetup() || fitted(player.getUniqueId())) return false;
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (data == null || data.has(ASKED)) return false;
        data.set(ASKED, PersistentDataType.BOOLEAN, true);
        return true;
    }

    private static NamespacedKey legacyKey(String name) {
        return new NamespacedKey("exylialib", name);
    }
}
