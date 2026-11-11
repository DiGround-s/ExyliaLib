package net.exylia.lib.dialogue;

import net.exylia.lib.dialogue.internal.DialogueRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Opens {@link Dialogue}s: RPG conversations in a box at the bottom of the screen, with no resource pack.
 *
 * <p>On the HUD (the default) the player keeps walking and looking round: a sneak, a click on the air or on an NPC
 * goes on (the first press finishes the line), and under the last line the wheel or the number keys move between
 * the answers while the held item stays put. On a screen they stand still and click. A Bedrock player reads the
 * lines in chat and answers in a form. One conversation per player: opening another ends the first as cancelled.
 *
 * @since 1.590.0
 */
public final class Dialogues {

    private final Plugin plugin;

    private Dialogues(Plugin plugin) {
        this.plugin = plugin;
    }

    public static @NotNull Dialogues of(@NotNull Plugin plugin) {
        return new Dialogues(plugin);
    }

    /**
     * Starts the conversation, from any thread.
     *
     * @return completes on the player's thread when it ends: read through, answered, or cancelled (they left, died,
     * closed the screen, or {@link #close} or another dialogue took its place)
     */
    public @NotNull CompletableFuture<Dialogue.Result> open(@NotNull Player player, @NotNull Dialogue dialogue) {
        return DialogueRuntime.open(plugin, player, dialogue);
    }

    /** Ends whatever conversation the player is in as cancelled; nothing when there is none. */
    public static void close(@NotNull Player player) {
        DialogueRuntime.close(player);
    }

    /** Whether the player is in a conversation, from any plugin. */
    public static boolean talking(@NotNull Player player) {
        return DialogueRuntime.talking(player);
    }
}
