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
 * @since 1.192.0
 */
public record MobDeath(@NotNull MobTemplate template, @NotNull LivingEntity entity,
                       @NotNull Location location, @Nullable Player killer,
                       @NotNull Map<UUID, Double> damage, @Nullable UUID topDamager,
                       double playerShare) {

    public MobDeath {
        damage = Map.copyOf(damage);
    }
}
