package net.exylia.lib.client;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

/**
 * The walls of a box, drawn by the client as translucent panels.
 *
 * <p>Not the per-player world border, which confines and tints; this is a
 * highlight anybody can walk through. Only clients that have zone borders draw
 * one, so a plugin keeps its own outline for everyone else.
 *
 * @param name   the handle it is removed by
 * @param world  the world the box is in
 * @param box    the box
 * @param colour its colour
 * @since 1.233.0
 */
public record ZoneBorder(@NotNull String name, @NotNull World world, @NotNull BoundingBox box,
                         @NotNull TextColor colour) implements ClientElement {

    public ZoneBorder {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a border needs a name");
        }
        if (box.getWidthX() <= 0 || box.getWidthZ() <= 0 || box.getHeight() <= 0) {
            throw new IllegalArgumentException("a border needs a box with room on every axis");
        }
        box = box.clone();
    }
}
