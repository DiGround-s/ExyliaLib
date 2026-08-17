# Database

Records stored in a database, configured by the plugin that owns them.

```java
@Table("practice_player_stats")
public record PlayerStats(
        @Id UUID uuid,
        @Column int elo,
        @Column int kills,
        @Indexed @Column(value = "kit_id", length = 64) String kitId) {
}

private Repository<PlayerStats> stats;

@Override
public void onEnable() {
    stats = Databases.of(this).repository(PlayerStats.class);
}

// later, anywhere
stats.find(player.getUniqueId()).thenAccept(found ->
        tasks.runAtEntity(player, () -> found.ifPresent(this::show)));
```

That is the whole setup. No connection, no credentials, no schema: the table is
created from the record, and the engine is whatever the consumer's
`plugins/<Plugin>/database.yml` says.

## One client per datasource

Each consumer plugin owns `database.yml`; ExyliaLib owns the client it needs.
The default embedded H2 file is therefore `plugins/<Plugin>/database/h2`, so two
plugins that leave their files alone never share data by accident.

ExyliaLib resolves every setting that affects a connection and shares exactly
one SQL/Mongo client among plugins whose resolved settings are identical. This
avoids duplicate pools when plugins intentionally point at the same datasource.
Different settings, including credentials, pool size, URL properties, or an H2
file path, are separate targets. Credentials are used for matching but are never
printed in logs or diagnostics.

**H2 is the default and the fallback.** A file next to the consumer plugin: no daemon, no
credentials, nothing to install. It is never YAML — a YAML "database" cannot
index, cannot sort, cannot count without reading everything, and rewrites the
whole file to change one row.

## What a record may hold

| Java | Stored as | Notes |
| --- | --- | --- |
| `int`, `long`, `double`, `float`, `short`, `byte`, `boolean` | the engine's own type | so `ORDER BY` is numeric |
| `String` | `VARCHAR(n)`, or the largest text type for `Column.UNBOUNDED` | |
| `UUID` | `VARCHAR(36)` | matches every row already stored |
| `BigDecimal` | `DECIMAL(38,10)` | for money; a `double` cannot hold a balance |
| enum | its **name** | never the ordinal |
| `ItemStack`, `ItemStack[]` | Base64, Bukkit's own versioned format | |
| `Location` | `world,x,y,z,yaw,pitch` | |
| `List<X>` | a JSON array | `X` needs a codec or be a plain value |
| anything else | whatever a registered `Codec` says | |

An unsupported type is a **registration error**, at enable, naming the
component. It is never a column that quietly stores `toString()`.

```java
Databases.codec(MyThing.class, Codec.of(MyThing::pack, MyThing::unpack));
```

## Everything is a future

There is no synchronous form of anything, on purpose. A database call takes as
long as the database takes, and the one thread that must never wait for it is
the one running the game. ExyliaCommons offered both, and the ecosystem is full
of blocking calls made from an event handler because the blocking method existed
and was one word shorter.

Come back to the game with `Tasks`, as everywhere else:

```java
stats.find(id).thenAccept(found ->
        tasks.runAtEntity(player, () -> show(found)));
```

## Reading

```java
stats.find(uuid);                       // one row by id
stats.findAll();                        // every row — fine for arenas, not for players
stats.exists(uuid);
stats.count();

stats.where("kit_id", "boxing")         // filter
     .orderByDescending("elo")          // sort
     .limit(10)                         // and stop
     .find();

stats.where("kit_id", "boxing").count();   // counted in the database, nothing read
```

Filters are equalities, which is what all forty-nine existing lookups in the
ecosystem are. Anything richer belongs in the plugin: a repository that grows an
expression tree has become an ORM.

## Writing

```java
stats.save(row);            // insert or update, one round trip
stats.saveAll(rows);        // batched — about 8x faster on MySQL
stats.delete(uuid);

stats.where("played_at", cutoff).delete();   // deleted in the database
```

`save` completes when the row is **durable**. There is no flush interval:
Commons buffered writes for thirty seconds by default, so a crash discarded half
a minute of every player's progress on the whole server. Ignore the future if
you do not care; wait for it before telling a player their purchase worked.

## Indexes

Without one, a database answers a query by reading every row. Invisible on a
test server with forty rows; the entire cost on a live one with four hundred
thousand.

```java
@Indexed @Column("player_uuid") UUID playerUuid     // looked up by
```

For a leaderboard, one column is not enough:

```java
@Table("practice_player_stats")
@Index(columns = {"kit_id", "elo"}, descending = {"elo"})
@Index(columns = {"kit_id", "wins"}, descending = {"wins"})
public record PlayerStats(...) { }
```

`(kit_id, elo DESC)` is already in the answer's order, so the database reads ten
rows and stops however many players the kit has. Two separate single-column
indexes are **not** the same thing and do not help: a database uses one of them.

