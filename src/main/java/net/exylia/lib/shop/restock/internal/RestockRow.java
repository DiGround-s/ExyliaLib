package net.exylia.lib.shop.restock.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Owner;
import net.exylia.lib.database.Table;

import java.util.UUID;

/**
 * What a player has taken of one offer in one restock window, for {@code Restock}.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * @param id     {@code shop|offer|player}
 * @param player the player
 * @param state  {@code window:taken}, one column so a compare-and-set covers both
 * @since 1.632.0
 */
@Table(value = "exylia_restock_takes", data = DataKind.PROGRESS)
public record RestockRow(
        @Id String id,
        @Owner @Indexed @Column UUID player,
        @Column String state) {
}
