package net.exylia.lib.api.chatcosmetics;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A named snapshot of what a player had equipped.
 *
 * <p>What is in it is deliberately not published: it is stored as the plugin's
 * own packed text and a cosmetic the player has since lost is skipped when the
 * loadout is put on. Apply it with {@link CosmeticsService#applyLoadout(org.bukkit.entity.Player, long)}
 * and read what they ended up wearing afterwards.
 *
 * @param id        the loadout id, what applying takes
 * @param player    whose it is
 * @param name      what they called it
 * @param createdAt when it was saved, in epoch millis
 * @since 1.0.0
 */
public record Loadout(long id, @NotNull UUID player, @NotNull String name, long createdAt) {
}
