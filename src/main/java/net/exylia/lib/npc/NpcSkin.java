package net.exylia.lib.npc;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * What a placed NPC wears.
 *
 * <pre>{@code
 * NpcSkin.texture(value, signature);   // written down, drawn at once
 * NpcSkin.player("Notch");             // looked up once, off the main thread, then cached
 * NpcSkin.mirror();                    // every viewer sees their own skin
 * NpcSkin.none();                      // the client's default
 * }</pre>
 *
 * <p>Immutable, and equal by what it describes.
 *
 * @since 1.291.0
 */
public final class NpcSkin {

    private static final NpcSkin NONE = new NpcSkin(null, null, null, false);
    private static final NpcSkin MIRROR = new NpcSkin(null, null, null, true);

    private final String texture;
    private final String signature;
    private final String player;
    private final boolean mirror;

    private NpcSkin(String texture, String signature, String player, boolean mirror) {
        this.texture = texture;
        this.signature = signature;
        this.player = player;
        this.mirror = mirror;
    }

    /** The client's default skin. */
    public static @NotNull NpcSkin none() {
        return NONE;
    }

    /** Each viewer sees the NPC wearing their own skin, read from their connection. */
    public static @NotNull NpcSkin mirror() {
        return MIRROR;
    }

    /**
     * A texture written down somewhere.
     *
     * @param value     the base64 texture value
     * @param signature Mojang's signature for it; without one a modern client draws the
     *                  default skin
     * @return the skin
     */
    public static @NotNull NpcSkin texture(@NotNull String value, @Nullable String signature) {
        return new NpcSkin(Objects.requireNonNull(value, "value"), signature, null, false);
    }

    /**
     * The skin a Minecraft account wears.
     *
     * <p>Read from the connection when that player is online, otherwise asked of Mojang
     * off the main thread and cached for the rest of the run. Until it arrives the NPC is
     * drawn with the default skin, and is redrawn for its viewers once it does.
     *
     * @param name the account name
     * @return the skin
     */
    public static @NotNull NpcSkin player(@NotNull String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a player skin needs a name");
        }
        return new NpcSkin(null, null, trimmed, false);
    }

    /** The written texture, or {@code null}. */
    public @Nullable String texture() {
        return texture;
    }

    /** The written texture's signature, or {@code null}. */
    public @Nullable String signature() {
        return signature;
    }

    /** The account whose skin is looked up, or {@code null}. */
    public @Nullable String playerName() {
        return player;
    }

    /** Whether each viewer sees their own skin. */
    public boolean isMirror() {
        return mirror;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NpcSkin that
                && mirror == that.mirror
                && Objects.equals(texture, that.texture)
                && Objects.equals(signature, that.signature)
                && Objects.equals(key(player), key(that.player));
    }

    @Override
    public int hashCode() {
        return Objects.hash(texture, signature, key(player), mirror);
    }

    private static String key(String name) {
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }
}
