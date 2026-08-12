package net.exylia.lib.util;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Who a cooldown belongs to.
 *
 * <p>Most cooldowns belong to a player, but not all: a chat channel's slow mode
 * is per player and per channel, a world boss is on cooldown for the entire
 * server, and a clan's war declaration is shared by everyone in it.
 *
 * <p>A scope is the identity half of a cooldown; the key is the other half.
 * Two different scopes never collide, so a clan and a player can hold the same
 * key without knowing about each other.
 *
 * @since 1.11.0
 */
public final class CooldownScope {

    /** The one scope every server shares. */
    public static final CooldownScope GLOBAL = new CooldownScope("global", "", null);

    private final String type;

    /**
     * The textual id, for every scope that is not a player.
     *
     * <p>Empty for player scopes, which keep the UUID itself instead: turning
     * a UUID into a string costs an allocation and about sixty nanoseconds,
     * and this object is built on every call of the hot path.
     */
    private final String id;

    /** The player, for player scopes. Null for every other kind. */
    private final UUID uuid;

    /**
     * Cached because a scope is created on every call in the hot path, and its
     * only job is to be a map key.
     */
    private final int hash;

    private CooldownScope(String type, String id, UUID uuid) {
        this.type = type;
        this.id = id;
        this.uuid = uuid;
        this.hash = uuid != null
                ? uuid.hashCode() * 31 + 1
                : type.hashCode() * 31 + id.hashCode();
    }

    /**
     * Player scopes, kept rather than rebuilt.
     *
     * <p>Every call of the hot path needs one, and they are immutable, so
     * there is no reason to allocate a fresh one each time. Bounded by the
     * number of players the server has seen since boot.
     */
    private static final java.util.concurrent.ConcurrentHashMap<UUID, CooldownScope> PLAYERS
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** A cooldown belonging to one player. */
    public static @NotNull CooldownScope player(@NotNull UUID player) {
        CooldownScope existing = PLAYERS.get(player);
        return existing != null ? existing
                : PLAYERS.computeIfAbsent(player, id -> new CooldownScope("player", "", id));
    }

    /** Drops a player's cached scope. Called when they leave. */
    static void forgetPlayer(@NotNull UUID player) {
        PLAYERS.remove(player);
    }

    /** A cooldown belonging to one player. */
    public static @NotNull CooldownScope player(@NotNull Player player) {
        return player(player.getUniqueId());
    }

    /**
     * A cooldown shared by a named group: a clan, a team, a party.
     *
     * <p>The library does not care what the group is or who is in it. It stores
     * the cooldown against the name and the caller decides what that means.
     */
    public static @NotNull CooldownScope group(@NotNull String groupId) {
        return new CooldownScope("group", groupId, null);
    }

    /**
     * A cooldown of the caller's own making.
     *
     * <p>For anything the built-in scopes do not cover: a region, a block
     * position, an NPC.
     */
    public static @NotNull CooldownScope of(@NotNull String type, @NotNull String id) {
        return new CooldownScope(type, id, null);
    }

    /** The kind of thing this scope identifies. */
    public @NotNull String type() {
        return type;
    }

    /** Which one of them. */
    public @NotNull String id() {
        return uuid != null ? uuid.toString() : id;
    }

    /** Returns whether this scope is a single player. */
    public boolean isPlayer() {
        return uuid != null;
    }

    /**
     * The player this scope belongs to, or {@code null} when it is not a
     * player scope.
     */
    public UUID playerId() {
        return uuid;
    }

    /** The name of the file this scope's persistent cooldowns live in. */
    @NotNull String storageId() {
        return uuid != null ? "player-" + uuid : type + "-" + id;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CooldownScope scope) || hash != scope.hash) {
            return false;
        }
        if (uuid != null) {
            return uuid.equals(scope.uuid);
        }
        return scope.uuid == null && type.equals(scope.type) && id.equals(scope.id);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        if (uuid != null) {
            return "player:" + uuid;
        }
        return id.isEmpty() ? type : type + ":" + id;
    }
}
