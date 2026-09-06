package net.exylia.lib.api.armorskin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Set;

/**
 * A skin the server declares, as it was when you asked.
 *
 * <p>A snapshot of the catalogue entry and nothing more. What the skin actually
 * looks like — its colour, its trim, its particles, its animation — is left out
 * on purpose: those are the plugin's own types, they change with the version
 * that draws them, and a third party has no use for them that
 * {@link ArmorSkinService#skinItem(String)} does not already cover.
 *
 * @param id         the id used everywhere a skin is named, lowercase
 * @param name       what menus and items call it, with colour codes still in it
 * @param permission the node granting this skin on every piece it fits, so a
 *                   rank or shop plugin can sell it without building the string
 * @param pieces     the armor slots it can be worn in
 * @since 1.0.0
 */
public record ArmorSkin(
        @NotNull String id,
        @NotNull String name,
        @NotNull String permission,
        @NotNull @Unmodifiable Set<ArmorPiece> pieces) {

    /**
     * Whether this skin can be worn on a piece.
     *
     * @param piece the armor slot
     * @return {@code true} when the skin fits there
     */
    public boolean supports(@NotNull ArmorPiece piece) {
        return pieces.contains(piece);
    }
}
