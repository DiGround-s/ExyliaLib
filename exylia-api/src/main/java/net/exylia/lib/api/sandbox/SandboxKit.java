package net.exylia.lib.api.sandbox;

import org.jetbrains.annotations.NotNull;

/**
 * One kit a player saved, without its contents.
 *
 * <p>What the kit holds is deliberately not here. A kit is a whole inventory —
 * armour, offhand and thirty-six slots of items with their own data — and
 * copying all of it into a snapshot to answer "what are this player's kits
 * called" would cost more than the question is worth. A menu, a placeholder or
 * a list needs the slot, the name and whether there is anything in it;
 * {@link SandBoxService#applyKit(org.bukkit.entity.Player, int)} is how the
 * contents are used.
 *
 * @param slot        the kit slot, which is how a kit is addressed
 * @param name        what the player called it
 * @param favorite    whether this is the kit re-kitting reaches for
 * @param hasContents whether anything was ever saved into it
 * @param createdAt   when the slot was first written, in epoch milliseconds
 * @param updatedAt   when it last changed, in epoch milliseconds
 * @since 1.0.0
 */
public record SandboxKit(
        int slot,
        @NotNull String name,
        boolean favorite,
        boolean hasContents,
        long createdAt,
        long updatedAt) {
}
