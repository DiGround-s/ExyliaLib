package net.exylia.lib.hologram.internal;

import net.exylia.lib.hologram.Hologram;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A hologram that shows nothing.
 *
 * <p>Returned when the config disables it or PacketEvents is missing, so a
 * caller never has to null-check before chaining.
 */
final class NoopHologram implements Hologram {

    private final String id;
    private final Location location;

    NoopHologram(String id, Location location) {
        this.id = id;
        this.location = location.clone();
    }

    @Override
    public @NotNull String id() {
        return id;
    }

    @Override
    public @NotNull Location location() {
        return location.clone();
    }

    @Override
    public void moveTo(@NotNull Location location) {
    }

    @Override
    public void attachTo(@Nullable Entity entity) {
    }

    @Override
    public void lines(@NotNull List<String> lines) {
    }

    @Override
    public void refresh() {
    }

    @Override
    public void updateData(@NotNull Map<String, Object> data) {
    }

    @Override
    public void visibleIf(@Nullable Predicate<Player> filter) {
    }

    @Override
    public boolean isViewing(@NotNull Player player) {
        return false;
    }

    @Override
    public int viewerCount() {
        return 0;
    }

    @Override
    public void remove() {
    }

    @Override
    public boolean removed() {
        return true;
    }
}
