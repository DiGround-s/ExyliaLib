package net.exylia.lib.model.internal;

import net.exylia.lib.ragdoll.internal.SkinCubes;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * What one cube is drawn with, decided once as the file is read.
 *
 * @param kind     how it is drawn
 * @param material the block or item; for a head, the block it is drawn as
 *                 until its texture is ready, and for ever on a server that
 *                 cannot reach Exylia Skins
 * @param head     its six faces painted as a head, or {@code null}
 * @param slot     the carrier's equipment it shows, for {@link Kind#EQUIPMENT}
 */
@ApiStatus.Internal
public record Look(Kind kind, @Nullable Material material, SkinCubes.@Nullable Cube head,
                   @Nullable EquipmentSlot slot) {

    /** How a cube is drawn. */
    public enum Kind {
        /** A block display of one block. */
        BLOCK,
        /**
         * The block or item a cube's name asks for, decided as it is drawn:
         * whether a material is a block is the server's registry's to say.
         */
        NAMED,
        /** A player head wearing the cube's own texture, through Exylia Skins. */
        HEAD,
        /** Whatever the carrier holds or wears in one slot. */
        EQUIPMENT,
        /** Nothing: a cube the file marks hidden, or a helper. */
        HIDDEN
    }

    static Look block(Material material) {
        return new Look(Kind.BLOCK, material, null, null);
    }

    static Look named(Material material) {
        return new Look(Kind.NAMED, material, null, null);
    }

    static Look head(SkinCubes.Cube head) {
        return new Look(Kind.HEAD, head.dominant(), head, null);
    }

    static Look equipment(EquipmentSlot slot) {
        return new Look(Kind.EQUIPMENT, null, null, slot);
    }

    static final Look HIDDEN = new Look(Kind.HIDDEN, null, null, null);
}
