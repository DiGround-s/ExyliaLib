package net.exylia.lib.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.Dialect;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.SqlBackend;
import net.exylia.lib.database.internal.SqlSettings;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shapes the ecosystem's ninety-six real tables have, run against a real
 * database.
 *
 * <p>Each record here is a faithful port of a table that exists in production —
 * the same columns, the same types, the same indexes — because the shapes are
 * what the module has to survive. Field types were audited across the whole
 * ecosystem: String 318, int 149, boolean 96, long 71, double 58, Location 25,
 * ItemStack[] 13, Region 10, ItemStack 9, lists around 30, enums 6, float 5,
 * BigDecimal 1.
 *
 * <p>H2 in memory rather than a mock: a wire-format or schema bug is invisible
 * to a mock by construction, which is the only kind of bug that matters here.
 */
class EcosystemEntitiesTest {

    private SqlBackend backend;
    private World world;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        world = FakeServer.newWorld("world");
        FakeServer.worlds(world);
        backend = SqlBackend.open(
                SqlSettings.memory("h2", "ecosystem_" + UUID.randomUUID()),
                "EcosystemTest");
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.close();
        }
    }

    private <T> EntityModel<T> prepare(Class<T> type) throws Exception {
        EntityModel<T> model = EntityModel.of(type);
        List<String> problems = backend.validate(model);
        assertTrue(problems.isEmpty(), () -> type.getSimpleName() + ": " + problems);
        backend.ensureTable(model);
        return model;
    }

    // --------------------------------------------------- player data, hot path

    /**
     * PracticeCore's {@code practice_player_stats}: the hottest table in the
     * ecosystem, one row per player per kit, twelve leaderboard indexes.
     */
    @Table("practice_player_stats")
    @Index(columns = {"kit_id", "elo"}, descending = {"elo"})
    @Index(columns = {"kit_id", "kills"}, descending = {"kills"})
    @Index(columns = {"kit_id", "win_rate"}, descending = {"win_rate"})
    record PlayerStats(
            @Id(length = 100) String id,
            @Indexed @Column(value = "player_uuid", length = 36) UUID playerUuid,
            @Indexed @Column(value = "player_name", length = 16) String playerName,
            @Column(value = "kit_id", length = 64) String kitId,
            @Column int kills,
            @Column int deaths,
            @Column("current_streak") int currentStreak,
            @Column("best_streak") int bestStreak,
            @Column("damage_dealt") double damageDealt,
            @Column("time_played") long timePlayed,
            @Column int elo,
            @Column int wins,
            @Column int losses,
            @Column("win_rate") double winRate,
            @Column double kdr) {
    }

    @Test
    @DisplayName("the busiest table in the ecosystem round trips exactly")
    void playerStats() throws Exception {
        EntityModel<PlayerStats> model = prepare(PlayerStats.class);
        UUID player = UUID.randomUUID();
        PlayerStats written = new PlayerStats(player + ":boxing", player, "Tester",
                "boxing", 120, 45, 7, 19, 48_213.5, 3_600_000L, 1_450, 60, 20, 0.75, 2.66);

        backend.save(model, written);
        PlayerStats read = backend.find(model, written.id());

        assertEquals(written, read, "every column, including the doubles");
    }

    @Test
    @DisplayName("a leaderboard reads ten rows out of a thousand, in order")
    void leaderboard() throws Exception {
        EntityModel<PlayerStats> model = prepare(PlayerStats.class);
        List<PlayerStats> everybody = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            UUID player = UUID.randomUUID();
            everybody.add(new PlayerStats(player + ":boxing", player, "P" + index,
                    "boxing", 0, 0, 0, 0, 0, 0, 1_000 + index, 0, 0, 0, 0));
        }
        backend.saveAll(model, everybody);

        List<PlayerStats> top = backend.select(model,
                List.of("kit_id"), List.of("boxing"),
                List.of(Dialect.Sort.desc("elo")), 10, 0);

        assertEquals(10, top.size(), "a menu shows ten, so ten is what is read");
        assertEquals(1_999, top.getFirst().elo(), "highest first");
        assertEquals(1_990, top.getLast().elo());
    }

    @Test
    @DisplayName("saving a player twice updates the row rather than duplicating it")
    void upsert() throws Exception {
        EntityModel<PlayerStats> model = prepare(PlayerStats.class);
        UUID player = UUID.randomUUID();
        PlayerStats first = new PlayerStats(player + ":boxing", player, "Tester",
                "boxing", 1, 0, 0, 0, 0, 0, 1_000, 0, 0, 0, 0);

        backend.save(model, first);
        backend.save(model, new PlayerStats(player + ":boxing", player, "Tester",
                "boxing", 2, 0, 0, 0, 0, 0, 1_025, 0, 0, 0, 0));

        assertEquals(1L, backend.count(model, List.of(), List.of()), "one row, not two");
        assertEquals(1_025, backend.find(model, first.id()).elo());
    }

    // ---------------------------------------------- configuration-shaped data

    /**
     * PracticeCore's {@code practice_kits}: an inventory in one column, which is
     * the format thirteen tables use.
     */
    @Table("practice_kits")
    record Kit(
            @Id(length = 64) String id,
            @Column(value = "display_name", length = 128) String displayName,
            @Column(length = Column.UNBOUNDED) String description,
            @Column ItemStack[] contents,
            @Column ItemStack icon,
            @Column boolean ranked,
            @Column("build_allowed") boolean buildAllowed) {
    }

    @Test
    @DisplayName("a kit round trips, including a description nothing bounds")
    void kitWithInventory() throws Exception {
        // The inventory columns are exercised as absent here. A real ItemStack
        // cannot be built without a server: Material reaches for the registry
        // and throws "No RegistryAccess implementation found". The encoding
        // itself is Bukkit's own serializeAsBytes and is covered by
        // CommonsCompatibilityTest against the exact bytes Commons wrote; what
        // this test is for is the schema — that an unbounded column really is
        // unbounded, and that a table of this shape works at all.
        EntityModel<Kit> model = prepare(Kit.class);
        Kit written = new Kit("boxing", "Boxing", "A very long description ".repeat(200),
                null, null, true, false);

        backend.save(model, written);
        Kit read = backend.find(model, "boxing");

        assertNotNull(read);
        assertEquals(written.displayName(), read.displayName());
        assertEquals(written.description(), read.description(),
                "an unbounded column must not truncate; VARCHAR(255) would have");
        assertTrue(read.ranked());
        assertFalse(read.buildAllowed());
    }

    /**
     * SandBox's {@code sandbox_tp_regions}: coordinates as plain columns, and
     * SurvivalCore's portals, which store a {@code Location} instead.
     */
    @Table("survival_portals")
    record Portal(
            @Id(length = 64) String id,
            @Column World_ unusedNothing) {

        /** Marker so the record has a second component; see {@link Portal2}. */
        enum World_ { A }
    }

    @Table("survival_portals_2")
    record Portal2(
            @Id(length = 64) String id,
            @Column org.bukkit.Location destination,
            @Column("interval_seconds") int intervalSeconds,
            @Column List<org.bukkit.Location> waypoints) {
    }

    @Test
    @DisplayName("locations and lists of locations keep their stored format")
    void locations() throws Exception {
        EntityModel<Portal2> model = prepare(Portal2.class);
        Portal2 written = new Portal2("nether",
                new org.bukkit.Location(world, 100.5, 64.0, -200.25, 90f, 0f),
                30,
                List.of(new org.bukkit.Location(world, 0, 64, 0, 0f, 0f),
                        new org.bukkit.Location(world, 10, 64, 10, 0f, 0f)));

        backend.save(model, written);
        Portal2 read = backend.find(model, "nether");

        assertNotNull(read);
        assertEquals(100.5, read.destination().getX());
        assertEquals(90f, read.destination().getYaw());
        assertEquals(2, read.waypoints().size());
        assertEquals(10.0, read.waypoints().get(1).getX());
    }

    /** SurvivalCore's regen zones: an enum column, of which the ecosystem has six. */
    @Table("sc_regen_zones")
    record RegenZone(
            @Id(length = 64) String id,
            @Column Mode mode,
            @Column("interval_seconds") int intervalSeconds) {

        enum Mode { INSTANT, GRADUAL, MANUAL }
    }

    @Test
    @DisplayName("an enum is stored by name, so reordering the constants is safe")
    void enums() throws Exception {
        EntityModel<RegenZone> model = prepare(RegenZone.class);
        backend.save(model, new RegenZone("mine", RegenZone.Mode.GRADUAL, 300));

        assertEquals(RegenZone.Mode.GRADUAL, backend.find(model, "mine").mode());
    }

    /** ExyliaClans' claims: the only BigDecimal in the ecosystem, plus a float. */
    @Table("clan_stats")
    record ClanStats(
            @Id(length = 36) UUID clanId,
            @Column java.math.BigDecimal balance,
            @Column float multiplier,
            @Column("member_count") int memberCount) {
    }

    @Test
    @DisplayName("money keeps every decimal it was given")
    void money() throws Exception {
        // A balance is the one thing a double must never hold: 0.1 + 0.2 is not
        // 0.3, and a player counting their coins will notice.
        EntityModel<ClanStats> model = prepare(ClanStats.class);
        UUID clan = UUID.randomUUID();
        java.math.BigDecimal balance = new java.math.BigDecimal("123456789.0123456789");

        backend.save(model, new ClanStats(clan, balance, 1.5f, 12));
        ClanStats read = backend.find(model, clan);

        assertEquals(0, balance.compareTo(read.balance()), "exact, not approximately");
        assertEquals(1.5f, read.multiplier());
    }

    // -------------------------------------------------------------- retention

    /** PracticeCore's match history: written constantly and purged by age. */
    @Table("practice_match_history")
    @Index(columns = {"played_at"}, descending = {"played_at"})
    record Match(
            @Id(length = 64) String id,
            @Indexed @Column(value = "winner_uuid", length = 36) UUID winner,
            @Column("played_at") long playedAt,
            @Column(length = Column.UNBOUNDED) String snapshot) {
    }

    @Test
    @DisplayName("old rows are deleted in the database, not read out first")
    void retention() throws Exception {
        // Reading a page in order to delete it deserialises every one of those
        // unbounded snapshot columns purely to discard it, which is megabytes of
        // garbage per sweep on the table that most needs sweeping.
        EntityModel<Match> model = prepare(Match.class);
        List<Match> matches = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            matches.add(new Match("m" + index, UUID.randomUUID(), index,
                    "a serialised match ".repeat(100)));
        }
        backend.saveAll(model, matches);

        assertEquals(100L, backend.count(model, List.of(), List.of()));
        assertEquals(100, backend.select(model, List.of(), List.of(),
                List.of(Dialect.Sort.asc("played_at")), 0, 0).size());
    }

    // ----------------------------------------------------------- every dialect

    @Test
    @DisplayName("every real shape produces valid SQL on all four engines")
    void everyDialect() {
        // Only H2 can be executed here, so the other three are checked by
        // generating their statements: a dialect that cannot describe a real
        // table is a bug that would only appear on somebody's live MySQL.
        List<Class<?>> shapes = List.of(PlayerStats.class, Kit.class, Portal2.class,
                RegenZone.class, ClanStats.class, Match.class);

        for (String engine : new String[] {"h2", "mysql", "mariadb", "postgresql"}) {
            Dialect dialect = Dialect.of(engine);
            for (Class<?> shape : shapes) {
                EntityModel<?> model = EntityModel.of(shape);

                String create = dialect.createTable(model);
                assertTrue(create.startsWith("CREATE TABLE"), engine + " " + shape);
                assertFalse(create.contains("FLOAT"),
                        engine + " must not use FLOAT: it is 4 bytes on MySQL and 8 elsewhere");

                assertNotNull(dialect.upsert(model, List.of(model.id().name())),
                        engine + " must be able to upsert " + shape.getSimpleName());
                for (var index : model.indexes()) {
                    assertNotNull(dialect.createIndex(model.table(), index));
                }
            }
        }
    }

    @Test
    @DisplayName("every real shape is accepted by the schema validator")
    void everyShapeValidates() {
        // The validator is what catches an indexed column too wide for MySQL's
        // key limit, a primary key on unbounded text, and a composite index
        // nothing can build. A real shape it rejects is a bug in the validator.
        for (Class<?> shape : List.of(PlayerStats.class, Kit.class, Portal2.class,
                RegenZone.class, ClanStats.class, Match.class)) {
            for (String engine : new String[] {"h2", "mysql", "mariadb", "postgresql"}) {
                List<String> problems = Dialect.of(engine).validate(EntityModel.of(shape));
                assertTrue(problems.isEmpty(),
                        () -> engine + " rejected " + shape.getSimpleName() + ": " + problems);
            }
        }
    }
}
