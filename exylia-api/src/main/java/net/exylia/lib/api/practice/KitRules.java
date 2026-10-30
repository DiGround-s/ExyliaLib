package net.exylia.lib.api.practice;

import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * The rules of a kit that a plugin running its own fights can enforce.
 *
 * <p>A snapshot of what an admin configured in practice. Practice enforces
 * these itself only inside its own matches: a plugin that equips players with
 * {@link PracticeService#applyKit} somewhere else reads them here and applies
 * what it supports. {@link PracticeService#applyKitEffects} already covers the
 * start health, start food and potion effects.
 *
 * <p>Cooldowns are in seconds, {@code 0} for none. Start values are
 * {@code -1} when the kit leaves them alone (full health, full food).
 *
 * @param build              whether players may place blocks
 * @param blockBreak         whether players may break blocks
 * @param interact           whether players may use blocks (doors, buttons, chests)
 * @param itemDrop           whether players may drop items
 * @param itemPickup         whether players may pick items up
 * @param pvp                whether players may damage each other
 * @param fallDamage         whether falling hurts
 * @param healthRegeneration whether health regenerates naturally
 * @param foodDecreasing     whether hunger goes down
 * @param hitDelay           the no-damage ticks between hits, {@code 20} in vanilla
 * @param enderPearlCooldown seconds between ender pearl throws
 * @param goldenAppleCooldown seconds between golden apples
 * @param arrowCooldown      seconds a bow or crossbow waits between shots
 * @param startHealth        the health a player starts with, or {@code -1}
 * @param startFood          the food level a player starts with, or {@code -1}
 * @param startSaturation    the saturation a player starts with, or {@code -1}
 * @param potionEffects      the effects granted when the fight starts; an
 *                           effect lasting the whole fight has
 *                           {@link PotionEffect#INFINITE_DURATION}
 * @since 3.5.0
 */
public record KitRules(
        boolean build,
        boolean blockBreak,
        boolean interact,
        boolean itemDrop,
        boolean itemPickup,
        boolean pvp,
        boolean fallDamage,
        boolean healthRegeneration,
        boolean foodDecreasing,
        int hitDelay,
        int enderPearlCooldown,
        int goldenAppleCooldown,
        int arrowCooldown,
        double startHealth,
        double startFood,
        double startSaturation,
        @NotNull @Unmodifiable List<PotionEffect> potionEffects) {
}
