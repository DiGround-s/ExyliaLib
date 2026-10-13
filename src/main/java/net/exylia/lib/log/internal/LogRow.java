package net.exylia.lib.log.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;
import net.exylia.lib.log.Logs;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One staff log entry, in the table {@code Logs} keeps: who did what to whom.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * <p>{@code actor} is {@code null} for the console, {@code target} for an
 * action on nobody in particular. {@code createdDay} is the epoch day, which is
 * what old entries are purged by: the database layer only deletes by equality.
 *
 * <p>{@code items} holds every stack the entry kept, one group after the
 * other, and {@code itemGroups} how to cut them back up: a line per group,
 * {@code <count> <label>}. Both are {@code null} on an entry that kept none.
 *
 * @since 1.296.0
 */
@Table("exylia_logs")
@Index(columns = {"plugin", "actor", "created_at"}, descending = {"created_at"})
@Index(columns = {"plugin", "target", "created_at"}, descending = {"created_at"})
@Index(columns = {"plugin", "created_at"}, descending = {"created_at"})
public record LogRow(
        @Id(length = 36) String id,
        @Column(length = 64) String plugin,
        @Column(length = 64) String server,
        @Column(value = "log_action", length = 48) String action,
        @Column(length = 36) String actor,
        @Column(length = 32) String actorName,
        @Column(length = 36) String target,
        @Column(length = 32) String targetName,
        @Column(length = Column.UNBOUNDED) String detail,
        @Column(length = 64) String world,
        @Column int x, @Column int y, @Column int z,
        @Indexed @Column("created_day") long createdDay,
        @Column("created_at") long createdAt,
        @Column(value = "item_groups", length = Column.UNBOUNDED) String itemGroups,
        @Column(length = Column.UNBOUNDED) ItemStack[] items) {

    /** Whether it kept any items to open. */
    public boolean hasItems() {
        return itemGroups != null && !itemGroups.isEmpty();
    }

    /** The kept stacks back in their groups; empty when none was kept. */
    public List<Logs.Group> groups() {
        if (!hasItems()) return List.of();
        ItemStack[] stacks = items == null ? new ItemStack[0] : items;
        List<Logs.Group> groups = new ArrayList<>();
        int at = 0;
        for (String line : itemGroups.split("\n")) {
            int space = line.indexOf(' ');
            if (space <= 0) continue;
            int count;
            try {
                count = Integer.parseInt(line.substring(0, space));
            } catch (NumberFormatException malformed) {
                continue;
            }
            int end = Math.min(stacks.length, at + Math.max(0, count));
            // Groups copy and skip nulls, which is what a slot that no longer reads comes back as.
            groups.add(new Logs.Group(line.substring(space + 1), Arrays.asList(stacks).subList(at, end)));
            at = end;
        }
        return groups;
    }

    /** {@link #itemGroups} for these groups, or {@code null} when there is none. */
    public static String encodeGroups(List<Logs.Group> groups) {
        if (groups == null || groups.isEmpty()) return null;
        StringBuilder text = new StringBuilder();
        for (Logs.Group group : groups) {
            if (!text.isEmpty()) text.append('\n');
            text.append(group.items().size()).append(' ')
                    .append(group.label() == null ? "" : group.label().replace('\n', ' '));
        }
        return text.toString();
    }

    /** {@link #items} for these groups, or {@code null} when none holds anything. */
    public static ItemStack[] encodeItems(List<Logs.Group> groups) {
        if (groups == null) return null;
        List<ItemStack> all = new ArrayList<>();
        groups.forEach(group -> all.addAll(group.items()));
        return all.isEmpty() ? null : all.toArray(ItemStack[]::new);
    }
}
