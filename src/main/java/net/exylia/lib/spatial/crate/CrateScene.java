package net.exylia.lib.spatial.crate;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.display.Displays;
import net.exylia.lib.spatial.SpatialMenus;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-world crate opening: a crate built from displays in front of the player, a reel of rewards
 * sliding past a marker and slowing onto the prize, the reveal (its icon, name and rarity, a burst and a
 * sound scaled to the rarity) and the prize tried on where the crate stood, then "open another" and
 * "done". Packets only, no resource pack; the look is {@code plugins/<Plugin>/spatial/crate.yml}
 * ({@link CrateSceneLayout}).
 *
 * <pre>{@code
 * CrateScene.of(plugin).play(player, show);   // false: cannot be drawn here, show it another way
 * }</pre>
 *
 * <p>The scene decides nothing: the {@link CrateShow} arrives with its prizes drawn and paid for, and
 * {@link CrateShow#reveal} hands each over as its reel lands, or at once when the player sneaks to skip.
 * While it plays the player is held still, sees nobody and is seen by nobody (both per layout) and holds
 * only the leave item, as in a spatial menu; it is one per player, and opening a spatial menu ends it.
 *
 * <p>Most plugins never call this: their crate does, with {@code CrateOpening.SPATIAL}.
 *
 * @since 1.416.0
 */
public final class CrateScene {

    private static final Map<String, CrateScene> BY_PLUGIN = new ConcurrentHashMap<>();
    static final Map<UUID, SceneSession> PLAYING = new ConcurrentHashMap<>();
    /** Players whose scene waits for them to land. */
    private static final java.util.Set<UUID> WAITING = ConcurrentHashMap.newKeySet();

    final Plugin plugin;
    final TaskScheduler tasks;
    private volatile ConfigFile<CrateSceneLayout> layout;

    private CrateScene(Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
    }

    /** The plugin's crate scene, made on first use. */
    public static @NotNull CrateScene of(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> new CrateScene(plugin));
    }

    /** {@code plugins/<Plugin>/spatial/crate.yml}, written with the defaults the first time it is asked for. */
    public @NotNull ConfigFile<CrateSceneLayout> layout() {
        ConfigFile<CrateSceneLayout> file = layout;
        if (file != null) return file;
        synchronized (this) {
            if (layout == null) layout = Configs.define(plugin, "spatial/crate", CrateSceneLayout.class).load();
            return layout;
        }
    }

    /** Whether a scene can be drawn on this server at all: displays need PacketEvents. */
    public boolean available() {
        return Displays.of(plugin).isSupported();
    }

    /**
     * Whether the scene can be drawn for this player: {@link #available()} and a client of 1.21.4 or newer
     * ({@link SpatialMenus#supports}). An older client gets the inventory opening.
     *
     * @since 1.431.0
     */
    public boolean available(@NotNull Player player) {
        return available() && SpatialMenus.supports(player);
    }

    /**
     * Plays an opening in front of the player, on their thread. Closes a spatial menu of this plugin's
     * they had open (the wardrobe the crate was opened from) and a scene already playing.
     *
     * @return {@code false} when it cannot be drawn: no PacketEvents, a client older than 1.21.4, the player gone, or another
     *         plugin's spatial menu in the way. Nothing of {@code show} was asked then, so the caller
     *         shows it another way.
     */
    public boolean play(@NotNull Player player, @NotNull CrateShow show) {
        if (!player.isOnline() || !available(player) || show.prizes().isEmpty()) return false;
        SceneSession open = PLAYING.get(player.getUniqueId());
        if (open != null) open.close(false);
        SpatialMenus.of(plugin).close(player);
        if (SpatialMenus.inMenu(player)) return false;
        player.closeInventory();
        if (!net.exylia.lib.stage.Footing.settled(player)) {
            // In mid-air the scene would hang where they happen to be: it plays where they land. Given up
            // (the void, an elytra), every prize is handed over as a skipped scene hands it.
            WAITING.add(player.getUniqueId());
            net.exylia.lib.stage.Footing.landed(plugin, player).thenAccept(landed -> {
                WAITING.remove(player.getUniqueId());
                if (!player.isOnline()) {
                    // Paid by the caller's quit path, as a scene the player left.
                    show.ended(true);
                    return;
                }
                if (!landed || SpatialMenus.inMenu(player) || PLAYING.containsKey(player.getUniqueId())) {
                    for (int i = 0; i < show.prizes().size(); i++) show.reveal(i);
                    show.ended(false);
                    return;
                }
                start(player, show);
            });
            return true;
        }
        start(player, show);
        return true;
    }

    private void start(Player player, CrateShow show) {
        CrateSceneLayout look = layout().get();
        Location feet = player.getLocation();
        feet.setPitch(0);
        double distance = look.clearView() ? look.distance() : clearance(feet, look.distance());
        SceneSession session = new SceneSession(this, player, show, look, feet, distance);
        PLAYING.put(player.getUniqueId(), session);
        session.open();
    }

    /** Ends the player's scene, handing over every prize not revealed yet. */
    public void close(@NotNull Player player) {
        SceneSession open = PLAYING.get(player.getUniqueId());
        if (open == null || open.owner != this) return;
        if (tasks.isOwnedBy(player)) open.skip();
        else tasks.runAtEntity(player, open::skip);
    }

    /** Whether the player is watching a crate opening of any plugin's. */
    public static boolean isPlaying(@NotNull Player player) {
        return PLAYING.containsKey(player.getUniqueId()) || WAITING.contains(player.getUniqueId());
    }

    /** How far ahead the board may stand: nearer than {@code wanted} when a block is in the way, never under 2. */
    private static double clearance(Location feet, double wanted) {
        World world = feet.getWorld();
        if (world == null) return wanted;
        Location eyes = feet.clone().add(0, SceneSession.EYES, 0);
        Vector ahead = feet.getDirection().setY(0).normalize();
        RayTraceResult hit = world.rayTraceBlocks(eyes, ahead, wanted + 0.6, FluidCollisionMode.NEVER, true);
        if (hit == null) return wanted;
        return Math.clamp(hit.getHitPosition().distance(eyes.toVector()) - 0.6, Math.min(2.0, wanted), wanted);
    }
}
