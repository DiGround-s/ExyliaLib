package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;
import org.jetbrains.annotations.ApiStatus;

/**
 * One piece of a skin MineSkin has already turned into a texture, as the
 * database of a plugin that shows ragdolls keeps it.
 *
 * <p>Only Mojang's texture id is kept: sixty-four characters that rebuild the
 * whole property, rather than the signed property itself, which is several
 * times the size and carries nothing a head needs. A table of every piece a
 * network has ever uploaded stays small enough to never think about.
 *
 * @param fingerprint the hash of the piece's painted picture
 * @param texture     the id at the end of its textures.minecraft.net address
 */
@ApiStatus.Internal
@Table("exylia_ragdoll_skins")
public record RagdollTextureRow(
        @Id(length = 64) String fingerprint,
        @Column(length = 64) String texture) {
}
