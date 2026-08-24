# Loot

Since 1.56.0.

What comes out of a chest, a spawner or a broken block. Lives in
`net.exylia.lib.util.loot`, keeps no state, and reads and writes the exact form
ExyliaCommons already stored on live servers.

```java
List<LootEntry> table = Loot.parseAll(config.pool());

for (ItemStack item : Loot.roll(table)) {
    chest.getInventory().addItem(item);
}
```

Migrating a plugin from ExyliaCommons is changing imports: the stored rows, the
config grammar and the two rolling behaviours are the same ones.

## A table is a list the caller owns

There is no registry, no per-plugin owner and nothing to release. A loot table
is a `List<LootEntry>` — read from a column with [`LootCodec`](#the-stored-form),
from a config with `Loot.parseAll`, or built in code — and rolling it allocates
only the result.

That is deliberate. Loot tables are held by the thing that has them: a chest
template, a spawner, an event configuration. A registry in the library would be
a second place to keep them in sync with.

## Two readings of one weight

A loot table in this ecosystem means one of two things, and which one is the
caller's to say:

| Call | What the weight means | Who wants it |
| --- | --- | --- |
| `Loot.roll` | a percentage — every line is rolled on its own | a chest, a spawner |
| `Loot.pick` | a share of the total — exactly one line comes up | a refill, one item per slot |

The same stored number, read two ways, because that is what the tables already
out there mean by it. Nothing converts between them.

## Rolling

| Method | Contract |
| --- | --- |
| `roll(entries)` → `List<ItemStack>` | rolls every line; **never empty** for a table that is not — one line is forced, because a chest that opens empty reads as broken. Shuffled |
| `roll(entries, forceOneIfEmpty)` | the same, with `false` when nothing is a real answer — a spawner tick that produced nothing |
| `rollEntries(entries)` → `List<LootEntry>` | the same roll, entries instead of items, for a table that can hold command lines. In written order |
| `pick(entries)` → `ItemStack?` | one line by weight, built |
| `pickEntry(entries)` → `LootEntry?` | one line by weight; `null` when every weight is zero |
| `itemOf(entry)` → `ItemStack?` | the entry's item at a freshly rolled stack size; `null` for a command entry |
| `amountOf(entry)` → `int` | how many it gives, without building it. Never below one |

`roll` shuffles because the caller is about to spread the result across a chest,
and unshuffled they would land in the order the file wrote them — every chest on
the map identical. `rollEntries` does not: the caller is applying each line, not
laying them out.

The forced line, when a roll came up empty, is picked **evenly and not by
weight**. That is what ExyliaCommons did, and changing it would quietly make
rare items common in exactly the tables where every line is unlikely.

### Threads

Everything here is computation over strings and numbers and is safe from any
thread — which is the point:

```java
tasks.runAsync(() -> {
    List<ItemStack> items = Loot.roll(table);
    tasks.runAtLocation(chest.getLocation(), () -> fill(chest, items));
});
```

Putting an item into an inventory, dropping it in the world or running a command
is the caller's, and belongs on the thread that owns that block or that player.

## An entry

`LootEntry` is immutable. An edit produces a new entry through `toBuilder()`,
which keeps the id, so an admin saving a table cannot change a line out from
under a chest that is being filled, and the same list can be read from several
threads without a lock.

```java
LootEntry bread = LootEntry.item("BREAD")
        .amountBetween(1, 3)
        .weight(40.0)
        .tier("COMMON")
        .build();

LootEntry rarer = bread.toBuilder().weight(5.0).build();  // same line, edited
LootEntry another = bread.copy();                          // a second line
```

| Part | What it is |
| --- | --- |
| `id()` | identity, stable across edits; what an editor menu finds a clicked row by |
| `type()` | `ITEM` or `COMMAND`; `isItem()` and `isCommand()` read it |
| `itemSnapshot()` | the item, as a material name, a head string or a `bytes:` snapshot |
| `command()` | the console command, for a `COMMAND` line |
| `minAmount()` / `maxAmount()` / `isRanged()` | the stack size, both ends included |
| `weight()` | see [above](#two-readings-of-one-weight); defaults to `LootEntry.DEFAULT_WEIGHT`, which is commons' `50.0` |
| `tier()` | `COMMON`, `RARE`, whatever the plugin groups by. **The library never reads it** |
| `displayName()` / `resolvedIcon()` | what a menu shows and draws, without a server |

`displayName()` and `resolvedIcon()` deliberately do **not** decode a `bytes:`
snapshot: a menu of forty lines would pay forty NBT reads for a label, and the
item module decodes it again when the row is really drawn. Such a line reads as
`ITEM` and draws as a `CHEST`.

`Loot.entryOf(item)` is the other direction — what an editor's "add the item in
my hand" button stores — and returns a builder, so the row can be given its odds
in the same breath.

## Editing a table on screen

Since 1.56.0.

```java
Loot.editor(this, template.entries())
    .title("{primary}&lLOOT TABLE")
    .onSave(entries -> manager.save(template, entries))
    .onCancel(() -> setupMenu.open(player))
    .open(player);
```

The [editor](editors.md) screen: pagination, add, edit, delete, copy, paste,
save and cancel. A table copied here pastes into any other loot editor — a chest
into a spawner, a spawner into an event — because they are the same rows in the
same format.

## The written form

`Loot.parseAll` reads the compact grammar every event config already holds:

```
MATERIAL MIN MAX WEIGHT [TIER]
DIAMOND_SWORD 1 1 5 RARE
SPLASH:HEALING 1 2 20
GOLDEN_APPLE 1 2 15
```

- The material token also takes a potion: `POTION:`, `SPLASH:`, `LINGERING:`
  and `TIPPED:`, followed by a potion type. Both spellings of every renamed
  vanilla potion resolve — `SPEED` and `SWIFTNESS`, `INSTANT_HEAL` and
  `HEALING`, `JUMP` and `LEAPING`, `REGEN` and `REGENERATION`.
- `MIN` and `MAX` are the stack size, both ends included. `WEIGHT` is a decimal.
- `TIER` is optional and may contain spaces. It is uppercased, as the tables
  hold it.
- A line that cannot be read is **skipped, never fatal**: one typo in a
  fifty-line pool costs that line, and refusing the file would cost the event.

A line is refused when it has fewer than four tokens, when the amounts or the
weight are not numbers, when an amount is zero or negative, when the range is
the wrong way round, or when nothing on the server answers to the material.

Pass a plugin's own reporter to hear about it:

```java
List<LootEntry> table = Loot.parseAll(config.pool(),
        (line, problem) -> debug.warn("loot: " + line + " — " + problem));
```

## The stored form

`LootCodec` is the column.

| Method | Contract |
| --- | --- |
| `encode(entries)` → `String?` | the JSON array, or **`null` for an empty list** |
| `encode(entry)` → `String` | one entry's JSON object |
| `decode(stored)` → `List<LootEntry>` | reads a column, ignoring what it cannot understand |
| `decode(stored, problems)` | the same, reporting where the trouble was |

The format is not a choice. Production databases already hold these rows —
`sc_loot_chest_templates`, the spawner tables, every event configuration — and
ExyliaCommons wrote them by handing the list to a bare `new Gson()`. The field
names are that bean's fields, in its declaration order:

```json
[{"id":"…","type":"ITEM","itemSnapshot":"bytes:…","minAmount":1,
  "maxAmount":3,"weight":50.0,"tier":"RARE"}]
```

Gson omits null fields, so an item line carries no `command` key at all and a
line with no tier carries no `tier`. That is reproduced exactly: adding them back
would grow every row, and a human diffing two rows across the migration should
see nothing move.

An empty list stores as `NULL` rather than `[]`, because that is what commons'
`serializeCollection` did — a column that suddenly held `[]` would read back the
same but would not compare the same.

A line with **no `type` key** is an item. Those rows were written before command
lines existed, and they meant items. A type this version has never heard of is
also read as an item and reported, so it costs a payload rather than the table.

An `ITEM` line with no item and a `COMMAND` line with no command are **kept** and
reported. A half-configured row is exactly what an editor is for, and dropping it
would lose the row the moment the table was saved back.

## What is not reproduced

Two of the old module's behaviours are bugs and are not carried over:

- **A stack of zero.** Commons handed back `minAmount` as written, so a line
  stored with `0` produced an item of amount zero and it vanished on the way
  into the chest. `amountOf` never returns less than one.
- **A range the wrong way round losing the line.** `min > max` gives the low end
  rather than nothing.

## Where the code lives

| Part | Where |
| --- | --- |
| Public API | `util/loot/Loot`, `LootEntry`, `LootType`, `LootCodec` |
| Internal | `util/loot/internal/` — `LootLines` (the written grammar), `LootRolls` (the dice), `LootItems` (the one Bukkit seam) |
| Tests | `src/test/java/net/exylia/lib/util/loot/` |

`LootItems` is the only part that needs a running server. Everything a loot
table decides — which lines come up, how many of each, what a stored row means,
what a menu labels it — is decided in terms of a snapshot string and a count,
and is tested without one.

## Reload

The module holds nothing derived from the palette and nothing at all between
calls, so there is no `invalidateAll()` and nothing hooks into
`ExyliaLib.loadPalette`.
