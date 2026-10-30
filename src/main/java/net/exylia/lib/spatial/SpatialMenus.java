package net.exylia.lib.spatial;

import net.exylia.lib.config.BundledFiles;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.item.Item;
import net.exylia.lib.overlay.Overlays;
import net.exylia.lib.overlay.PluginOverlays;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.packet.Visibility;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.teleport.Teleports;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-world 3D menus: panels of tinted glass standing round the player, lists and grids of icons on
 * them, a carousel of plinths along the bottom and a live preview turning in the middle. Looked at to
 * aim, clicked to choose, scrolled to page, sneaked out of. Packets only, nothing placed in the world,
 * no resource pack.
 *
 * <pre>{@code
 * SpatialMenus menus = SpatialMenus.of(this);
 * menus.register("trims", menu -> menu
 *         .source("pieces", ctx -> pieces(ctx.viewer()))
 *         .source("patterns", ctx -> patterns(ctx))
 *         .preview(ctx -> SpatialPreview.npc(ctx.viewer(), trying(ctx)))
 *         .action("apply-set", ctx -> apply(ctx)));
 *
 * menus.open(player, "trims");                 // in front of the player
 * menus.open(player, "trims", "trims-1");      // at a spot an admin placed
 * menus.edit(admin, "trims");                  // move its pieces with the mouse wheel
 * }</pre>
 *
 * <p>Each menu's layout is {@code plugins/<Plugin>/spatial/<menu>.yml} ({@link SpatialLayout}),
 * written from the jar's {@code spatial/<menu>.yml} the first time and from the library's wardrobe
 * when the jar has none. While a menu is open the player is held still (optional per layout), wears
 * a hotbar with only a leave item, and may be hidden from everyone and everyone from them. A player
 * has one spatial menu open at a time, whichever plugin opened it: opening another closes the first.
 *
 * <p>Every method is safe from any thread; the menu itself is drawn and run on the player's.
 *
 * @since 1.406.0
 */
public final class SpatialMenus {

    private static final Map<String, SpatialMenus> BY_PLUGIN = new ConcurrentHashMap<>();
    private static final Map<UUID, MenuSession> OPEN = new ConcurrentHashMap<>();
    /** Players in a menu that hides everyone else. */
    private static final Set<UUID> HIDDEN = ConcurrentHashMap.newKeySet();
    private static volatile @Nullable Plugin library;

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final SpatialInput input;
    private final Map<String, SpatialMenu> menus = new ConcurrentHashMap<>();
    private final Map<String, ConfigFile<SpatialLayout>> layouts = new ConcurrentHashMap<>();
    private volatile @Nullable SpatialSpots spots;

    private SpatialMenus(Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.input = SpatialInput.of(plugin);
    }

