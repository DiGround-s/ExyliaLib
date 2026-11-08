package net.exylia.lib.ui;

import net.exylia.lib.screen.ScreenSettings;
import net.exylia.lib.settings.Setting;
import net.exylia.lib.settings.Settings;
import net.exylia.lib.task.Tasks;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Which {@link MenuStyle} a player gets for one plugin's menus. {@link Menus} asks it on every open, so a plugin
 * only says what its own config chose:
 *
 * <pre>{@code
 * MenuStyles.of(this).defaultStyle(() -> settings.get().menuStyle());   // "CLASSIC", "SCREEN" or "DEFAULT"
 * menus.open(player, "kits");     // a screen or a chest window, decided here
 * }</pre>
 *
 * <p>The first rule that answers wins:
 * <ol>
 *   <li>a client that cannot draw screens (Bedrock, older than 1.20.2, no PacketEvents) gets CLASSIC;</li>
 *   <li>a staff override from {@code /exylialib style} (for this plugin, else for all), even when players cannot
 *       choose and {@code /settings} is off;</li>
 *   <li>the player's own choice in {@code /settings}, when {@code screens.yml} lets players choose;</li>
 *   <li>the plugin's {@link #defaultStyle} ({@code DEFAULT} or nothing passes);</li>
 *   <li>{@code screens.yml}'s {@code plugins.<Plugin>} entry;</li>
 *   <li>{@code screens.yml}'s {@code default-style}, CLASSIC as shipped.</li>
 * </ol>
 *
 * <p>Games ask {@link #gameForm} instead, once at the start of a round: the same answer, except that a player on chest
 * windows plays in the world, since a game has no chest form.
 *
 * @since 1.495.0
 */
public final class MenuStyles {

    /** The {@code /settings} key of the player's own choice, under the library. */
    public static final String SETTING = "menu-style";

    private static final Map<String, MenuStyles> BY_PLUGIN = new ConcurrentHashMap<>();
    /** Each online player's own choice, read from their data on join. */
    private static final Map<UUID, MenuStyle> CHOSEN = new ConcurrentHashMap<>();
    /** Staff overrides of {@code /exylialib style}: per player, by plugin name or {@link #ALL}. Not kept across restarts. */
    private static final Map<UUID, Map<String, MenuStyle>> STAFF = new ConcurrentHashMap<>();
    /** Staff overrides of {@code /exylialib style game}: per player, by plugin name or {@link #ALL}. Not kept across restarts. */
    private static final Map<UUID, Map<String, GameForm>> STAFF_FORMS = new ConcurrentHashMap<>();
    private static final String ALL = "*";
    private static final NamespacedKey KEY = new NamespacedKey("exylialib", "menu_style");
    private static volatile Plugin library;

    private final Plugin plugin;
    private volatile Supplier<?> defaultStyle = () -> null;

    private MenuStyles(Plugin plugin) {
        this.plugin = plugin;
    }

    /** The menu style of {@code plugin}'s menus. Asking twice gives the same object. */
    public static @NotNull MenuStyles of(@NotNull Plugin plugin) {
        return BY_PLUGIN.compute(plugin.getName(), (name, old) -> old != null && old.plugin == plugin ? old : new MenuStyles(plugin));
    }

    /**
     * What this plugin's config chose, read on every open so a reload applies at once: a {@link MenuStyle}, or
     * text ({@code CLASSIC}, {@code SCREEN}; {@code DEFAULT} or {@code null} leave it to {@code screens.yml}).
     */
    public @NotNull MenuStyles defaultStyle(@NotNull Supplier<?> style) {
        this.defaultStyle = style;
        return this;
    }

    /** The style {@code player} gets for this plugin's menus now. Safe from any thread. */
    public @NotNull MenuStyle resolve(@NotNull Player player) {
        ScreenSettings settings = ScreenSettings.get();
        Object own;
        try {
            own = defaultStyle.get();
        } catch (RuntimeException broken) {
            own = null;
        }
        Map<String, String> plugins = settings.plugins();
        return decide(canShowScreens(player), staffOverride(player), settings.playerChoice() ? CHOSEN.get(player.getUniqueId()) : null,
                MenuStyle.parse(own), plugins == null ? null : MenuStyle.parse(plugins.get(plugin.getName())),
                MenuStyle.parse(settings.defaultStyle()));
    }

    /** Whether {@code player} gets screens for this plugin's menus now. */
    public boolean screens(@NotNull Player player) {
        return resolve(player) == MenuStyle.SCREEN;
    }

    /**
     * The form {@code player} gets for a game of this plugin: a screen for a player whose menu style is SCREEN, the
     * world for one on chest windows. Ask once, at the start of a round. Safe from any thread.
     *
     * @since 1.531.0
     */
    public @NotNull GameForm gameForm(@NotNull Player player) {
        return gameForm(player, null);
    }

    /**
     * As {@link #gameForm(Player)}, for a game that says what it wants: its config's {@code SCREEN} or {@code WORLD}
     * ({@code AUTO} or {@code null} leave it to the player's style). A client that cannot draw screens, or whose view a
     * camera holds, gets the world whatever is asked; a staff override wins over everything else.
     *
     * @since 1.531.0
     */
    public @NotNull GameForm gameForm(@NotNull Player player, @Nullable Object forced) {
        return decideForm(canShowScreens(player) && !net.exylia.lib.screen.internal.ScreenRuntime.cameraHeld(player),
                staffForm(player), GameForm.parse(forced), resolve(player));
    }

    /** The rules of {@link #gameForm}, first answer wins; package-private for tests. */
    static @NotNull GameForm decideForm(boolean capable, @Nullable GameForm staff, @Nullable GameForm forced, @NotNull MenuStyle style) {
        if (!capable) return GameForm.WORLD;
        if (staff != null) return staff;
        if (forced != null) return forced;
        return style == MenuStyle.SCREEN ? GameForm.SCREEN : GameForm.WORLD;
    }

    private @Nullable GameForm staffForm(Player player) {
        Map<String, GameForm> own = STAFF_FORMS.get(player.getUniqueId());
        if (own == null) return null;
        GameForm forPlugin = own.get(plugin.getName());
        return forPlugin != null ? forPlugin : own.get(ALL);
    }

    /**
     * Staff override for testing ({@code /exylialib style game}): {@code form} for {@code plugin}'s games only, or for
     * every plugin when {@code plugin} is {@code null}; a {@code null} form clears it (all of the player's when
     * {@code plugin} is {@code null}). Wins over everything except a client that cannot draw screens.
     *
     * @since 1.531.0
     */
    @ApiStatus.Internal
    public static void overrideForm(@NotNull Player player, @Nullable String plugin, @Nullable GameForm form) {
        if (form == null && plugin == null) {
            STAFF_FORMS.remove(player.getUniqueId());
            return;
        }
        String key = plugin == null ? ALL : BY_PLUGIN.keySet().stream().filter(plugin::equalsIgnoreCase).findFirst().orElse(plugin);
        if (form == null) STAFF_FORMS.computeIfPresent(player.getUniqueId(), (id, m) -> {
            m.remove(key);
            return m.isEmpty() ? null : m;
        });
        else STAFF_FORMS.computeIfAbsent(player.getUniqueId(), id -> new ConcurrentHashMap<>()).put(key, form);
    }

    /** The rules themselves, first answer wins; package-private for tests. */
    static @NotNull MenuStyle decide(boolean capable, @Nullable MenuStyle staff, @Nullable MenuStyle chosen, @Nullable MenuStyle plugin,
                                     @Nullable MenuStyle server, @Nullable MenuStyle fallback) {
        if (!capable) return MenuStyle.CLASSIC;
        if (staff != null) return staff;
        if (chosen != null) return chosen;
        if (plugin != null) return plugin;
        if (server != null) return server;
        return fallback != null ? fallback : MenuStyle.CLASSIC;
    }

    /** Whether the player's client can draw screens: Java, 1.20.2 or newer, and PacketEvents on the server. */
    public static boolean canShowScreens(@NotNull Player player) {
        try {
            if (!net.exylia.lib.packet.Packets.isAvailable()) return false;
            if (net.exylia.lib.client.Clients.isBedrock(player)) return false;
            return net.exylia.lib.packet.ClientVersions.atLeast(player,
                    com.github.retrooper.packetevents.protocol.player.ClientVersion.V_1_20_2);
        } catch (RuntimeException | LinkageError noPacketEvents) {
            return false;
        }
    }

    private @Nullable MenuStyle staffOverride(Player player) {
        Map<String, MenuStyle> own = STAFF.get(player.getUniqueId());
        if (own == null) return null;
        MenuStyle forPlugin = own.get(plugin.getName());
        return forPlugin != null ? forPlugin : own.get(ALL);
    }

    /**
     * Staff override for testing ({@code /exylialib style}): {@code style} for {@code plugin}'s menus only, or for
     * every plugin when {@code plugin} is {@code null}; a {@code null} style clears it (all of the player's
     * overrides when {@code plugin} is {@code null}). Wins over everything except an incapable client.
     */
    @ApiStatus.Internal
    public static void override(@NotNull Player player, @Nullable String plugin, @Nullable MenuStyle style) {
        if (style == null && plugin == null) {
            STAFF.remove(player.getUniqueId());
            return;
        }
        String key = plugin == null ? ALL : BY_PLUGIN.keySet().stream().filter(plugin::equalsIgnoreCase).findFirst().orElse(plugin);
        if (style == null) STAFF.computeIfPresent(player.getUniqueId(), (id, m) -> {
            m.remove(key);
            return m.isEmpty() ? null : m;
        });
        else STAFF.computeIfAbsent(player.getUniqueId(), id -> new ConcurrentHashMap<>()).put(key, style);
    }

    /** Names of the plugins that resolve a menu style. */
    @ApiStatus.Internal
    public static @NotNull java.util.Collection<String> plugins() {
        return java.util.List.copyOf(BY_PLUGIN.keySet());
    }

    /** The style the player picked for themselves, or {@code null} for the server's choice. */
    public static @Nullable MenuStyle choice(@NotNull Player player) {
        return CHOSEN.get(player.getUniqueId());
    }

    /**
     * Picks a style for the player ({@code null} goes back to the server's), kept with their data across
     * restarts. Applies to the next menu they open. Safe from any thread.
     */
    public static void choose(@NotNull Player player, @Nullable MenuStyle style) {
        if (style == null) CHOSEN.remove(player.getUniqueId());
        else CHOSEN.put(player.getUniqueId(), style);
        Plugin owner = library;
        if (owner == null) return;
        Tasks.of(owner).runAtEntity(player, () -> {
            if (style == null) player.getPersistentDataContainer().remove(KEY);
            else player.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, style.name());
        });
    }

    /** Whether players may choose for themselves on this server ({@code screens.yml} {@code player-choice}). */
    public static boolean choosable() {
        return ScreenSettings.get().playerChoice();
    }

    /**
     * Reads each joining player's choice and offers it in {@code /settings}. Called by the library on enable.
     */
    @ApiStatus.Internal
    public static void init(@NotNull Plugin lib) {
        library = lib;
        lib.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void onJoin(PlayerJoinEvent event) {
                read(event.getPlayer());
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onQuit(PlayerQuitEvent event) {
                CHOSEN.remove(event.getPlayer().getUniqueId());
                STAFF.remove(event.getPlayer().getUniqueId());
                STAFF_FORMS.remove(event.getPlayer().getUniqueId());
            }
        }, lib);
        for (Player online : lib.getServer().getOnlinePlayers()) Tasks.of(lib).runAtEntity(online, () -> read(online));
        Settings.category(lib, "interface", "ITEM_FRAME", "Interface", "How menus look to you.");
        Settings.register(lib, Setting.choice(SETTING, "default", "default", "classic", "screen")
                .category("interface")
                .icon("ITEM_FRAME")
                .name("Menu style")
                .label("default", "Server's choice").label("classic", "Chest windows").label("screen", "Screens")
                .description("Chest windows, or full-screen", "menus with a {highlight}mouse cursor{letters}.")
                .available(MenuStyles::choosable, "Chosen by the server")
                .store(new Setting.Store() {
                    @Override
                    public @Nullable String get(@NotNull Player player) {
                        MenuStyle chosen = choice(player);
                        return chosen == null ? null : chosen.name().toLowerCase(java.util.Locale.ROOT);
                    }

                    @Override
                    public void set(@NotNull Player player, @NotNull String value) {
                        choose(player, MenuStyle.parse(value));
                    }
                })
                .build());
    }

    private static void read(Player player) {
        MenuStyle stored = MenuStyle.parse(player.getPersistentDataContainer().get(KEY, PersistentDataType.STRING));
        if (stored == null) CHOSEN.remove(player.getUniqueId());
        else CHOSEN.put(player.getUniqueId(), stored);
    }
}
