package net.exylia.lib.spatial;

import net.exylia.lib.util.sequence.Sequence;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * What stands in the middle of a spatial menu, on its pedestal, slowly turning: the player wearing
 * what they are choosing, an item, an effect playing for real on a mannequin, a projectile flying
 * with its trail, or nothing. Returned by
 * the menu's {@code preview}, read again on every redraw: an equal preview is left as it is, an NPC
 * whose equipment alone changed is re-dressed rather than redrawn.
 *
 * @since 1.406.0
 */
public sealed interface SpatialPreview {

    /** Nothing in the middle. */
    static @NotNull SpatialPreview none() {
        return None.NONE;
    }

    /** A body in {@code skin}'s skin, wearing what they wear now. */
    static @NotNull SpatialPreview npc(@NotNull Player skin) {
        return new Npc(skin, null);
    }

    /**
     * A body in {@code skin}'s skin, wearing {@code equipment} (slots left out are empty), so a trim or
     * a skin is tried on before it is applied.
     */
    static @NotNull SpatialPreview npc(@NotNull Player skin, @NotNull Map<EquipmentSlot, ItemStack> equipment) {
        return new Npc(skin, equipment);
    }

    /** An item on the pedestal. */
    static @NotNull SpatialPreview item(@NotNull ItemStack item) {
        return new Item(item.clone());
    }

    /**
     * A mannequin in {@code skin}'s skin with {@code sequence} playing on it again and again, for the
     * viewer alone: a kill or hit effect seen for real. {@code null} skin wears the layout's
     * {@code preview.skins} (another online player, or a vanilla default skin), and the effect's ragdoll and
     * {@code [NPC] {victim}} wear the same one.
     */
    static @NotNull SpatialPreview sequence(@NotNull Sequence sequence, @Nullable Player skin) {
        return new Effect(sequence, skin);
    }

    /**
     * A projectile flying across the stage in a gentle arc into a small target on the pedestal, for the
     * viewer alone, again every couple of seconds: an arrow trail or a projectile effect seen for real.
     * {@code launch} plays where it sets off, {@code trail} wherever it is every {@code trailEveryTicks}
     * ticks, {@code impact} where it lands; each may be {@code null}. Every sequence plays at the
     * layout's {@code preview.effect-scale}, and the projectile is a display, never an entity.
     *
     * @since 1.422.0
     */
    static @NotNull SpatialPreview flight(@NotNull ItemStack projectile, @Nullable Sequence launch,
                                          @Nullable Sequence trail, int trailEveryTicks,
                                          @Nullable Sequence impact) {
        return new Flight(projectile.clone(), launch, trail, Math.max(1, trailEveryTicks), impact);
    }

    /** No preview. */
    enum None implements SpatialPreview { NONE }

    /** @param equipment {@code null} for what the player wears */
    record Npc(@NotNull Player skin, @Nullable Map<EquipmentSlot, ItemStack> equipment) implements SpatialPreview {
        public Npc {
            if (equipment != null) {
                Map<EquipmentSlot, ItemStack> copy = new EnumMap<>(EquipmentSlot.class);
                equipment.forEach((slot, item) -> {
                    if (item != null) copy.put(slot, item.clone());
                });
                equipment = Map.copyOf(copy);
            }
        }
    }

    record Item(@NotNull ItemStack item) implements SpatialPreview {
    }

    /** @since 1.422.0 */
    record Flight(@NotNull ItemStack projectile, @Nullable Sequence launch, @Nullable Sequence trail,
                  int trailEveryTicks, @Nullable Sequence impact) implements SpatialPreview {
    }

    record Effect(@NotNull Sequence sequence, @Nullable Player skin) implements SpatialPreview {
    }
}
