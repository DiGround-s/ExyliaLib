package net.exylia.lib.util.mob;

import net.exylia.lib.util.reward.RewardEntry;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The plugin that keeps the server's mob templates, for the plugins that only use them.
 *
 * <pre>{@code
 * // a vote party spawning a template somebody built in ExyliaMobs
 * Mobs.store().ifPresent(store -> {
 *     MobTemplate pinata = store.mobs().template("pinata_classic");
 *     if (pinata != null) store.mobs().spawn(pinata, where);
 * });
 * }</pre>
 *
 * <p>Answered by ExyliaMobs through {@link Mobs#provide}. A mob spawned through
 * {@link #mobs()} is the store's: it pays out through the store's handlers, and
 * a caller listening with {@link PluginMobs#onHit}/{@link PluginMobs#onDeath}
 * on it hears every one of the store's mobs, so it filters by its own entities
 * and takes its handlers off again when it disables. Empty
 * {@link Mobs#store()} means no plugin keeps templates on this server.
 *
 * @since 1.316.0
 */
public interface MobStore {

    /** The store's mobs: its registered templates, spawns and handlers. */
    @NotNull PluginMobs mobs();

    /**
     * Every template the store's database holds, read now rather than from
     * {@link PluginMobs#templates()}, so a caller that must not collide with
     * another server's template sees it. A row this server cannot read is left out.
     */
    @NotNull CompletableFuture<List<Stored>> stored();

    /**
     * Stores a template and registers it.
     *
     * @param template the template
     * @param payouts  what the store pays beyond the template's own rewards;
     *                 {@code null} keeps what the stored row pays, or nothing for a new one
     * @return done once the write landed
     */
    @NotNull CompletableFuture<Void> save(@NotNull MobTemplate template, @Nullable Payouts payouts);

    /**
     * Opens the store's editor on a template.
     *
     * @param back what its BACK button runs; {@code null} returns to the store's own list
     * @return {@code false} when there is no such template
     */
    boolean edit(@NotNull Player player, @NotNull String templateId, @Nullable Runnable back);

    /** A stored template with what the store pays for it. */
    record Stored(@NotNull MobTemplate template, @NotNull Payouts payouts) {
    }

    /**
     * What a store pays beyond the template's own rewards.
     *
     * @param booster the booster source its money is boosted as; blank is the store's own
     * @param mode    where items go, {@code DROP} or {@code INVENTORY}; blank follows the store's config
     * @param lists   reward lists by name ({@code HIT}, {@code LAST_HIT}, {@code EVERYONE}...);
     *                names the store does not know are ignored
     */
    record Payouts(@NotNull String booster, @NotNull String mode, @NotNull Map<String, List<RewardEntry>> lists) {

        /** Nothing beyond the template. */
        public static final Payouts NONE = new Payouts("", "", Map.of());

        public Payouts {
            booster = booster == null ? "" : booster;
            mode = mode == null ? "" : mode;
            lists = lists == null ? Map.of() : Map.copyOf(lists);
        }
    }
}
