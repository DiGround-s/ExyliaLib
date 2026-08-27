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

## Keys the database hands out

Most tables key a row by something it already is — a player's `UUID`, a kit's
id — and those need nothing beyond `@Id`. A row that has no natural key asks the
database for one:

```java
@Table("shield_design_library")
public record Design(
        @Id(generated = true) long id,
        @Column("owner_uuid") UUID owner,
        @Column("design_json") String json) {
}

// The zero is a placeholder. The database picks the real one.
long id = designs.insert(new Design(0L, owner, json)).join();

// Or keep the whole row, carrying its new key:
Design stored = designs.insertReturning(new Design(0L, owner, json)).join();
```

The key component must be `long`, `Long`, `int` or `Integer` — every engine
counts in integers, and a generated `UUID` would be invented by the library
rather than the database, so two servers would each invent their own.

`insert` always creates a row; it never updates one. A record that came back
from a read is written with `update`:

```java
Design published = designs.find(id).join().orElseThrow();
designs.update(published.withOneMoreUse()).join();
```

`update` never creates a row either. A key that matches nothing changes
nothing, which is the honest outcome for a design somebody deleted while it was
open on a screen — creating it would give it a different key from the one the
caller is holding.

The three refuse each other's records rather than guessing. `save` is an upsert
and needs to be told which row to merge with, so it is not for a generated key
at all: on a placeholder it would merge onto whichever row holds id 0. `insert`
on a record that brought its own key has nothing to hand out, and `update` on a
record still carrying the placeholder has no row to write to.

`saveAll` refuses a generated key outright, because a batch cannot answer with
the keys it was given — a caller would have stored a hundred rows nothing can
refer to.

The key comes back from the same statement that wrote the row
(`getGeneratedKeys`). Reading it afterwards with `SELECT MAX(id)` or
`LAST_INSERT_ID()` is the classic version of this bug: the pool hands out a
different connection, and on a table two servers write to the number belongs to
whoever inserted last.

Each engine spells the column its own way — `AUTO_INCREMENT` on H2, MySQL and
MariaDB, `GENERATED BY DEFAULT AS IDENTITY` on Postgres — and MongoDB, which has
no counter at all, keeps one per table in an `exylia_sequences` collection
advanced with an atomic `$inc`. All four are safe with several servers on one
database, and none of them reuses the key of a deleted row: anything that stored
the old id would otherwise start pointing at somebody else's.

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

### A column the table requires and no record declares

Every entity in Commons extended a base class carrying `created_at` and
`updated_at`, and its `CREATE TABLE` wrote them `NOT NULL` with no default. No
record in this library declares them, because nothing reads them — so the first
insert after a migration names only the columns the record has, and the row is
refused for a column the code no longer knows exists:

```
NULL not allowed for column "CREATED_AT"
INSERT INTO "shield_design_library" ("owner_uuid", "owner_name", "design_json", "uses") VALUES (?,?,?,?)
```

On the start that finds it, such a column **stops being required**. It keeps its
name and its data; only the refusal goes, and the change is named in the schema
summary.

The column is not dropped and not filled in. Dropping it would take the existing
rows' values with it, and a plugin that has not migrated yet still reads them.
Filling it in would mean inventing a value for a column that means something —
a creation time that is not when the row was created is worse than an absent
one.

Only columns with no default and no generated value are touched, so an identity
column or one the engine fills in for itself is left alone. A database that
refuses the alteration is left exactly as it was rather than kept from starting.

### A column too narrow for what the record now writes

A column declared `@Column(length = 64)` when an icon was a material name, and
`@Column(length = Column.UNBOUNDED)` now that it can hold a head or a whole
serialised item, is still `VARCHAR(64)` on every server that already has the
table. The first long value is refused, or — on a MySQL that is not strict —
truncated into something that no longer parses back:

```
Data too long for column 'icon' at row 1
```

Since 1.72.0, a **text** column narrower than the record declares is widened in
place on the start that finds it, and the change is named in the schema summary.
Its data and its name are untouched, and a `NOT NULL` column stays `NOT NULL`.

It never goes the other way. A column stored wider than the record declares is
left exactly as it is: it may be another plugin's view of the same table, and
narrowing it truncates rows. Numeric columns are not touched at all — precision
is not a width. A database that refuses the alteration is left as it was rather
than kept from starting, with a warning naming the table and the column, because
the next long write will fail.

### A table an older plugin created unquoted

Since 1.27.0, a table stored under the engine's own folding — `PLAYER_DATA`
where this library writes `"player_data"` — is renamed once, with its declared
columns, on the start that finds it.

The library always quotes a lower-case identifier, so on H2 and Postgres a
table created unquoted by an older plugin was found by the metadata lookup,
skipped by `CREATE TABLE`, and then missed by every statement:

```
Table "player_data" not found (candidates are: "PLAYER_DATA")
```

Every read and write for that record type failed, and the rows were still
there. Renaming is what makes them reachable; addressing the table in its own
case instead would spread each engine's folding rules through every statement
the library emits. Columns another plugin owns are never touched.

### A column an older plugin created unquoted

The same reconciliation runs per column, on **every** table that already
existed — not only on one whose name had to be renamed. A table can have some
columns folded and others not, and production did: on `killeffect_players`,
`uuid`, `name`, `killeffect` and `particlevisibility` were stored lower case
while the timestamp pair an ExyliaCommons-era base class added was stored
`CREATED_AT` / `UPDATED_AT`. The table name itself was fine, so nothing was
ever reconciled, and every statement failed:

```
Column "created_at" not found
```

