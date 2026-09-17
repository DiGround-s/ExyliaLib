package net.exylia.lib.replay;

import net.exylia.lib.npc.internal.NpcRuntime;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Somebody a recording was made of.
 *
 * <p>Identity only: who they were and what they looked like. Where they were
 * from one moment to the next is the recording's, not theirs.
 *
 * <h2>The skin is kept, not looked up</h2>
 * A recording outlives the session it was made in &mdash; that is the whole
 * point of one &mdash; so the texture is read off the connection at the moment
 * the player is followed and written into the file beside their name. Played
 * back a week later, on a server the player is not on, the body still wears
 * what they were wearing.
 *
 * @param id        their UUID, as the recording knew it
 * @param name      their name at the time, which is what the body is announced
 *                  under
 * @param texture    the base64 skin, or {@code null} when it could not be read
 * @param signature  the signature that goes with it, or {@code null}
 * @param entityType what to draw when this is not a player &mdash; an arrow, a
 *                   crystal, a primed block of TNT &mdash; named as Bukkit
 *                   names it. {@code null} for a player, which is drawn from
 *                   the name and skin above instead.
 * @since 1.175.0
 */
public record ReplayActor(@NotNull UUID id, @NotNull String name,
                          @Nullable String texture, @Nullable String signature,
                          @Nullable String entityType) {

    /**
     * Reads a player's identity as it is right now.
     *
     * <p>Costs nothing and blocks on nothing: the skin comes from the profile
     * this server already holds for the connection, not from Mojang.
     *
     * @param player who to read
     * @return their identity, with whatever skin could be read
     */
    public static @NotNull ReplayActor of(@NotNull Player player) {
        String[] skin = NpcRuntime.textureOf(player);
        return new ReplayActor(player.getUniqueId(), player.getName(),
                skin == null ? null : skin[0], skin == null ? null : skin[1], null);
    }

    /**
     * Something that is not a player: an arrow in flight, a crystal on the
     * ground, a block of TNT counting down.
     *
     * <p>Its type and nothing else. What an arrow looks like is the client's
     * business, and a recording that tried to keep more than the type would be
     * storing a copy of the game's own model.
     *
     * @param entity what it is
     * @return its identity
     */
    public static @NotNull ReplayActor of(@NotNull org.bukkit.entity.Entity entity) {
        return new ReplayActor(entity.getUniqueId(), entity.getType().name(), null, null,
                entity.getType().name());
    }

    /** Whether this is a player, rather than something else in the arena. */
    public boolean isPlayer() {
        return entityType == null;
    }
}
