# Transfer module

A plugin's whole database, written to one file and read back. What a server
owner needs when moving from H2 to MySQL, when copying a network's data onto a
test server, or when taking something off a box before it is rebuilt.
Since 1.36.0.

Entry point: `net.exylia.lib.database.transfer.Transfers`.

```java
Transfers.of(this).export(getDataFolder().toPath().resolve("dumps"))
        .thenAccept(report -> {
            if (!report.successful()) {
                report.problems().forEach(getLogger()::warning);
            }
        });
```

Or, without writing any code at all:

```
/exylialib export <plugin>
/exylialib import <plugin> <file> [force]
```

It is **not a backup tool** and does not try to be. It moves rows between two
databases this library can already talk to.

## API

`Transfers` (static):

| Method | Contract |
| --- | --- |
| `of(plugin)` | that plugin's view; throws if ExyliaLib has not enabled |
| `init(plugin)` / `releaseAll()` | lifecycle, driven by ExyliaLib — a consumer never calls these |

`PluginTransfers`:

| Method | Contract |
| --- | --- |
| `plugin()` | whose tables these are |
| `export(Path destination)` | a folder to write into, or the exact `.exyliadump.gz` to write |
| `importFrom(Path source)` | refuses any target table that already holds rows |
| `importFrom(Path source, boolean force)` | writes anyway; see **What force means** |

Both futures **always complete normally**. A failure is a report whose
`outcome()` is `FAILED`, with the reason in `problems()` — never a thrown
exception. An import that refused is a result somebody has to read, not
something to catch.

`TransferReport` (a record):

| Component | What it is |
| --- | --- |
| `outcome()` | `SUCCESS`, `PARTIAL` or `FAILED` |
| `file()` | the file written or read, or `null` if it never got that far |
| `tables()` | one `TableTransfer` per table touched |
| `rows()` | rows exported or written, across every table |
| `took()` | wall time, file and database included |
| `problems()` | one line each; empty on a clean run |
| `successful()` / `tableNames()` | conveniences |

`TableTransfer` carries `table()`, `rows()`, `skipped()`, `drifted()` and a
`note()`.

### Three outcomes, not a boolean

`PARTIAL` exists because ExyliaCommons' importer logged a failed batch, carried
on, and still reported success — so an import that lost a thousand rows and one
that lost none were the same answer, and the only record of the difference was
a console line nobody was reading at the time.

Anything less than everything is `PARTIAL`: a table in the dump that no model
claims, a column the record no longer has, a row the engine refused. It is
never `SUCCESS` again once one of those has happened.

## What gets exported

The tables the plugin has **registered** — the ones it has asked
`Databases.of(this).repository(...)` for by the time the export runs.

A plugin that registers a record type lazily (on first use, behind a config
switch, from a subcommand) has fewer tables than it eventually will, and
nothing can tell from outside. That is why `TransferReport.tableNames()` exists
and why the command prints the names before it starts rather than only a count:
a silent short list is an export missing a table, and the only way anybody
notices is by reading the names against what they expected.

## The file format

One gzip-compressed file of **NDJSON** — one JSON value per line — named
`<plugin>-<engine>-<timestamp>.exyliadump.gz`.

```
{"format":1,"exportedAt":1787180653071,"engine":"h2","plugin":"Practice","tables":[{"table":"transfer_kits","rows":1,"generatedId":false,"columns":[{"name":"id","type":"string"},{"name":"display","type":"string"},{"name":"cost","type":"int"}]}]}
{"table":"transfer_kits"}
["boxing","{primary}&lBOXING",250]
```

Line 1 is the header: format version, when, the source engine, the plugin, and
per table its row count and **column layout** — each column's name and stored
type, in order. Then per table a marker line, then one line per row.

### Why NDJSON in gzip

- **A truncated file is still readable up to the truncation.** ExyliaCommons
  wrote one nested `{tables:{t:[...]}}` object, which a parser can only accept
  or reject whole: a dump cut short by a full disk was worth nothing at all.
- **The reader can name the line that failed.** "line 41812: a row could not be
  read" is something an operator can open the file at; "unexpected token at
  offset 9214663" is not.
- **gzip is roughly an order of magnitude on this data** — mostly Base64
  inventories and repeated column shapes — and costs well under the I/O it
  saves.

### Rows are positional arrays

A row is a JSON array in column order, not an object keyed by column name. It
is far smaller on a table of four hundred thousand rows and carries nothing the
header does not already have. It also means a marker (`{`) and a row (`[`) are
distinguishable by one character.

### Values are written typed, never inferred

The reader asks each value for the type the **header** says it is. This is not
a stylistic preference:

- **`BigDecimal` is written as a JSON string.** Gson binding a JSON number
  without a type token produces a `Double` — which is exactly what
  ExyliaCommons did, with `GSON.fromJson(reader, Map.class)` — so every decimal
  it ever imported came back changed and nothing reported it. The text *is* the
  value; money is the only reason a column is a `BigDecimal`.
- **`long` is written as a JSON number**, deliberately. The risk with a number
  is a reader that widens it through a `double`; this reader calls
  `JsonReader.nextLong()`, which parses the token's own digits and is exact for
  every `long`. Quoting every timestamp column in the ecosystem to guard
  against a mistake the reader structurally cannot make would be a worse trade.
- **`null` is written explicitly**, never omitted. A row is positional, so an
  omitted value would shift every column after it by one.