For each column the record declares, one metadata read now decides between
three answers: stored exactly as addressed → nothing; stored under another
case → `ALTER TABLE ... RENAME COLUMN`; not there at all → `ALTER TABLE ...
ADD COLUMN`, as before. A column the record does not declare is neither
dropped nor renamed, whatever case it is in — it may belong to another
plugin's view of the same table.

Renames appear in the schema report alongside additions, so the one start that
repaired the table says so on the console and the next start says nothing.

## Failures are never silent

Since 1.27.0, an operation that fails prints through `Debug` against the plugin
that owns the repository, **even when nobody attached a handler**:

```
[ExyliaArmorTrims] A find on player_data (PlayerData) failed
```

A caller that does handle it — `exceptionally`, `handle`, `whenComplete` —
still gets it, and still gets it first. This only covers the dropped future.

The bug that prompted it: a plugin wrote `find(id).thenAccept(...)` with no
error branch, the read failed, and the menu it fed simply never opened. No
stack trace, no console line, nothing to search the logs for. Dropping the
future is the caller's mistake; a database error that reaches nobody at all
was the library's.

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

**A save issued from your `onDisable` still works.** That is the point at which
a plugin writes what it was holding in memory, so the release is deferred until
your teardown is over rather than done when the library first hears about it:

```java
@Override
public void onDisable() {
    players.repository().saveAll(cache.values()).join();
}
```

The reason it needs saying is that `PluginDisableEvent` — how the library finds
out — fires **before** `onDisable`, not after. Both Bukkit's `JavaPluginLoader`
and Paper's `PaperPluginInstanceManager` deliver the event and only then call
`setEnabled(false)`. Released on the event, the datasource dropped its last
owner one step ahead of the write that needed it, and the save came back as
`the database target is closing because its last plugin was disabled` with the
rows gone. Since 1.45.0 the datasource, and the modules layered on it, are
released one tick later instead.

The same holds for a full server stop, by a different route: there the deferred
task never runs, and the shutdown path releases everything after every plugin's
`onDisable` has finished.

## Enumerating what a plugin stores (since 1.36.0)

For a whole-plugin operation — an export, a diagnostic listing — there has to
be a way to ask which tables a plugin owns:

| Method | Contract |
| --- | --- |
| `Databases.find(String pluginName)` | that plugin's view, or `null` when it has never registered a repository |
| `Databases.registeredPlugins()` | the names of every plugin holding a view, sorted |
| `PluginDatabase.tables()` | its repositories by table name, sorted, unmodifiable |

`find` is a **lookup and deliberately not a factory**. `Databases.of(plugin)`
creates the view if it is missing, and creating one reads — and therefore
*writes* — that plugin's `database.yml`: looking an unrelated plugin up through
`of` would leave a config file behind in the data folder of a plugin that never
asked for one.

**A plugin appears once it has called `repository(...)`, not before.** One that
registers a record type lazily — on first use, behind a config switch, from a
subcommand — has fewer tables here than it eventually will, and nothing can
tell the difference from outside. Anything built on this should therefore
**name** the tables it found rather than only counting them: a silent short
list is the failure mode, and reading the names is the only way anybody
notices it.

`Repository.model()` and `Repository.storage()` also exist and are marked
`@ApiStatus.Internal`. They hand back `internal` types, which by this library's
own rule change without notice; they are public only because the transfer
module lives in another package. A plugin calling them has coupled itself to
something that carries no compatibility promise at all.

## Configuration

Each consumer gets `plugins/<Plugin>/database.yml`, generated with its own
explanation when it first calls `Databases.of(plugin)`:

```yaml
database:
  type: h2                  # h2 | mysql | mariadb | postgresql | mongodb

  settings:
    pool-size: 0            # 0 lets the library size it

  h2:
    file: database/h2       # relative to this plugin folder

  mysql:                    # and mariadb, and postgresql
    host: localhost
    port: 0                 # 0 uses the engine's default
    database: minecraft
    username: root
    password: ''

  mongodb:
    host: localhost
    port: 0
    database: minecraft
    username: ''
    password: ''
    connection-string: ''   # a full URI wins over everything above
```

Only the block `type` names is read; the rest sit there as documentation of what
switching engine would need.

The layout is ExyliaCommons', key for key, so a server already running commons
plugins keeps the credentials it has. The keys commons wrote that this library
does not implement — `server-id`, `write-behind`, `cache`, `redis`, and the
`settings` entries beyond `pool-size` — are removed on load and reported, because
a setting that no longer does what it says is worse than no setting at all.

A file written by ExyliaLib 1.24 to 1.30 was flat (`type`, `host`, `user`, …).
It is migrated to the layout above on first load, and its connection fields land
in the block its `type` names, not in `mysql` regardless.

## Where the code is

| | |
| --- | --- |
| Public API | `database/Databases`, `PluginDatabase`, `Repository`, `Query`, `Table`, `Column`, `Id`, `Indexed`, `Index`, `Codec`, `DatabaseException`, `DatabaseSettings` |
| Internal | `database/internal/EntityModel`, `ColumnModel`, `IndexModel`, `IndexCoverage`, `CodecRegistry`, `Codecs`, `Coercions`, `Storage`, `SqlStorage`, `MongoStorage`, `GatedStorage`, `SqlBackend`, `SqlSchema`, `SqlSettings`, `SchemaReport`, `Dialect`, `AnsiDialect`, `H2Dialect`, `MySQLDialect`, `MariaDBDialect`, `PostgresDialect`, `MongoBackend`, `MongoDocuments`, `DatabaseRuntime`, `TaskExecutor` |
| Moving a database | `database/transfer/` — see [transfer.md](transfer.md) |
| Lifecycle | `ExyliaLib` starts target management; each consumer loads `database.yml` on view creation and leases a target lazily |
