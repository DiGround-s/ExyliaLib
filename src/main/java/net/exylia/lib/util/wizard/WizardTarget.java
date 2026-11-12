package net.exylia.lib.util.wizard;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * What a player clicked to answer {@link PluginWizards#askTarget}: a block, an
 * entity, or a library placed NPC (ExyliaDisplays NPCs included).
 *
 * @param location the block's location, or where the entity or NPC stood
 * @param entity   the clicked entity, {@code null} for a block or an NPC
 * @param npc      the clicked NPC as {@code <owner plugin>:<npc id>}, {@code null} otherwise
 * @since 1.603.0
 */
public record WizardTarget(@NotNull Location location, @Nullable UUID entity, @Nullable String npc) {

    /** Whether a block was clicked rather than an entity or an NPC. */
    public boolean isBlock() {
        return entity == null && npc == null;
    }
}
