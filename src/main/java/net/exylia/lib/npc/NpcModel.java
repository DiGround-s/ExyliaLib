package net.exylia.lib.npc;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Who an NPC looks like, and what it is wearing.
 *
 * <pre>{@code
 * NpcModel corpse = NpcModel.of(victim)
 *         .wearing(victim)
 *         .pose(NpcPose.LYING)
 *         .glow(0xA33B53);
 * }</pre>
 *
 * <h2>The skin comes from the connection, not from Mojang</h2>
 * {@link #of(Player)} reads the texture the packet layer already holds for a
 * player who is on the server. That costs no lookup, blocks on nothing and
 * cannot fail halfway, which matters because the moment an NPC is usually
 * wanted &mdash; somebody just died &mdash; is a moment nothing may wait in.
 *
 * <p>Immutable and shared. One model built when a file is read serves every
 * play; a model built from a player is built per play, which is one object.
 *
 * @since 1.88.2
 */
public final class NpcModel {

    /** A hidden identity, so two NPCs of the same player never collide. */
    private final UUID id;
    private final String name;
    private final String texture;
    private final String signature;
    private final NpcPose pose;
    private final int glowArgb;
    private final Player wearing;
    private final ItemStack held;

    /** Whose connection the skin is read from, when no texture was written down. */
    private final Player skinFrom;

    private NpcModel(UUID id, String name, String texture, String signature, NpcPose pose,
                     int glowArgb, Player wearing, ItemStack held, Player skinFrom) {
        this.id = id;
        this.name = name;
        this.texture = texture;
        this.signature = signature;
        this.pose = pose;
        this.glowArgb = glowArgb;
        this.wearing = wearing;
        this.held = held;
        this.skinFrom = skinFrom;
    }

    /**
     * An NPC wearing a player's face.
     *
     * <p>The player has to be on the server; that is not a restriction so much
     * as the whole design. Their name is kept too, so the NPC is recognisable
     * from behind and above rather than only from the front.
     *
     * @param player whose face and name
     * @return the model
     */
    public static @NotNull NpcModel of(@NotNull Player player) {
        return new NpcModel(UUID.randomUUID(), player.getName(), null, null,
                NpcPose.STANDING, -1, null, null, player);
    }

    /**
     * An NPC wearing a texture written down somewhere.
     *
     * @param name      the name it carries; kept short, it is drawn above the head
     * @param texture   the base64 texture value
     * @param signature the texture's signature, or {@code null} for an unsigned skin
     * @return the model
     */
    public static @NotNull NpcModel of(@NotNull String name, @NotNull String texture,
                                       @Nullable String signature) {
        return new NpcModel(UUID.randomUUID(), trimmedName(name), texture, signature,
                NpcPose.STANDING, -1, null, null, null);
    }

    /** How it holds itself. */
    public @NotNull NpcModel pose(@NotNull NpcPose pose) {
        return new NpcModel(id, name, texture, signature, pose, glowArgb, wearing, held, skinFrom);
    }

    /**
     * An outline around it, in {@code 0xRRGGBB}.
     *
     * @param rgb the outline colour, or a negative number for none
     * @return a new model
     */
    public @NotNull NpcModel glow(int rgb) {
        return new NpcModel(id, name, texture, signature, pose, rgb, wearing, held, skinFrom);
    }

    /**
     * Dresses it in what a player is wearing right now.
     *
     * <p>Read when the NPC is shown rather than kept: an inventory is a live
     * thing and the armour a corpse should be wearing is the armour they died
     * in, not the armour they had when the model was built.
     *
     * @param player whose armour and weapon, or {@code null} for none
     * @return a new model
     */
    public @NotNull NpcModel wearing(@Nullable Player player) {
        return new NpcModel(id, name, texture, signature, pose, glowArgb, player, held, skinFrom);
    }

    /**
     * Puts one item in its main hand, whatever else it is wearing.
     *
     * @param item the item, or {@code null} for an empty hand
     * @return a new model
     */
    public @NotNull NpcModel holding(@Nullable ItemStack item) {
        return new NpcModel(id, name, texture, signature, pose, glowArgb, wearing, item, skinFrom);
    }

    /** The identity this NPC is announced under. Never a real player's. */
    public @NotNull UUID id() {
        return id;
    }

    /** The name drawn above it. */
    public @NotNull String name() {
        return name;
    }

    /**
     * Whose connection the skin is read from, or {@code null} when a texture was
     * written down instead.
     */
    public @Nullable Player skinFrom() {
        return skinFrom;
    }

    /** The base64 texture, or {@code null} to read it from {@link #skinFrom()}. */
    public @Nullable String texture() {
        return texture;
    }

    /** The texture's signature, or {@code null}. */
    public @Nullable String signature() {
        return signature;
    }

    /** How it holds itself. */
    public @NotNull NpcPose pose() {
        return pose;
    }

    /** The outline colour as {@code 0xRRGGBB}, or a negative number for none. */
    public int glowArgb() {
        return glowArgb;
    }

    /** Whose kit it wears, or {@code null}. */
    public @Nullable Player wearing() {
        return wearing;
    }

    /** What is in its hand regardless of the kit, or {@code null}. */
    public @Nullable ItemStack held() {
        return held;
    }

    /**
     * A name the protocol will carry.
     *
     * <p>Sixteen characters, because that is what the field is: a longer one is
     * refused by the client and takes the NPC with it.
     */
    private static String trimmedName(String name) {
        String trimmed = name.trim();
        return trimmed.length() <= 16 ? trimmed : trimmed.substring(0, 16);
    }
}
