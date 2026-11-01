package net.exylia.lib.database.wipe.internal;

import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Owner;
import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.Table;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.RecordComponent;
import java.util.UUID;

/**
 * One table as a wipe sees it: whose it is, what it holds, and whose rows in it
 * are a player's.
 *
 * @param plugin     who it is listed under: the plugin, or {@code ExyliaLib} for
 *                   a library table shared by every plugin of one database
 * @param source     the plugin whose database view holds the repository, which
 *                   is whose dump backs it up and whose writes are frozen
 * @param location   where the table physically lives ({@link PluginDatabase#location()})
 * @param table      the table name
 * @param kind       what it holds
 * @param owner      the {@link Owner} component, or {@code null} for none
 * @param ownerText  whether that component stores the uuid as text
 * @param tenant     for a library table shared by several plugins, the
 *                   {@code plugin} value this entry's rows carry; {@code null}
 *                   when every row is this entry's
 * @param repository the repository, {@code null} only in tests
 */
public record WipeTable(@NotNull String plugin, @NotNull String source, @NotNull String location,
                        @NotNull String table, @NotNull DataKind kind, @Nullable String owner,
                        boolean ownerText, @Nullable String tenant, @Nullable Repository<?> repository) {

    /** What the library's shared tables are listed under. */
    public static final String LIBRARY = "ExyliaLib";

    /** The column a library table names its plugin in, when several plugins share it. */
    static final String TENANT_COLUMN = "plugin";

    /**
     * Reads what a registered repository declares.
     *
     * @param source     the plugin whose view holds it
     * @param location   where that view's tables live
     * @param repository the repository
     * @return the table
     */
    public static @NotNull WipeTable of(@NotNull String source, @NotNull String location,
                                        @NotNull Repository<?> repository) {
        Class<?> type = repository.type();
        Table declared = type.getAnnotation(Table.class);
        DataKind kind = declared == null ? DataKind.UNSPECIFIED : declared.data();
        String owner = null;
        boolean text = false;
        boolean tenant = false;
        boolean library = library(type);
        for (RecordComponent component : type.getRecordComponents()) {
            if (component.isAnnotationPresent(Owner.class) && owner == null) {
                owner = component.getName();
                text = component.getType() == String.class;
            }
            if (library && component.getName().equals(TENANT_COLUMN) && component.getType() == String.class) {
                tenant = true;
            }
        }
        // A library table that names its plugin in a column holds every plugin's
        // rows side by side (logs, notices, crate players): each plugin's entry
        // is only its own rows. One without that column is the library's own
        // shared data and is listed once, under the library.
        return new WipeTable(library && !tenant ? LIBRARY : source, source, location, repository.table(), kind,
                owner, text, tenant ? source : null, repository);
    }

    /** Whether a record ships in the library's own jar, rather than in a plugin's. */
    static boolean library(Class<?> type) {
        try {
            var mine = WipeTable.class.getProtectionDomain().getCodeSource();
            var theirs = type.getProtectionDomain().getCodeSource();
            return mine != null && theirs != null && mine.getLocation().equals(theirs.getLocation());
        } catch (SecurityException unknown) {
            return false;
        }
    }

    /** What an owner column holds for a player, in record form. */
    @NotNull Object ownerValue(@NotNull UUID player) {
        return ownerText ? player.toString() : player;
    }

    /** The key two views of one physical table share. */
    @NotNull String key() {
        return location + '|' + table.toLowerCase(java.util.Locale.ROOT) + '|' + (tenant == null ? "" : tenant);
    }

    /** The same table under another kind. */
    @NotNull WipeTable withKind(@NotNull DataKind other) {
        return new WipeTable(plugin, source, location, table, other, owner, ownerText, tenant, repository);
    }
}
