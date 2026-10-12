package net.exylia.lib.notice;

import net.exylia.lib.text.Text;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Collapses one category's waiting notices into a few lines on join.
 *
 * <pre>{@code
 * notices.summary("sold", (player, sold) -> List.of(Text.from(this,
 *         "%prefix% {letters}While you were away {highlight}%count% items {letters}sold for {success}%total%")
 *         .with("%count%", sold.size())
 *         .with("%total%", Formats.money(sold.stream().mapToDouble(n -> n.number("price")).sum()))));
 * }</pre>
 *
 * <p>Runs on the player's thread, so it reads only what it is handed: no
 * database, no blocking call.
 *
 * @since 1.278.0
 */
@FunctionalInterface
public interface NoticeSummary {

    /**
     * The lines to show for these notices.
     *
     * @param player  who is reading
     * @param notices the category's notices, oldest first, never empty
     * @return the lines, sent in order; empty shows nothing
     */
    @NotNull List<Text> render(@NotNull Player player, @NotNull List<Notice> notices);
}
