package net.exylia.lib.util.mob.internal;

import net.exylia.lib.effect.internal.LiveBars;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The boss bar of every mob with {@link net.exylia.lib.util.mob.MobFlag#BOSS_BAR}:
 * its name and how much of it is left, shown to whoever is near it, recoloured
 * as its fight moves through its phases.
 *
 * <p>Refreshed once a second and whenever it is hurt, healed or renamed, on the
 * mob's thread; a bar is only sent when something on it changed.
 */
final class MobBars {

    /** How near a player must come to be shown the bar. */
    static final double RANGE = 40;
    /** How far a player already shown it may go before it is taken away. */
    static final double KEEP = 48;

    private static final class Bar {
        final Map<UUID, Player> viewers = new HashMap<>();
        Component title;
        float progress = -1;
        int phase = -1;
    }

    private final Map<UUID, Bar> bars = new ConcurrentHashMap<>();

    /**
     * Brings a boss's bar up to date.
     *
     * @param entity   the boss
     * @param title    what the bar says: the boss's name as it is drawn
     * @param progress how much of it is left, {@code 0-1}
     * @param phase    its fight phase, {@code 1} at the start
     */
    void update(LivingEntity entity, Component title, float progress, int phase) {
        Bar bar = bars.computeIfAbsent(entity.getUniqueId(), ignored -> new Bar());
        float left = Math.clamp(progress, 0f, 1f);
        String colour = colour(phase);
        Location at = entity.getLocation();
        Map<UUID, Player> near = new HashMap<>();
        for (Entity nearby : entity.getNearbyEntities(KEEP, KEEP, KEEP)) {
            if (!(nearby instanceof Player player) || !player.isOnline()) continue;
            double reach = bar.viewers.containsKey(player.getUniqueId()) ? KEEP : RANGE;
            if (player.getLocation().distanceSquared(at) <= reach * reach) {
                near.put(player.getUniqueId(), player);
            }
        }
        for (Iterator<Map.Entry<UUID, Player>> shown = bar.viewers.entrySet().iterator(); shown.hasNext(); ) {
            Map.Entry<UUID, Player> viewer = shown.next();
            if (!near.containsKey(viewer.getKey())) {
                if (viewer.getValue().isOnline()) LiveBars.hide(viewer.getValue(), id(entity.getUniqueId(), viewer.getKey()));
                shown.remove();
            }
        }
        boolean titleChanged = !Objects.equals(title, bar.title);
        boolean moved = Math.abs(left - bar.progress) > 1e-4;
        boolean recoloured = phase != bar.phase;
        for (Player player : near.values()) {
            UUID id = id(entity.getUniqueId(), player.getUniqueId());
            if (bar.viewers.put(player.getUniqueId(), player) == null) {
                LiveBars.show(player, id, title, left, colour, "progress");
                continue;
            }
            if (titleChanged || moved) LiveBars.update(player, id, title, left, titleChanged);
            if (recoloured) LiveBars.style(player, id, colour, "progress");
        }
        bar.title = title;
        bar.progress = left;
        bar.phase = phase;
    }

    /** Takes a boss's bar off everybody: it died, broke, expired or was removed. */
    void hide(UUID entity) {
        Bar bar = bars.remove(entity);
        if (bar == null) return;
        for (Map.Entry<UUID, Player> viewer : bar.viewers.entrySet()) {
            if (viewer.getValue().isOnline()) LiveBars.hide(viewer.getValue(), id(entity, viewer.getKey()));
        }
    }

    /** Takes every bar off everybody. */
    void clear() {
        for (UUID entity : bars.keySet().toArray(UUID[]::new)) hide(entity);
    }

    /** The colour of a phase: purple, then pink, then red from the third on. */
    static String colour(int phase) {
        return phase <= 1 ? "purple" : phase == 2 ? "pink" : "red";
    }

    /** One bar per boss per player, so the fallback path can take each one down again. */
    static UUID id(UUID entity, UUID player) {
        return UUID.nameUUIDFromBytes(("exylia-mob-bar:" + entity + ":" + player).getBytes(StandardCharsets.UTF_8));
    }
}
