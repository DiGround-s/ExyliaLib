package net.exylia.lib.util.crate.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One player's crate in one plugin: their keys and what they unlocked.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * <p>Immutable, and every change answers {@code this} when it changes nothing,
 * so the store can tell a change that needs writing from one that does not by
 * identity. {@code updatedAt} is stamped by the store, not here.
 *
 * @param id        the plugin and the player, {@code ExyliaTrims:<uuid>}
 * @param plugin    the plugin's name
 * @param uuid      the player
 * @param keys      keys on the account, never below zero
 * @param unlocked  unlocked reward ids, comma separated, in the order they came; {@code null} for none
 * @param createdAt when the row was created, in epoch milliseconds
 * @param updatedAt when it last changed, in epoch milliseconds
 * @since 1.189.0
 */
@Table("exylia_crate_players")
public record CrateRow(
        @Id(length = 128) String id,
        @Column(length = 64) String plugin,
        @Column UUID uuid,
        @Column int keys,
        @Column(length = Column.UNBOUNDED) String unlocked,
        @Column("created_at") long createdAt,
        @Column("updated_at") long updatedAt) {

    /**
     * What separates one unlocked id from the next.
     *
     * <p>A comma rather than a second table: the list is short, it is only ever
     * read whole, and a table would cost a join on every redraw.
     */
    private static final String SEPARATOR = ",";

    /** The key a player's row is stored under for a plugin. */
    public static @NotNull String key(@NotNull String plugin, @NotNull UUID uuid) {
        return plugin + ':' + uuid;
    }

    /** A row nobody has written yet. */
    public static @NotNull CrateRow fresh(@NotNull String plugin, @NotNull UUID uuid, int keys) {
        long now = System.currentTimeMillis();
        return new CrateRow(key(plugin, uuid), plugin, uuid, Math.max(0, keys), null, now, now);
    }

    /**
     * A row nobody has written yet, seeded with what the player had in the
     * plugin's own table. Ids are normalised as the crate stores them; blank
     * ones, ones carrying a comma and repeats are left out.
     */
    public static @NotNull CrateRow imported(@NotNull String plugin, @NotNull UUID uuid, int keys,
                                             @NotNull List<String> unlocked) {
        CrateRow row = fresh(plugin, uuid, keys);
        for (String written : unlocked) {
            String id = TierTable.normalise(written);
            if (!id.isEmpty() && !id.contains(SEPARATOR)) row = row.withUnlocked(id);
        }
        return row;
    }

    public @NotNull List<String> unlockedIds() {
        if (unlocked == null || unlocked.isEmpty()) return List.of();
        return List.of(unlocked.split(SEPARATOR));
    }

    public boolean isUnlocked(@NotNull String rewardId) {
        return unlockedIds().contains(rewardId);
    }

    public @NotNull CrateRow withKeys(int keys) {
        int clamped = Math.max(0, keys);
        return clamped == this.keys ? this : new CrateRow(id, plugin, uuid, clamped, unlocked, createdAt, updatedAt);
    }

    public @NotNull CrateRow withUnlocked(@NotNull String rewardId) {
        if (isUnlocked(rewardId)) return this;
        List<String> ids = new ArrayList<>(unlockedIds());
        ids.add(rewardId);
        return withList(ids);
    }

    public @NotNull CrateRow withoutUnlocked(@NotNull String rewardId) {
        List<String> ids = new ArrayList<>(unlockedIds());
        return ids.remove(rewardId) ? withList(ids) : this;
    }

    public @NotNull CrateRow withoutUnlocks() {
        return unlocked == null ? this : withList(List.of());
    }

    /** The same row, stamped as changed now. */
    public @NotNull CrateRow touched() {
        return new CrateRow(id, plugin, uuid, keys, unlocked, createdAt, System.currentTimeMillis());
    }

    private CrateRow withList(List<String> ids) {
        return new CrateRow(id, plugin, uuid, keys, ids.isEmpty() ? null : String.join(SEPARATOR, ids),
                createdAt, updatedAt);
    }
}
