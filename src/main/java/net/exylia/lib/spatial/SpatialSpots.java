package net.exylia.lib.spatial;

import net.exylia.lib.hologram.HologramConfig;
import net.exylia.lib.stage.PlacedTables;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Places in the world where a spatial menu opens: a small clickable hologram an admin set down, kept
 * in {@code plugins/<Plugin>/spatial/spots.yml}. Clicking it walks the player onto the spot (through
 * the teleport module, no countdown) and opens the menu there, facing the way the spot faces; they
 * stay where they are when it closes. Built on {@link PlacedTables}.
 *
 * <pre>{@code
 * menus.spots().place("trims-1", "trims", admin.getLocation());  // where the player will stand
 * menus.spots().nearest(admin.getLocation(), 4).ifPresent(spot -> menus.spots().remove(spot.id()));
 * }</pre>
 *
 * <p>Safe from any thread.
 *
 * @since 1.406.0
 */
public final class SpatialSpots {

    /** How far in front of a spot its hologram stands, in blocks. */
    private static final double AHEAD = 1.6;

    /** One spot: the menu it opens and where the player stands for it. */
    public record Spot(@NotNull String id, @NotNull String menu, @Nullable Location location) {
    }

    private final PlacedTables places;

    SpatialSpots(SpatialMenus menus) {
        // Its own hologram namespace: a plugin with tables of its own may give one the same id as a spot.
        this.places = new PlacedTables(menus.plugin(), "spatial/spots.yml", AHEAD, new PlacedTables.Hooks() {
            @Override
            public @NotNull HologramConfig look(@NotNull PlacedTables.Placed table) {
                // Every viewer their own copy: placeholders in the lines resolve per viewer.
                HologramConfig c = PlacedTables.look(1.2);
                return new HologramConfig(c.enabled(), c.type(), c.lines(), c.item(), c.block(), true, c.viewDistance(),
                        c.offsetX(), c.offsetY(), c.offsetZ(), c.properties(), c.config(), c.click(), c.pages(),
                        c.pageInterval());
            }

            @Override
            public @NotNull List<String> lines(@NotNull PlacedTables.Placed table, @Nullable UUID occupant) {
                SpatialLayout layout = menus.layoutOrDefault(table.game());
                return layout.theme().spotLines().stream().map(line -> line.replace("%title%", layout.title())).toList();
            }

            @Override
            public void clicked(@NotNull Player player, @NotNull PlacedTables.Placed table) {
                menus.open(player, table.game(), table.id());
            }
        }, "spatial-spot");
    }

    private static Spot spot(PlacedTables.Placed placed) {
        return new Spot(placed.id(), placed.game(), placed.spot());
    }

    /** Sets a spot down where the player will stand, facing the way the menu should face, or moves the one with that id. */
    public @NotNull Spot place(@NotNull String id, @NotNull String menu, @NotNull Location location) {
        return spot(places.place(id, menu, location));
    }

    /** Takes a spot away; {@code false} when there was none. */
    public boolean remove(@NotNull String id) {
        return places.remove(id);
    }

    public @NotNull Optional<Spot> get(@NotNull String id) {
        return places.get(id).map(SpatialSpots::spot);
    }

    /** The spot nearest {@code at} within {@code radius} blocks. */
    public @NotNull Optional<Spot> nearest(@NotNull Location at, double radius) {
        return places.nearest(at, radius).map(SpatialSpots::spot);
    }

    public @NotNull List<Spot> all() {
        return places.all().stream().map(SpatialSpots::spot).toList();
    }

    /** A free id for a new spot of a menu ({@code trims-1}, {@code trims-2}...). */
    public @NotNull String nextId(@NotNull String menu) {
        return places.nextId(menu);
    }

    /** Puts the holograms up again, after a reload or a world that loaded since. */
    public void refresh() {
        places.refresh();
    }

    void close() {
        places.close();
    }
}
