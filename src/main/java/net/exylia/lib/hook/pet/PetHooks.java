package net.exylia.lib.hook.pet;

import net.exylia.lib.hook.pet.internal.PetHookRuntime;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

/**
 * Putting a player's pets away while the player must not be seen: a vanished
 * staff member, a spectator.
 *
 * <pre>{@code
 * PetHooks.hide(plugin, player);   // their pets despawn, and stay away
 * PetHooks.show(plugin, player);   // the same pets come back
 * }</pre>
 *
 * <p>While a player is hidden their active pets are despawned and any pet
 * they or the pet plugin call is held back; once no plugin hides them, every
 * pet that was put away or held back comes back at their side. Supported:
 * MCPets and MyPet. A server without either makes every call a no-op.
 *
 * <p>Holds are counted per owner and kept in memory; the player's quit and the
 * owner's disable drop them. Call from the player's thread.
 *
 * @since 1.604.0
 */
public final class PetHooks {

    private PetHooks() {
    }

    /**
     * Despawns the player's pets until {@code owner} shows them again.
     *
     * @return {@code false} when {@code owner} already hid them
     */
    public static boolean hide(Plugin owner, Player player) {
        return PetHookRuntime.hide(owner.getName(), player);
    }

    /** Ends {@code owner}'s hold; once nobody holds the player, their pets come back. */
    public static void show(Plugin owner, Player player) {
        PetHookRuntime.show(owner.getName(), player);
    }

    /** Whether any plugin is keeping the player's pets away. */
    public static boolean isHidden(UUID player) {
        return PetHookRuntime.isHidden(player);
    }

    /** The pet plugins on this server a hold puts away, for a setup screen or a log line. */
    public static List<String> plugins() {
        return PetHookRuntime.hooked();
    }
}
