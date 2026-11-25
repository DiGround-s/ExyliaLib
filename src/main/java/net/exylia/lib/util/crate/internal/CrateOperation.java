package net.exylia.lib.util.crate.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Owner;
import net.exylia.lib.database.Table;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * One crate operation that happened: the ledger row that makes it happen once.
 *
 * <p>Its key is the player and the operation id, so a second write of the same
 * operation is refused by the database itself. It is written in the same
 * transaction as the keys it moved ({@link net.exylia.lib.database.Atomic}), so
 * a row here means the change landed and no row means it did not. Rows older
 * than the retention are deleted by {@link #day}.
 *
 * @param id     {@link #key}: the plugin, the player and the operation
 * @param plugin the plugin's global row owner
 * @param uuid   the player
 * @param entry  what it did, as one {@link CrateJournal} entry
 * @param day    the day it was written, since the epoch, for the retention
 */
@Table(value = "exylia_crate_operations", data = DataKind.ACCOUNT)
public record CrateOperation(
        @Id(length = 191) String id,
        @Column(length = 64) String plugin,
        @Owner @Column UUID uuid,
        @Column(length = Column.UNBOUNDED) String entry,
        @Indexed @Column int day) {

    /** The ledger key; an operation id too long for it is hashed, so the key is still one per operation. */
    public static @NotNull String key(@NotNull String plugin, @NotNull UUID uuid, @NotNull String operation) {
        String key = plugin + ':' + uuid + '|' + operation;
        return key.length() <= 191 ? key : plugin + ':' + uuid + "|#" + sha256(operation);
    }

    public static @NotNull CrateOperation of(@NotNull String plugin, @NotNull UUID uuid,
                                             @NotNull CrateJournal.Entry entry) {
        return new CrateOperation(key(plugin, uuid, entry.id()), plugin, uuid,
                CrateJournal.empty().with(entry).format(), today());
    }

    /** What it did, or {@code null} when the stored text cannot be read. */
    public @Nullable CrateJournal.Entry read() {
        List<CrateJournal.Entry> entries = CrateJournal.parse(entry).entries();
        return entries.isEmpty() ? null : entries.get(0);
    }

    public static int today() {
        return (int) TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis());
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
