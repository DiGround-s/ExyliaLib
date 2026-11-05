package net.exylia.lib.spatial;

import net.exylia.lib.config.BundledFiles;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.overlay.Overlays;
import net.exylia.lib.overlay.PluginOverlays;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.packet.Visibility;
import net.exylia.lib.spatial.internal.SceneShield;
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
        if (packaged) {
            // Keys a newer library added with its own value before this jar shipped one: offered, not kept.
            // ponytail: the keys added since 1.442.0 only; extend the map when a later version adds keys a jar sets.
            BundledFiles.libraryWrote(plugin, anchor, path, LIBRARY_WROTE);
            // A file the record filled in and nobody edited still counts as untouched for a new menu-version.
            BundledFiles.recordWrote(plugin, anchor, path, SpatialLayout.class, EARLIER_DEFAULTS);
            BundledFiles.refresh(plugin, anchor, path);
        }
        return define(id, definition, packaged ? anchor : null);
    }

    /** What the layout record writes for the keys it gained in 1.442.0, before a plugin's jar ships them. */
    private static final Map<String, Object> LIBRARY_WROTE = Map.of(
            "anchor.clear-view", true,
            "preview.facing", "FRONT",
            "preview.turn-controls", false,
            "preview.turn-hint", SpatialLayout.Preview.TURN_HINT,
            "preview.turn-pause", 8.0);

    /** What the layout record wrote for a key before its default changed: still the library's on disk. */
    private static final Map<String, List<Object>> EARLIER_DEFAULTS = Map.of(
            "preview.skins", List.of(List.of("others", "defaults")),
            "theme.title-scale", List.of(0.5),
            "theme.text-scale", List.of(0.36),
            "theme.small-scale", List.of(0.27),
            "theme.select-sound", List.of("minecraft:block.note_block.hat|1.0|1.0"),
            "theme.open-sound", List.of("minecraft:block.ender_chest.open|1.0|1.4"),
            "theme.intro-line", List.of("{muted}Click to continue {letters_black}· {muted}Sneak to skip {letters_black}» "
                    + "{info}%step%{letters_black}/{info}%steps%",
                    "%dots%\n{muted}Click to continue {letters_black}· {muted}sneak to skip",
                    "%dots%\n{letters_black}Click to continue · sneak to skip"));

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
        LayoutEditor.register(this, id, file, menu, bundled(id, menu, shipped));
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

    /** Where the layout the plugin ships for menu {@code id} is read from: what "reset to default" brings back. */
    private @Nullable java.util.function.Supplier<java.io.InputStream> bundled(String id, SpatialMenu menu,
                                                                             @Nullable Class<?> shipped) {
        String path = "spatial/" + id + ".yml";
        if (shipped != null) {
            return () -> net.exylia.lib.config.internal.BundledResources.open(shipped.getClassLoader(), path);
        }
        String source = menu.stageSource;
        if (source != null) {
            return () -> {
                try (java.io.InputStream in = net.exylia.lib.config.internal.BundledResources.open(SpatialMenus.class.getClassLoader(), CENTRE_STAGE)) {
                    if (in == null) return null;
                    String layout = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                            .replace("  source: catalog\n", "  source: " + source + "\n");
                    return new java.io.ByteArrayInputStream(layout.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException unreadable) {
                    return null;
                }
            };
        }
        return () -> plugin.getResource(path);
    }

    /**
     * Draws every open copy of menu {@code id} again from its layout file, after the web editor wrote it: in place
     * when it still stands where it stood (the same anchor, hiding and freezing), closed otherwise, since the space
     * cleared and sealed for it was measured for the old one. Edit mode closes: its own copy of the layout is stale. Since 1.464.0.
     */
    void relive(String id) {
        ConfigFile<SpatialLayout> file = layouts.get(id);
        if (file == null) return;
        for (MenuSession session : OPEN.values()) {
            if (session.menus != this || !session.definition.id().equals(id)) continue;
            run(session.viewer, () -> {
                if (session.isClosed()) return;
                SpatialLayout next = file.get();
                SpatialLayout now = session.layout;
                boolean stands = now.anchor().equals(next.anchor()) && now.hideOthers() == next.hideOthers()
                        && now.freeze() == next.freeze();
                if (stands && session.edit == null) session.rebuild(next);
                else close(session, true);
            });
        }
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
            menu.catalog = true;
            menu.catalogue = declared;
            // A plugin that ships no layout gets the library's centre stage rather than the generic wardrobe.
            if (!packaged(anchor, "spatial/" + id + ".yml")) {
                menu.stageSource = declared.primarySource();
                installCentreStage(id, declared.primarySource());
            }
        });
    }

    /** The library's catalogue layout ({@code spatial/centre-stage.yml}), its shelf reading {@code source}. */
    public static final String CENTRE_STAGE = "spatial/centre-stage.yml";

    private void installCentreStage(String id, String source) {
        java.nio.file.Path file = plugin.getDataFolder().toPath().resolve("spatial/" + id + ".yml");
        if (java.nio.file.Files.exists(file)) return;
        try (java.io.InputStream in = net.exylia.lib.config.internal.BundledResources.open(SpatialMenus.class.getClassLoader(), CENTRE_STAGE)) {
            if (in == null) return;
            String layout = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("  source: catalog\n", "  source: " + source + "\n");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, layout);
        } catch (java.io.IOException failure) {
            net.exylia.lib.debug.Debug.of(plugin).warn("Could not write spatial/" + id + ".yml: " + failure.getMessage());
        }
    }

    /**
     * Whether the player's client can draw a spatial menu (and the crate scene): 1.21.4 or newer, as
     * PacketEvents saw it at login. An unknown version counts as the server's. Every {@code open} asks
     * this itself and runs the menu's {@link SpatialMenu#fallback} instead; plugins ask it to pick a
     * screen up front.
     *
     * @since 1.431.0
     */
    public static boolean supports(@NotNull Player player) {
        // A spatial menu is drawn with packets; without PacketEvents every menu takes its fallback.
        if (!net.exylia.lib.packet.Packets.isAvailable()) return false;
        return net.exylia.lib.packet.ClientVersions.atLeast(player,
                com.github.retrooper.packetevents.protocol.player.ClientVersion.V_1_21_4);
    }

    // ── Guided tour ───────────────────────────────────────────────────────

    /** Where a player's "tour seen" mark for one menu is kept: their own data, under this plugin's name. */
    private org.bukkit.NamespacedKey introKey(String id) {
        String safe = id.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return new org.bukkit.NamespacedKey(plugin, "spatial_intro_" + safe);
    }

    /** Whether the player has seen the guided tour of this plugin's menu {@code id}. Read on the player's thread. */
    boolean introSeen(Player player, String id) {
        return player.getPersistentDataContainer().has(introKey(id), org.bukkit.persistence.PersistentDataType.BYTE);
    }

    void markIntroSeen(Player player, String id) {
        player.getPersistentDataContainer().set(introKey(id), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
    }

    /**
     * Forgets that the player has seen the guided tour of menu {@code id} (a layout's {@code intro}), so it shows
     * again the next time they open it: a "show me again" option or an admin's reset. The mark lives in the
     * player's own data on this server, per plugin and menu. Safe from any thread.
     *
     * @since 1.452.0
     */
    public void resetIntro(@NotNull Player player, @NotNull String id) {
        Objects.requireNonNull(id, "id");
        run(player, () -> player.getPersistentDataContainer().remove(introKey(id)));
    }

    /**
     * Shows the guided tour of the player's open menu now, if it is this plugin's and its layout has one: what
     * a button with action {@code intro} does. Safe from any thread.
     *
     * @since 1.452.0
     */
    public void showIntro(@NotNull Player player) {
        MenuSession session = OPEN.get(player.getUniqueId());
        if (session != null && session.menus == this) run(player, session::startTour);
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
        if (!supports(player)) {
            // Too old a client for item displays as the menu draws them: its inventory screen, or a refusal.
            if (!editing && definition.fallback != null) definition.fallback.accept(player);
            else net.exylia.lib.stage.StageLook.play(player, layout.theme().errorSound());
            return;
        }
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
        boolean placed = spot.isPresent() && spot.get().location() != null;
        if (!placed && !net.exylia.lib.stage.Footing.settled(player)) {
            // In mid-air the board would hang where they happen to be, and the hold fight their fall:
            // it opens where they land instead.
            net.exylia.lib.stage.Footing.landed(plugin, player).thenAccept(landed -> {
                if (landed) start(player, definition, file, spotId, editing, state);
                else net.exylia.lib.stage.StageLook.play(player, layout.theme().errorSound());
            });
            return;
        }
        if (placed) {
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
        // A large board facing a wall would clear a hole through it: the nearest heading with room for it instead.
        if (layout.anchor().clearView()) yaw = roomiest(feet, yaw, layout.anchor().distance());
        Location at = feet.clone();
        at.setYaw(yaw);
        at.setPitch(0);
        // With the view cleared, nothing stands between them and the board: it keeps its distance.
        double distance = layout.anchor().clearView() ? layout.anchor().distance() : clearance(at, layout.anchor().distance());
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

    /**
     * The square heading (45° steps) nearest {@code yaw} with room for a board {@code distance} ahead: the way
     * the player faces when nothing stands there, else the next one round either side, else the way with the
     * most room. The view is cleared whichever it is, so a cramped spot still opens; it only clears less.
     */
    static float roomiest(Location feet, float yaw, double distance) {
        World world = feet.getWorld();
        if (world == null) return yaw;
        float best = yaw;
        double most = -1;
        for (int step : new int[]{0, 1, -1, 2, -2, 3, -3, 4}) {
            float heading = yaw + step * 45f;
            double room = room(feet, heading, distance);
            if (room >= distance - 0.01) return heading;
            // A little room more is not worth turning round for: only a clearly better heading wins.
            if (room > most + 0.75) {
                most = room;
                best = heading;
            }
        }
        return best;
    }

    /** How far ahead the way {@code yaw} looks is clear at eye height, across the middle and both sides of the view. */
    private static double room(Location feet, float yaw, double distance) {
        World world = feet.getWorld();
        Location eyes = feet.clone().add(0, 1.62, 0);
        double least = distance;
        for (double side : new double[]{0, -22, 22}) {
            Location look = feet.clone();
            look.setYaw((float) (yaw + side));
            look.setPitch(0);
            Vector ahead = look.getDirection().setY(0).normalize();
            RayTraceResult hit = world.rayTraceBlocks(eyes, ahead, distance, FluidCollisionMode.NEVER, true);
            if (hit != null) least = Math.min(least, hit.getHitPosition().distance(eyes.toVector()));
        }
        return least;
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
        // First, so everything the menu draws from here on is its own and nothing else stands in it.
        SceneShield shield = shield(player, layout, anchor, distance);
        SpatialPointer pointer = input.attach(player).sounds(theme.hoverSound(), theme.selectSound());
        MenuSession session = new MenuSession(this, definition, layout, player, anchor, distance, pointer);
        session.shield = shield;
        if (editing) session.edit = new EditMode(session, file);
        OPEN.put(player.getUniqueId(), session);
        // Sneaking leaves; during the guided tour it skips the tour instead.
        pointer.onSneak(() -> {
            if (!session.skipTour()) close(session, true);
        })
                .onLeave(() -> close(session, true))
                .onTick(session::tick)
                .onEnd(end -> close(session, false));
        if (layout.freeze()) Packets.of(plugin).movement().freeze(player);
        // Nothing in the hotbar: the menu owns the wheel and the number keys, so a leave item there could
        // never be reached. The exit icon and sneaking leave.
        Overlays.of(plugin).show(player, input.hotbar());
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
        if (session.shield != null) session.shield.lower();
    }

    /**
     * The space the menu stands in, from the layout: as wide as its widest piece, as high as its highest,
     * from the eyes to just behind the board. Every entity in it (all of them with {@code hide-others})
     * is kept out of sight, and with {@code clear-view} every block in it shown as air.
     */
    private static SceneShield shield(Player player, SpatialLayout layout, Location anchor, double distance) {
        double half = 0.6;
        double top = 0.4;
        for (SpatialLayout.Panel panel : layout.panels().values()) {
            half = Math.max(half, Math.abs(panel.across()) + panel.width() / 2);
            top = Math.max(top, panel.up() + panel.height() / 2);
        }
        SpatialLayout.Carousel carousel = layout.carousel();
        if (carousel.present()) {
            // An arc's page arrows stand a step past its ends.
            half = Math.max(half, Math.abs(carousel.at().get(0)) + carousel.centreGap() / 2
                    + carousel.slots() / 2.0 * carousel.spacing() + (carousel.arc() ? carousel.spacing() * 1.5 : 0));
        }
        Plugin owner = library;
        if (owner == null) return null;
        SpatialLayout.Backdrop backdrop = layout.backdrop();
        if (backdrop.present()) {
            half = Math.max(half, Math.abs(backdrop.at().get(0)) + backdrop.size().get(0) / 2 + backdrop.feather());
            top = Math.max(top, backdrop.at().get(1) + backdrop.size().get(1) / 2 + backdrop.feather());
        }
        // As deep as the backdrop behind the board, and as high as the board stands once kept above the floor.
        double deeper = backdrop.present() ? Math.max(0, -backdrop.at().get(2)) + 0.1 : 0;
        return SceneShield.raise(owner, player, anchor, distance + deeper, half,
                top + layout.liftOverFloor(1.62, MenuSession.FLOOR_MARGIN), layout.anchor().clearView(), layout.hideOthers());
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
                        if (session.shield != null) session.shield.lower();
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
