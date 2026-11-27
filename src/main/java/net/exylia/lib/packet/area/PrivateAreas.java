package net.exylia.lib.packet.area;

import net.exylia.lib.packet.Packets;
import net.exylia.lib.packet.Visibility;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Places where each player plays their own copy (a private farm, a base on a shared hull): two players
 * in the same private area do not see each other or collide, since they would only stand in the way of
 * things the other cannot see. Players in different places, or one in and one out, are drawn faint,
 * see-through, so the hub still reads busy. Everybody stays on the tab list.
 * <pre>{@code
 * PrivateAreas areas = PrivateAreas.of(this);        // once: it is this plugin's visibility rule
 * areas.together((viewer, target) -> visiting(viewer, target));
 * areas.place(player, "plot");                       // on move; null when they left every area
 * }</pre>
 * <p>It registers the plugin's {@link Visibility#rule(net.exylia.lib.packet.VisibilityRule, boolean)
 * rule} and {@link Visibility#ghosts ghosts}, so a plugin using it has no rule of its own. Players are
 * placed by the plugin, which knows its areas; a player's place is forgotten when they leave.
 *
 * @since 1.676.0
 */
public final class PrivateAreas implements Listener {

    private final Visibility visibility;
    private final Map<UUID, String> placeOf = new ConcurrentHashMap<>();
    private volatile BiPredicate<UUID, UUID> together = (viewer, target) -> false;

    private PrivateAreas(Plugin plugin) {
        this.visibility = Packets.of(plugin).visibility();
        visibility.rule((viewer, target) -> {
            String here = placeOf.get(viewer.getUniqueId());
            return here == null || !here.equals(placeOf.get(target.getUniqueId()))
                    || together.test(viewer.getUniqueId(), target.getUniqueId());
        }, true);
        visibility.ghosts((viewer, target) ->
                !Objects.equals(placeOf.get(viewer.getUniqueId()), placeOf.get(target.getUniqueId())));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** A plugin's private areas. One per plugin, on enable: it takes the plugin's visibility rule. */
    public static @NotNull PrivateAreas of(@NotNull Plugin plugin) {
        return new PrivateAreas(plugin);
    }

    /**
     * Which pairs in the same area still see each other (a visitor and the owner they visit). Asked on
     * a packet thread: read memory only. {@link #refresh} the pair when the answer changes.
     */
    public void together(@NotNull BiPredicate<UUID, UUID> rule) {
        this.together = rule;
    }

    /**
     * Puts a player in an area, or in none with {@code null}. When that changes their place, who sees
     * them and whom they see is asked again, and they collide only outside every area. Their thread.
     *
     * @return whether their place changed
     */
    public boolean place(@NotNull Player player, @Nullable String area) {
        String before = area == null ? placeOf.remove(player.getUniqueId()) : placeOf.put(player.getUniqueId(), area);
        if (Objects.equals(before, area)) return false;
        player.setCollidable(area == null);
        refresh(player);
        return true;
    }

    /** The area a player is in, or {@code null}. Any thread. */
    public @Nullable String area(@NotNull UUID player) {
        return placeOf.get(player);
    }

    /** Asks again who sees this player and whom they see: after they moved place or a visit began or ended. */
    public void refresh(@NotNull Player player) {
        visibility.refresh(player);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != player) visibility.refresh(player, other);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (placeOf.remove(event.getPlayer().getUniqueId()) != null) event.getPlayer().setCollidable(true);
    }
}
