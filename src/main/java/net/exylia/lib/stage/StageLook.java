package net.exylia.lib.stage;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.text.Colors;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * What every stage needs to read its look from a config file and to pose its pieces.
 *
 * <pre>{@code
 * int glow = StageLook.rgb("{primary}", 0x8A51C4);           // a palette token or #rrggbb
 * BlockData back = StageLook.block(theme.tableBlock(), Material.BLACK_CONCRETE);
 * DisplayMotion wave = StageLook.pop(stage.facing(), 0.3, 0.3, 0.3, index * 60L, stage.life());
 * StageLook.play(player, "UI_BUTTON_CLICK|0.6|1.2");
 * }</pre>
 *
 * <p>Stateless; every method is safe from any thread, and {@link #play} sends to the player
 * wherever it is called from, as the effect module does.
 *
 * @since 1.356.0
 */
public final class StageLook {

    private StageLook() {
        throw new AssertionError("No instances.");
    }

    /**
     * A colour written as a palette token, {@code {success}}, or as {@code #rrggbb}.
     *
     * @return the colour as {@code 0xRRGGBB}, or {@code fallback} when it cannot be read
     */
    public static int rgb(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        TextColor colour = trimmed.startsWith("{") && trimmed.endsWith("}")
                ? Colors.get(trimmed.substring(1, trimmed.length() - 1))
                : TextColor.fromHexString(trimmed);
        return colour == null ? fallback : colour.value();
    }

    /** A block written by name, or {@code fallback} when it is not one. */
    public static BlockData block(String name, Material fallback) {
        Material material = name == null ? null : Material.matchMaterial(name);
        return (material != null && material.isBlock() ? material : fallback).createBlockData();
    }

    /** An item written by name, or {@code fallback} when it is not one. */
    public static org.bukkit.inventory.ItemStack item(String name, Material fallback) {
        Material material = name == null ? null : Material.matchMaterial(name);
        return new org.bukkit.inventory.ItemStack(material != null && material.isItem() ? material : fallback);
    }

    /** Plays a sound written as {@code NAME|volume|pitch}; an empty line plays nothing. */
    public static void play(Player player, String line) {
        if (player != null && line != null && !line.isBlank()) {
            Effects.soundFrom(line).show(player);
        }
    }

    /**
     * Plays a sound written as {@code NAME|volume|pitch} with its pitch multiplied by {@code factor}:
     * a click that climbs as a wheel slows down keeps the pitch the config wrote as its floor.
     */
    public static void play(Player player, String line, double factor) {
        if (player == null || line == null || line.isBlank()) return;
        Effects.soundFrom(line).pitch(pitch(line) * factor).show(player);
    }

    /** The pitch a sound line {@code NAME|volume|pitch} is written at; {@code 1} when it says none. */
    public static double pitch(String line) {
        String[] parts = line.split("\\|");
        if (parts.length > 2) {
            try {
                return Double.parseDouble(parts[2].trim());
            } catch (NumberFormatException ignored) {
                // Unreadable: the library falls back on its own default, so do we.
            }
        }
        return 1.0;
    }

    /**
     * A win's beat: from wherever it is, the piece swells past {@code rest} and settles on it over
     * {@code entry} milliseconds, then keeps swelling by {@code grow} and back every {@code cycle},
     * until it is taken down. Two poses a cycle, played by the client.
     */
    public static DisplayMotion pulse(DisplayKeyframe rest, double grow, long entry, long cycle, long life) {
        long settle = Math.max(2L, entry);
        long beat = Math.max(100L, cycle);
        float g = (float) grow;
        return DisplayMotion.of(List.of(
                sized(rest, 0L, rest.scaleX(), rest.scaleY(), rest.scaleZ()),
                sized(rest, settle * 6 / 10, rest.scaleX() * 1.15f, rest.scaleY() * 1.15f, rest.scaleZ() * 1.15f),
                sized(rest, settle, rest.scaleX(), rest.scaleY(), rest.scaleZ()),
                sized(rest, settle + beat / 2, rest.scaleX() * g, rest.scaleY() * g, rest.scaleZ() * g),
                sized(rest, settle + beat, rest.scaleX(), rest.scaleY(), rest.scaleZ())), life)
                .looping(settle, settle + beat, 1.0, 1.0);
    }

    /** The turn that makes a display face the seat of a plane. */
    public static Rotation facing(StagePlane plane) {
        return Rotation.around(Rotation.Axis.Y, plane.facing());
    }

    /** A piece that stands still, turned and sized, for as long as {@code life} lasts. */
    public static DisplayMotion still(Rotation rotation, double x, double y, double z, long life) {
        return DisplayMotion.of(List.of(new DisplayKeyframe(0L, 0f, 0f, 0f, rotation,
                (float) x, (float) y, (float) z)), life);
    }

    /** A piece that pops in, overshooting a little, and then stands still. */
    public static DisplayMotion pop(Rotation rotation, double scale, long life) {
        return pop(rotation, scale, scale, scale, 0, life);
    }

    /**
     * A piece that waits {@code delay} milliseconds unseen, then pops in to its size: what lets a
     * board appear in a wave rather than all at once.
     */
    public static DisplayMotion pop(Rotation rotation, double x, double y, double z, long delay, long life) {
        float sx = (float) x;
        float sy = (float) y;
        float sz = (float) z;
        float tiny = 0.01f;
        return DisplayMotion.of(List.of(
                new DisplayKeyframe(0L, 0f, 0f, 0f, rotation, tiny, tiny, tiny),
                new DisplayKeyframe(delay, 0f, 0f, 0f, rotation, tiny, tiny, tiny),
                new DisplayKeyframe(delay + 150L, 0f, 0f, 0f, rotation, sx * 1.12f, sy * 1.12f, sz * 1.12f),
                new DisplayKeyframe(delay + 280L, 0f, 0f, 0f, rotation, sx, sy, sz)), life);
    }

    /**
     * The same pop for a piece held away from where it was spawned: it pops in at {@code pose}, its
     * offset and turn kept, so the pieces of a ring drawn from one centre can enter in a wave.
     */
    public static DisplayMotion pop(DisplayKeyframe pose, long delay, long life) {
        float tiny = 0.01f;
        return DisplayMotion.of(List.of(
                sized(pose, 0L, tiny, tiny, tiny),
                sized(pose, delay, tiny, tiny, tiny),
                sized(pose, delay + 150L, pose.scaleX() * 1.12f, pose.scaleY() * 1.12f, pose.scaleZ() * 1.12f),
                sized(pose, delay + 280L, pose.scaleX(), pose.scaleY(), pose.scaleZ())), life);
    }

    private static DisplayKeyframe sized(DisplayKeyframe pose, long at, float x, float y, float z) {
        return new DisplayKeyframe(at, pose.x(), pose.y(), pose.z(), pose.rotation(), x, y, z);
    }

    /**
     * The text scale that fits a line into {@code width} blocks, capped at {@code max}: wide server
     * fonts make a fixed scale spill over its neighbours.
     */
    public static double fit(net.kyori.adventure.text.Component text, double width, double max) {
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text);
        return Math.min(max, width / Math.max(0.18, width(plain)));
    }

    /**
     * How wide a line is at scale 1, in blocks, bold assumed: the server font's narrow glyphs (a dot,
     * an i, a space) are a third of a digit's width, so counting them as full characters would shrink
     * a "7.92" or a "×1.5" well below what fits.
     */
    public static double width(String plain) {
        double width = 0;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            width += switch (c) {
                case '.', ',', ':', ';', '!', '|', '\'', 'i' -> 0.075;
                case 'l', '`' -> 0.1;
                case ' ', 'I', 't', '[', ']' -> 0.125;
                case 'f', 'k', '(', ')', '<', '>', '{', '}', '*', '"' -> 0.15;
                default -> c > 0x2000 ? 0.2 : 0.18;
            };
        }
        return width;
    }

    /** A palette token or #rrggbb as a firework colour, {@code #rrggbb}. */
    public static String hex(String value) {
        return String.format("#%06X", rgb(value, 0xFFD700) & 0xFFFFFF);
    }
}