### One caveat about empty strings

`ColumnModel.decode` treats an empty string as absent on a **codec** column —
a `UUID`, an enum, a list. That is the database module's existing behaviour and
the dump does not change it: what the transfer guarantees is that null and `""`
stay *distinct in the file*, so the round trip is exactly as lossy as the
column itself already is and no more.

## No codec ever runs

Rows move in **storage form**: primitives, `String` and `BigDecimal`, and
nothing else. Every other type — `UUID`, enums, `ItemStack`, `Location`,
`List` — has `storedType() == String`, so a serialised inventory is Base64 text
at both ends and never becomes a Bukkit object.

That is what makes the round trip exact rather than merely equivalent, and it
is why a transfer needs no running server to be correct.

## What force means, exactly

> **`force` is a merge, not a replace.** A row whose primary key is in the dump
> overwrites the one in the table. A row in the table whose key is *not* in the
> dump is left exactly where it is. It does not empty anything.

Somebody expecting "replace" and getting this has quietly mixed two servers'
data into one table, which is why the refusal message spells the sentence out
rather than naming the flag.

Without `force`, an import into any table that already holds rows is refused —
`FAILED`, nothing written — and the report names which tables and how many
rows. The check covers only tables the dump actually carries **and** this
plugin actually claims: a full table the dump has nothing for is not in the way
of anything.

## Layout drift

Rows are bound by column **name**, using the layout in the dump's header.

- A record that **gained** a component since the dump imports with that column
  `null`.
- A record that **lost** one imports without it.

Either way it is reported and the outcome is `PARTIAL`. Binding positionally
would put one column's value into another and report success — a `UUID` landing
in `clan` is a table nobody can read and nothing that says so.

A `null` for a column the record declares non-null is correct: the schema layer
never emits `NOT NULL`, and the record reads it back as the type's absent value,
which is precisely what a column added to a live table looks like on every row
that predates it.

## Generated ids

Ids in the dump are written as they were, and the table's identity counter is
moved past them afterwards. Without that the next insert asks for a key the
imported rows already hold and fails on the primary key — on H2 and Postgres,
which do not advance the counter for a row that supplied its key. Not optional,
and covered by a test that fails with the real collision when the call is
removed.

## Memory

Constant on both sides, whatever the table holds. A thousand rows are alive at
a time: an export streams each batch straight into the compressed file, and an
import accumulates at most one batch before writing it. A table of four hundred
thousand rows costs the same heap as one of four hundred.

## Threads

Every method is safe from any thread and none of them blocks. The work runs on
the library's own scheduler through `Tasks.runAsync` — never a thread or an
executor of this module's own — and the futures complete there. A caller that
then touches the game hops back first, as anywhere else.

The file is written from exactly **one** thread: an export hands its writer to
`Storage.scan`, whose block runs on the database's own thread and is called
with each batch before the next is read, and it waits for each table's scan
before starting the next.

Nothing leaks if the plugin disables mid-transfer. Streams are closed in a
`finally`, a half-written dump is deleted (a leftover looks importable and is
missing whatever came after the failure), and the work is scheduled on the
library rather than on the consumer, so a transfer is not cancelled halfway
through a file by the plugin that asked for it going down.

## Known limitation: Redis

**A bulk write does not invalidate the shared cache.** That is deliberate:
invalidating per batch would be one network message per thousand rows, each
sending every peer back to the database for everything it held of that table —
which is how ExyliaCommons collapsed, since it dropped a table's whole keyspace
on every save.

The consequence: importing into a **live** table while Redis is on leaves other
servers serving the rows they had until their entries expire. Importing into a
**fresh** table — the migration case, and what this exists for — is unaffected,
because nothing is serving from it yet.

The import command warns when Redis is active *and* the target table was
non-empty, which is exactly the case where it matters.

## Reload

Nothing here is derived from the palette, so this module has **no**
`invalidateAll()` and is deliberately absent from `ExyliaLib.loadPalette`. It
caches nothing at all between transfers: no listener, no task, no open file.

## Not supported

- **Cross-plugin imports.** A dump belongs to the plugin that wrote it; a table
  in it that this plugin does not claim is skipped and reported.
- **Schema changes.** The tables must already exist, which they do — a
  repository creates its own.
- **Selecting tables.** A transfer is all of a plugin's registered tables or
  none.
- **Reading a dump from a newer format version.** It is refused by version
  number rather than half-parsed.

## Source and tests

- Public: `database/transfer/` — `Transfers`, `PluginTransfers`,
  `TransferReport`, `TableTransfer`, `TransferOutcome`.
- Internal: `database/transfer/internal/` — `DumpFormat`, `DumpWriter`,
  `DumpReader`, `DumpException`, `TransferRuntime`, `DumpFormatAccess`.
- Command: `internal/ReloadCommand` (`export`, `importDump`) over
  `internal/TransferAccess`.
- Seams opened for it: `Databases.find(String)`,
  `Databases.registeredPlugins()`, `PluginDatabase.tables()`,
  `Repository.model()` / `Repository.storage()` (both `@ApiStatus.Internal`).
- Tests: `src/test/java/net/exylia/lib/database/TransferTest.java` — the round
  trip across batches, `BigDecimal` precision, null against empty string, the
  refusal, force merging, generated ids, layout drift, an unknown table, a
  truncated file and the memory bound; plus the command's output in
  `internal/ReloadCommandTest.java`.