A query that filters or sorts on a column no index covers is **reported once** in
the console. A missing index is invisible until the table is large, which is the
one bug this module exists to prevent.

## Engines

`h2`, `mysql`, `mariadb`, `postgresql`, `mongodb`. Each is used the way it
should be rather than through a lowest common denominator:

| | |
| --- | --- |
| H2 | `MERGE INTO ... KEY (...)`; embedded, small pool, no network |
| MySQL | `INSERT ... AS new ON DUPLICATE KEY UPDATE`, `rewriteBatchedStatements` |
| MariaDB | `ON DUPLICATE KEY UPDATE ... VALUES(col)` — it **cannot parse** MySQL's `AS new` |
| PostgreSQL | `ON CONFLICT (pk) DO UPDATE`, `reWriteBatchedInserts` |
| MongoDB | real BSON types, `replaceOne` upsert, `bulkWrite`, compound indexes |

Some of the differences are traps rather than preferences, and each one is a
test:

- `FLOAT` is 4 bytes on MySQL and 8 everywhere else, so a `double` written to
  one truncates **on MySQL only**. `REAL` and `DOUBLE PRECISION` are emitted.
- MySQL rejects `CREATE INDEX IF NOT EXISTS`; the other three accept it.
- MySQL rejects `OFFSET ... FETCH`; Postgres rejects `LIMIT ?,?`.
- An indexed `VARCHAR` above 768 characters errors on MySQL and silently becomes
  a prefix index on MariaDB — where a `UNIQUE` constraint then stops enforcing
  uniqueness on the full value.
- H2 uppercases unquoted identifiers, Postgres lowercases them. Everything is
  quoted lowercase, so one name works on all five.
- H2 throws at connect if `AUTO_SERVER=TRUE` meets `DB_CLOSE_ON_EXIT=FALSE`.

### What Mongo does differently

Worth knowing if a server runs it:

- A record gaining a component needs no migration; old documents simply lack the
  field and read as absent. SQL runs `ALTER TABLE`.
- `saveAll` is not a transaction. One bad document does not stop the rest and
  nothing rolls back.
- A limited delete is two round trips and not atomic: Mongo has no
  `DELETE ... LIMIT`.
- Deep pagination is slow, because `skip(n)` is O(n).

## Reading data ExyliaCommons wrote

Every stored format is byte for byte what Commons produced, because there are
ninety-six tables of live data in those formats. A server swaps the library and
reads its rows unchanged.

Two deliberate differences, both bug fixes rather than format changes:

- A `Location` is written in a fixed locale. Commons used the default one, so a
  server running under `es_ES` wrote `world,10,50,64,00,...` — a string whose
  commas are both separators and decimal points. Rows written that way were
  already unreadable.
- An empty list is written as `[]` rather than `null`, so "no kits" and "this
  column did not exist yet" stop being the same value. Both still read back as
  an empty list, and reading absence gives an empty list rather than `null` —
  every list consumer in the ecosystem was either null-checking or a latent NPE.

## Threads

Every operation runs off the game threads, through `Tasks`, on the pool the
server already has. No executor is created: `runAsync` **is** a pool, and adding
another loses the automatic cancellation on disable.

Codecs run on that background thread, so a codec may serialise an `ItemStack`
and look a `World` up by name; it may not spawn anything.

## Lifecycle

Disabling a plugin releases its repositories and its datasource lease. A shared
target remains open until its final consumer releases it, then closes. ExyliaLib
releases every remaining target on shutdown, after everything pending is written.

## Configuration

Each consumer gets `plugins/<Plugin>/database.yml`, generated with its own
explanation when it first calls `Databases.of(plugin)`:

```yaml
type: h2                    # h2 | mysql | mariadb | postgresql | mongodb
file: database/h2           # embedded only, relative to this plugin folder
host: localhost
port: 0                     # 0 uses the engine's default
database: exylia
user: exylia
password: ''
pool-size: 0                # 0 lets the library size it
```

## Where the code is

| | |
| --- | --- |
| Public API | `database/Databases`, `PluginDatabase`, `Repository`, `Query`, `Table`, `Column`, `Id`, `Indexed`, `Index`, `Codec`, `DatabaseException`, `DatabaseSettings` |
| Internal | `database/internal/EntityModel`, `ColumnModel`, `IndexModel`, `IndexCoverage`, `CodecRegistry`, `Codecs`, `Coercions`, `Storage`, `SqlStorage`, `MongoStorage`, `GatedStorage`, `SqlBackend`, `SqlSchema`, `SqlSettings`, `SchemaReport`, `Dialect`, `AnsiDialect`, `H2Dialect`, `MySQLDialect`, `MariaDBDialect`, `PostgresDialect`, `MongoBackend`, `MongoDocuments`, `DatabaseRuntime`, `TaskExecutor` |
| Lifecycle | `ExyliaLib` starts target management; each consumer loads `database.yml` on view creation and leases a target lazily |
