package net.exylia.fixture;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;

/** CarryTest's tables, outside {@code net.exylia.lib}, where the library's own shared tables live. */
public final class CarryRecords {

    private CarryRecords() {
    }

    @Table("carry_balances")
    public record Balance(@Id String id, @Column long amount) {
    }

    @Table("carry_old_stats")
    public record OldStats(@Id String id, @Column String clan, @Column long kills) {
    }

    @Table(value = "carry_clan_stats", previous = "carry_old_stats")
    public record ClanStats(@Id String id, @Column String clan, @Column long kills) {
    }

    @Table("carry_shared_name")
    public record Foreign(@Id String uuid, @Column int streak) {
    }

    @Table(value = "carry_mine_stats", previous = "carry_shared_name")
    public record Mine(@Id String id, @Column String clan, @Column long kills) {
    }

    @Table("carry_arenas")
    public record Arena(@Id String id, @Column String world) {
    }
}
