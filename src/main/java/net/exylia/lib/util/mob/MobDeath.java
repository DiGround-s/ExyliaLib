package net.exylia.lib.util.mob;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * A custom mob that died, and who had a hand in it.
 *
 * <pre>{@code
 * mobs.onDeath(death -> {
 *     if (death.cause() == MobDeath.Cause.EXPIRED) return;             // it simply left
 *     if (death.killer() == null || death.playerShare() < 0.5) return; // a farm, or the void
 *     Rewards.of(this).give(death.killer(), death.template().rewards());
 *     economy.deposit(death.killer(), death.template().money());
 * });
 * }</pre>
 *
 * @param template    what the mob was spawned from
 * @param entity      the dying entity; do not keep it past the handler
 * @param location    where it died
 * @param killer      the player the server credits with the kill, or {@code null}
 * @param damage      damage dealt by each player, attributed through arrows,
 *                    tamed animals and TNT; never modified by the library afterwards
 * @param topDamager  the player who dealt the most, or {@code null} when no player dealt any
 * @param playerShare the share of all damage the mob took that came from players, {@code 0} to {@code 1}
 * @param cause       how it ended (since 1.195.0)
 * @since 1.192.0
 */
public record MobDeath(@NotNull MobTemplate template, @NotNull LivingEntity entity,
                       @NotNull Location location, @Nullable Player killer,
                       @NotNull Map<UUID, Double> damage, @Nullable UUID topDamager,
                       double playerShare, @NotNull Cause cause) {

    /**
     * How a mob ended.
     *
     * @since 1.195.0
     */
    public enum Cause {
        /**
         * It died: health ran out, or {@code /kill} and the void, which reach a
         * mob in hits mode too. The entity is dead; drops and experience are vanilla's
         * plus the template's.
         */
        KILLED,
        /**
         * Its last hit landed ({@link MobBehaviour#usesHits()}). {@code killer} is
         * the player who dealt it and {@code damage} counts one per hit. Its
         * {@code DEATH} skills were cast and its experience dropped; the entity is
         * still in the world during the handlers and removed right after.
         */
        BROKEN,
        /**
         * Its {@link MobBehaviour#lifetime()} ran out. No killer, no {@code DEATH}
         * skills, no experience; removed right after the handlers.
         */
        EXPIRED
    }

    public MobDeath {
        damage = Map.copyOf(damage);
        java.util.Objects.requireNonNull(cause, "cause");
    }
}