    /** The plugin's menus, made on first use. */
    public static @NotNull SpatialMenus of(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> new SpatialMenus(plugin));
    }

    public @NotNull Plugin plugin() {
        return plugin;
    }

    TaskScheduler tasks() {
        return tasks;
    }

    /**
     * Registers a menu, or replaces it. Writes its layout file from the jar's {@code spatial/<id>.yml}
     * when the server has none, and adds keys the server's copy is missing. Call it on enable.
     */
    public @NotNull SpatialMenus register(@NotNull String id, @NotNull Consumer<SpatialMenu> definition) {
        Objects.requireNonNull(id, "id");
        String path = "spatial/" + id + ".yml";
        if (!new File(plugin.getDataFolder(), path).exists() && plugin.getResource(path) != null) {
            plugin.saveResource(path, false);
        }
        return define(id, definition);
    }

    /**
     * Registers a menu whose layout ships next to {@code anchor}: the jar's {@code spatial/<id>.yml} is
     * read through that class, as {@link BundledFiles} and {@code Languages} read theirs, so it is found
     * under a loader whose plugin cannot see its own jar (Lukittu). The file is installed when missing
     * and gains the keys a newer jar adds, as every bundled file does; call it on enable.
     *
     * @param anchor a class packaged with the layout, usually the plugin's main class
     * @since 1.408.0
     */
    public @NotNull SpatialMenus register(@NotNull String id, @NotNull Class<?> anchor,
                                          @NotNull Consumer<SpatialMenu> definition) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(anchor, "anchor");
        String path = "spatial/" + id + ".yml";
        // Asked through getResourceAsStream (BundledResources.open), never getResource: Lukittu's loader
        // keeps the jar in memory and answers only the stream, so getResource said "no layout" there and
        // the library's wardrobe was written in the plugin's place.
        boolean packaged = packaged(anchor, path);
        if (packaged) BundledFiles.refresh(plugin, anchor, path);
        return define(id, definition, packaged ? anchor : null);
    }

    /** Whether the jar {@code anchor} came from holds {@code path}. */
    static boolean packaged(Class<?> anchor, String path) {
        try (java.io.InputStream in = net.exylia.lib.config.internal.BundledResources.open(anchor.getClassLoader(), path)) {
            return in != null;
        } catch (java.io.IOException unreadable) {
            return false;
        }
    }

    private SpatialMenus define(String id, Consumer<SpatialMenu> definition) {
        return define(id, definition, null);
    }

    /**
     * @param shipped the class whose jar ships this menu's layout, or {@code null}: a server file that is
     *                still the library's generic wardrobe is then replaced by the jar's
     */
    private SpatialMenus define(String id, Consumer<SpatialMenu> definition, @Nullable Class<?> shipped) {
        SpatialMenu menu = new SpatialMenu(id);
        definition.accept(menu);
        ConfigFile<SpatialLayout> file = Configs.define(plugin, "spatial/" + id, SpatialLayout.class).load();
        if (shipped != null && isLibraryDefault(file.get(), menu)) {
            replaceLibraryDefault(id, shipped);
            file.reload();
        }
        layouts.put(id, file);
        menus.put(id, menu);
        SpatialSpots placed = spots;
        if (placed != null) placed.refresh();
        else if (new File(plugin.getDataFolder(), "spatial/spots.yml").isFile()) spots();
        return this;
    }

    /**
     * Whether a server's layout is the library's generic wardrobe rather than one of the plugin's: the
     * same panels reading the same lists ({@code categories}, {@code options}, carousel {@code items}),
     * none of which the menu fills. What 1.406.0 to 1.411.0 wrote in the plugin's place when the jar's
     * layout could not be seen (a Lukittu loader): every value was the server's from then on, so it was
     * never replaced. Positions are not compared, since those defaults moved between versions.
     */
    static boolean isLibraryDefault(SpatialLayout layout, SpatialMenu menu) {
        SpatialLayout generic = new SpatialLayout();
        if (!layout.panels().keySet().equals(generic.panels().keySet())) return false;
        if (!layout.carousel().source().equals(generic.carousel().source())) return false;
        java.util.Set<String> read = new java.util.HashSet<>();
        read.add(layout.carousel().source());
        for (Map.Entry<String, SpatialLayout.Panel> panel : generic.panels().entrySet()) {
            String source = layout.panels().get(panel.getKey()).content().source();
            if (!source.equals(panel.getValue().content().source())) return false;
            if (!source.isBlank()) read.add(source);
        }
        for (String source : read) {
            if (menu.sources.containsKey(source)) return false;
        }
        return true;
    }

    /** Sets a stale generic layout aside as {@code <id>.yml.library-default} and installs the jar's. */
    private void replaceLibraryDefault(String id, Class<?> anchor) {
        String path = "spatial/" + id + ".yml";
        java.nio.file.Path file = plugin.getDataFolder().toPath().resolve(path);
        try {
            java.nio.file.Files.move(file, file.resolveSibling(id + ".yml.library-default"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException failure) {
            net.exylia.lib.debug.Debug.of(plugin).warn("Could not replace " + path + ", which still holds the"
                    + " library's default layout instead of " + plugin.getName() + "'s: " + failure.getMessage());
            return;
        }
        BundledFiles.refresh(plugin, anchor, path);
        net.exylia.lib.debug.Debug.of(plugin).log(path + " held the library's default layout instead of "
                + plugin.getName() + "'s, so it was replaced; the old file was kept as " + id + ".yml.library-default.");
    }

    /** A menu's layout file, for a plugin that edits it or reads it; {@code null} when not registered. */
    public @Nullable ConfigFile<SpatialLayout> layout(@NotNull String id) {
        return layouts.get(id);
    }

    SpatialLayout layoutOrDefault(String id) {
        ConfigFile<SpatialLayout> file = layouts.get(id);
        return file == null ? new SpatialLayout() : file.get();
    }

    /** The spots menus open at, made on first use (their holograms go up then). */
    public @NotNull SpatialSpots spots() {
        SpatialSpots placed = spots;
        if (placed != null) return placed;
        synchronized (this) {
            if (spots == null) spots = new SpatialSpots(this);
            return spots;
        }
    }

    /** Opens a menu in front of the player (or at its nearest spot, for a PLACED layout). {@code false}: no such menu. */
    public boolean open(@NotNull Player player, @NotNull String id) {
        return open(player, id, null, false, Map.of());
    }

    /**
     * Opens a menu in front of the player with its state already holding {@code state}: what it is about
     * (the slot an editor edits, the item a detail page shows), read with {@link SpatialContext#get} from
     * {@code onOpen} on. A placed spot opens without it, so a menu reading it falls back to its own default.
     * {@code false}: no such menu.
     *
     * @since 1.423.0
     */
    public boolean open(@NotNull Player player, @NotNull String id, @NotNull Map<String, ?> state) {
        return open(player, id, null, false, Map.copyOf(Objects.requireNonNull(state, "state")));
    }

    /** Opens a menu at a spot: the player is walked onto it first. {@code false}: no such menu. */
    public boolean open(@NotNull Player player, @NotNull String id, @Nullable String spot) {
        return open(player, id, spot, false, Map.of());
    }

    /**
     * Opens a menu for an admin to move its pieces: look at one (it is outlined) and click it, then the
     * mouse wheel moves it, F changes the axis (across, up, forward, turn, width, height), sneak + wheel
     * changes the step, and a click saves it to the layout file and redraws. Sneak to leave.
     */
    public boolean edit(@NotNull Player admin, @NotNull String id) {
        return open(admin, id, null, true, Map.of());
    }

    /** Closes the player's menu, if this plugin opened it. */
    public void close(@NotNull Player player) {
        MenuSession session = OPEN.get(player.getUniqueId());
        if (session != null && session.menus == this) run(player, () -> close(session, true));
    }

    /** Whether the player has a menu of this plugin's open. */
    public boolean isOpen(@NotNull Player player) {
        MenuSession session = OPEN.get(player.getUniqueId());
        return session != null && session.menus == this;
    }

    /** The menu of this plugin's the player has open. */
    public @NotNull Optional<SpatialContext> context(@NotNull Player player) {
        MenuSession session = OPEN.get(player.getUniqueId());
        return session != null && session.menus == this ? Optional.of(session) : Optional.empty();
    }

    /**
     * Redraws the player's menu, if this plugin's is open: a purchase or an unlock that finished, a
     * change made from a command. Safe from any thread.
     *
     * @since 1.416.0
     */
    public void refresh(@NotNull Player player) {
        MenuSession session = OPEN.get(player.getUniqueId());
        if (session != null && session.menus == this) run(player, () -> {
            if (!session.isClosed()) session.refresh();
        });
    }

    /**
     * Redraws every menu of this plugin's that is open: after a reload, so a catalogue, a price or a
     * word that changed shows at once rather than on the next click. Safe from any thread.
     *
     * @since 1.416.0
     */
    public void refreshAll() {
        for (MenuSession session : OPEN.values()) {
            if (session.menus == this) refresh(session.viewer);
        }
    }

    /**
     * Registers a cosmetic catalogue: a {@link SpatialCatalog} declared on a new menu, its layout
     * {@code spatial/<id>.yml} shipped next to {@code anchor} as {@link #register(String, Class, Consumer)}
     * reads it.
     *
     * @since 1.416.0
     */
    public @NotNull SpatialMenus registerCatalog(@NotNull String id, @NotNull Class<?> anchor,
                                                 @NotNull Consumer<SpatialCatalog> catalog) {
        Objects.requireNonNull(catalog, "catalog");
        return register(id, anchor, menu -> {
            SpatialCatalog declared = new SpatialCatalog(this, menu);
            catalog.accept(declared);
            declared.install();
        });
    }

    /** Whether a player has any spatial menu open, whichever plugin opened it. */
    public static boolean inMenu(@NotNull Player player) {
        return OPEN.containsKey(player.getUniqueId());
    }

    private boolean open(Player player, String id, @Nullable String spotId, boolean editing, Map<String, ?> state) {
        SpatialMenu definition = menus.get(id);
        ConfigFile<SpatialLayout> file = layouts.get(id);
        if (definition == null || file == null) return false;
        run(player, () -> start(player, definition, file, spotId, editing, state));
        return true;
    }

    private void run(Player player, Runnable task) {
        if (tasks.isOwnedBy(player)) task.run();
        else tasks.runAtEntity(player, task);
    }

    private void start(Player player, SpatialMenu definition, ConfigFile<SpatialLayout> file, @Nullable String spotId,
                       boolean editing, Map<String, ?> state) {
        if (!player.isOnline()) return;
        SpatialLayout layout = file.get();
        if (!editing && !allowed(player, definition, layout.theme())) return;
        MenuSession open = OPEN.get(player.getUniqueId());
        if (open != null) open.menus.close(open, false);
        Optional<SpatialSpots.Spot> spot = Optional.empty();
        if (spotId != null) spot = spots().get(spotId);
        else if (layout.anchor().mode() == SpatialLayout.Anchor.Mode.PLACED && spots != null) {
            spot = spots().all().stream().filter(s -> s.menu().equals(definition.id()) && s.location() != null
                            && s.location().getWorld() == player.getWorld()
                            && s.location().distanceSquared(player.getLocation()) <= 64)
                    .min(java.util.Comparator.comparingDouble(s -> s.location().distanceSquared(player.getLocation())));
        }
        Location feet = player.getLocation();
        if (spot.isPresent() && spot.get().location() != null) {
            Location at = spot.get().location().clone();
            at.setPitch(0);
            boolean there = at.getWorld() == feet.getWorld() && at.distanceSquared(feet) < 0.09
                    && Math.abs(wrap(at.getYaw() - feet.getYaw())) < 2;
            double distance = layout.anchor().distance();
            if (there) build(player, definition, file, at, distance, editing, state);
            else walk(player, at, () -> build(player, definition, file, at, distance, editing, state));
            return;
        }
        // In front: the board square to the world (45° steps), standing on the ground, nearer when a wall is close.
        float yaw = Math.round(feet.getYaw() / 45f) * 45f;
        Location at = feet.clone();
        at.setYaw(yaw);
        at.setPitch(0);
        double distance = clearance(at, layout.anchor().distance());
        if (Math.abs(wrap(yaw - feet.getYaw())) < 2) {
            build(player, definition, file, at, distance, editing, state);
        } else {
            // Turned square to the board, so it opens in the middle of their view.
            Location facing = feet.clone();
            facing.setYaw(yaw);
            walk(player, facing, () -> build(player, definition, file, at, distance, editing, state));
        }
    }

    /** The menu's permission and guard; a refusal is a sound and, when the guard gives one, a message. */
    private static boolean allowed(Player player, SpatialMenu definition, SpatialTheme theme) {
        String refusal = null;
        if (definition.permission != null && !player.hasPermission(definition.permission)) refusal = "";
        else if (definition.guard != null) refusal = definition.guard.apply(player);
        if (refusal == null) return true;
        net.exylia.lib.stage.StageLook.play(player, theme.errorSound());
        if (!refusal.isBlank()) player.sendMessage(net.exylia.lib.text.Text.component(refusal));
        return false;
    }

    private void walk(Player player, Location to, Runnable then) {
        Teleports.of(plugin).to(player, to).warmup(0).start().future().thenAccept(result -> {
            if (result.isSuccess()) tasks.runAtEntity(player, then);
        });
    }

    private static float wrap(float degrees) {
        float wrapped = degrees % 360;
        if (wrapped > 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }

    /** How far ahead the board may stand: nearer than {@code wanted} when a block is in the way, never under 2. */
    private static double clearance(Location feet, double wanted) {
        World world = feet.getWorld();
        if (world == null) return wanted;
        Location eyes = feet.clone().add(0, 1.62, 0);
        Vector ahead = feet.getDirection().setY(0).normalize();
        RayTraceResult hit = world.rayTraceBlocks(eyes, ahead, wanted + 0.4, FluidCollisionMode.NEVER, true);
        if (hit == null) return wanted;
        return Math.clamp(hit.getHitPosition().distance(eyes.toVector()) - 0.4, Math.min(2.0, wanted), wanted);
    }

    private void build(Player player, SpatialMenu definition, ConfigFile<SpatialLayout> file, Location anchor,
                       double distance, boolean editing, Map<String, ?> state) {
        if (!player.isOnline()) return;
        MenuSession open = OPEN.get(player.getUniqueId());
        if (open != null) open.menus.close(open, false);
        SpatialLayout layout = file.get();
        SpatialTheme theme = layout.theme();
        SpatialPointer pointer = input.attach(player).sounds(theme.hoverSound(), theme.selectSound());
        MenuSession session = new MenuSession(this, definition, layout, player, anchor, distance, pointer);
        if (editing) session.edit = new EditMode(session, file);
        OPEN.put(player.getUniqueId(), session);
        pointer.onSneak(() -> close(session, true))
                .onLeave(() -> close(session, true))
                .onTick(session::tick)
                .onEnd(end -> close(session, false));
        if (layout.freeze()) Packets.of(plugin).movement().freeze(player);
        Overlays.of(plugin).show(player, input.hotbar(Item.of(theme.leaveMaterial()).name(session.fill(theme.leaveName()))
                .lore(theme.leaveLore().stream().map(session::fill).toList()).build()));
        if (layout.hideOthers()) hide(player);
        state.forEach(session::set);
        session.open();
    }

    /** Ends a session: deflating it, or at once when the player is leaving. */
    void close(MenuSession session, boolean animated) {
        if (session.isClosed()) return;
        Player player = session.viewer;
        OPEN.remove(player.getUniqueId(), session);
        session.end();
        if (session.edit != null) session.edit.close();
        session.pointer.detach();
        boolean online = player.isOnline();
        session.takeDown(animated && online);
        if (animated && online) net.exylia.lib.stage.StageLook.play(player, session.theme.closeSound());
        if (online) {
            if (session.layout.freeze()) Packets.of(plugin).movement().unfreeze(player);
            PluginOverlays overlays = Overlays.of(plugin);
            if (overlays.showing(player).filter(shown -> shown.id().equals(SpatialInput.HOTBAR_ID)).isPresent()) {
                overlays.hide(player);
            }
        }
        unhide(player);
    }

    // ── Hiding ────────────────────────────────────────────────────────────

    /** The library's visibility rule: a player in a menu that hides others sees nobody and is seen by nobody. */
    static boolean allows(Player viewer, Player target) {
        if (viewer.getUniqueId().equals(target.getUniqueId())) return true;
        return !HIDDEN.contains(viewer.getUniqueId()) && !HIDDEN.contains(target.getUniqueId());
    }

    /**
     * Hides everyone from a player and the player from everyone, or ends it, under the library's rule:
     * for an in-world screen built outside {@code SpatialMenus} (the crate opening) that follows the
     * menus' {@code hide-others}. Every {@code true} needs its {@code false}.
     *
     * @since 1.416.0
     */
    public static void hideOthers(@NotNull Player player, boolean hidden) {
        if (hidden) hide(player);
        else unhide(player);
    }

    private static void hide(Player player) {
        if (HIDDEN.add(player.getUniqueId())) refreshAround(player);
    }

    private static void unhide(Player player) {
        if (HIDDEN.remove(player.getUniqueId())) refreshAround(player);
    }

    private static void refreshAround(Player player) {
        Plugin owner = library;
        if (owner == null) return;
        Visibility visibility = Packets.of(owner).visibility();
        visibility.refresh(player);
        // Whom they see is everyone else's visibility, so everyone else is looked at again too.
        for (Player other : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (!other.getUniqueId().equals(player.getUniqueId())) visibility.refresh(other);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Installs the input listener and the hiding rule, under the library's own name so no plugin's
     * own visibility rule is replaced. Called by the library on enable; consumers never call it.
     */
    public static void init(@NotNull Plugin plugin) {
        library = plugin;
        plugin.getServer().getPluginManager().registerEvents(new SpatialInput.Listen(), plugin);
        Packets.of(plugin).visibility().rule(SpatialMenus::allows);
    }

    /** Closes a disabled plugin's menus and ends its pointers. Called by the library. */
    public static void release(@NotNull Plugin plugin) {
        SpatialMenus menus = BY_PLUGIN.remove(plugin.getName());
        if (menus != null) {
            for (MenuSession session : List.copyOf(OPEN.values())) {
                if (session.menus == menus) {
                    try {
                        menus.close(session, false);
                    } catch (RuntimeException ignored) {
                        // Off the player's region on Folia: its displays and NPCs are released next anyway.
                        OPEN.remove(session.viewer.getUniqueId(), session);
                        HIDDEN.remove(session.viewer.getUniqueId());
                    }
                }
            }
            SpatialSpots placed = menus.spots;
            if (placed != null) placed.close();
        }
        SpatialInput.release(plugin);
    }

    /** Forgets everything, for shutdown. Called by the library. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
        OPEN.clear();
        HIDDEN.clear();
        SpatialInput.releaseAll();
        library = null;
    }
}
