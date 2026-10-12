package net.exylia.lib.toast;

import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.text.Text;
import net.exylia.lib.toast.internal.ToastPackets;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * The advancement popup in the top-right corner, for anything a plugin wants
 * noticed without a line of chat: an item sold, a quest step done, a booster
 * that started.
 *
 * <pre>{@code
 * Toasts.send(player, Material.EMERALD, "{success}Sold {highlight}x16 Diamond", Toasts.Frame.GOAL);
 * }</pre>
 *
 * <h2>Nothing is kept</h2>
 * The toast is a fake advancement that exists on one client for one packet:
 * it is added already done and removed straight after. It never reaches the
 * advancements screen, the server never grants anything, nothing is saved and
 * nothing is announced in chat.
 *
 * <h2>Without PacketEvents</h2>
 * The title is shown the way {@code toasts.fallback} in ExyliaLib's
 * {@code config.yml} says instead — {@code action-bar} (the default),
 * {@code title}, {@code chat} or {@code none} — so a plugin never has to check.
 *
 * <h2>Text</h2>
 * The title is Exylia notation: palette placeholders, {@code &} codes and
 * gradients, filled per player like any other message. A toast has room for
 * about 25 characters on one line; the client wraps a longer one onto a second.
 *
 * <h2>Threads</h2>
 * Safe from any thread: both the packets and the fallback are packet writes,
 * which touch no world state.
 *
 * <p>In a sequence, {@code [TOAST] EMERALD;GOAL;{success}Sold!} does the same.
 *
 * @since 1.278.0
 */
public final class Toasts {

    /** The frame drawn around the icon, which is also the sound the client plays. */
    public enum Frame {
        /** The plain square, silent: an ordinary advancement. */
        TASK,
        /** The rounded frame: a goal reached. */
        GOAL,
        /** The spiked frame and the challenge fanfare: something rare. */
        CHALLENGE;

        /**
         * Reads a frame by name, ignoring case.
         *
         * @param name {@code task}, {@code goal} or {@code challenge}
         * @return the frame, or {@link #TASK} when the name is none of them
         */
        public static @NotNull Frame of(@NotNull String name) {
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return TASK;
            }
        }
    }

    private Toasts() {
        throw new AssertionError("No instances.");
    }

    /**
     * Pops a toast with a material for its icon.
     *
     * @param player who sees it
     * @param icon   the icon; air is drawn as paper, since air has no picture
     * @param title  Exylia notation, filled for this player
     * @param frame  the frame and sound
     */
    public static void send(@NotNull Player player, @NotNull Material icon, @NotNull String title,
                            @NotNull Frame frame) {
        send(player, new ItemStack(icon.isAir() ? Material.PAPER : icon), title, frame);
    }

    /**
     * Pops a toast with an item for its icon, so a custom model or a head shows.
     *
     * @param player who sees it
     * @param icon   the icon, drawn as it is; the stack is not kept or changed
     * @param title  Exylia notation, filled for this player
     * @param frame  the frame and sound
     */
    public static void send(@NotNull Player player, @NotNull ItemStack icon, @NotNull String title,
                            @NotNull Frame frame) {
        if (!player.isOnline()) {
            return;
        }
        Component text = Text.of(title).forPlayer(player).build();
        if (Packets.isAvailable()) {
            ToastPackets.send(player, icon.getType().isAir() ? new ItemStack(Material.PAPER) : icon,
                    text, frame.name());
            return;
        }
        switch (LibrarySettings.get().toasts().fallback().trim().toLowerCase(Locale.ROOT)) {
            case "none", "" -> { }
            case "title" -> player.showTitle(net.kyori.adventure.title.Title.title(Component.empty(), text));
            case "chat" -> player.sendMessage(text);
            default -> player.sendActionBar(text);
        }
    }
}
