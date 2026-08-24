package net.exylia.lib.skull.internal;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;

/**
 * Builds the actual player head.
 *
 * <p>Confined here because it is the only code in the module that touches
 * server-specific profile types: Spigot and Paper disagree about how a texture
 * is attached to a head, and everything above this class is written once.
 *
 * <h2>Why the profile id is derived from the texture</h2>
 * A head's profile needs a UUID. ExyliaCommons used
 * {@code UUID.randomUUID()} for every head, which works but defeats the
 * client: two heads with the same skin but different ids are two different
 * textures to look up and keep. Deriving the id from the texture means the
 * same skin is always the same profile, so a menu of forty identical heads is
 * one texture on the client rather than forty.
 */
public final class HeadFactory {

    /** Whether Paper's profile API is available, decided once. */
    private static final boolean PAPER_PROFILES = detectPaperProfiles();

    private HeadFactory() {
    }

    /**
     * Builds a head wearing the given texture.
     *
     * <p>Must run on a thread allowed to touch item metadata. Item stacks are
     * not thread-safe, so callers hop back to the main thread first.
     *
     * @param base64 the texture property
     * @return the head, wearing the library's fallback texture when this one
     *         cannot be applied
     */
    public static ItemStack create(String base64) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (!Textures.isValid(base64)) {
            return withFallback(head);
        }
        if (!(head.getItemMeta() instanceof SkullMeta meta)) {
            return head;
        }
        if (PAPER_PROFILES) {
            applyPaperProfile(meta, base64);
        } else {
            applyBukkitProfile(meta, base64);
        }
        head.setItemMeta(meta);
        return head;
    }

    /**
     * Paper's own profile API. No reflection, no NMS, no version guessing.
     */
    private static void applyPaperProfile(SkullMeta meta, String base64) {
        PlayerProfile profile = Bukkit.createProfile(idFor(base64));
        profile.setProperty(new ProfileProperty("textures", base64));
        meta.setPlayerProfile(profile);
    }

    /**
     * The Bukkit-standard path, for Spigot.
     *
     * <p>{@code PlayerTextures} takes a URL rather than a property, which is
     * why the texture is decoded back into one. Slower than Paper's path, and
     * only walked on servers that have no alternative.
     */
    private static void applyBukkitProfile(SkullMeta meta, String base64) {
        String url = Textures.urlOf(base64);
        if (url == null) {
            return;
        }
        try {
            org.bukkit.profile.PlayerProfile profile =
                    Bukkit.createPlayerProfile(idFor(base64));
            org.bukkit.profile.PlayerTextures textures = profile.getTextures();
            textures.setSkin(java.net.URI.create(url).toURL());
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
        } catch (Exception unusable) {
            // A malformed URL inside a valid-looking texture. The head stays
            // plain rather than the menu failing to open.
        }
    }

    /**
     * A stable profile id for a texture.
     *
     * <p>Same skin, same id, on every server and every restart: the client
     * caches by profile, so this turns a menu full of one repeated head into a
     * single texture rather than one per slot.
     */
    private static UUID idFor(String base64) {
        return UUID.nameUUIDFromBytes(base64.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Applies the library's configured fallback texture.
     *
     * <p>The fallback is validated when it is set, not here, so this never
     * recurses: a head is built plain only if the fallback itself somehow
     * fails to apply, which {@link Textures#isValid} already prevents.
     */
    private static ItemStack withFallback(ItemStack head) {
        String fallback = SkullRuntime.fallback();
        if (!(head.getItemMeta() instanceof SkullMeta meta)) {
            return head;
        }
        if (PAPER_PROFILES) {
            applyPaperProfile(meta, fallback);
        } else {
            applyBukkitProfile(meta, fallback);
        }
        head.setItemMeta(meta);
        return head;
    }

    private static boolean detectPaperProfiles() {
        try {
            Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
            return true;
        } catch (ClassNotFoundException spigot) {
            return false;
        }
    }
}
