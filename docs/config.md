# Config module

YAML files declared and read as Java records. The file is generated from the
record — the YAML is output, not input. Since 1.1.0.

Entry point: `net.exylia.lib.config.Configs`.

## Declare and use

```java
public record Storage(
        @Comment("Connections kept open. Rule of thumb: cores x 2.")
        int poolSize,

        @Comment("Where player data lives.")
        String host) {

    public Storage() {
        this(10, "localhost");
    }
}

ConfigFile<Storage> storage = Configs.define(this, "storage", Storage.class).load();
int pool = storage.get().poolSize();
```

The no-arg constructor is the default the file is generated from. Nested
records become nested YAML sections.

## Annotations

- `@Key("old-name")` — the YAML key differs from the record component name.
- `@Comment("...")` — written above the value in the file; repeatable via
  `@Comment.Comments`. Comments are the server owner's manual: say what the
  value changes, in what unit, in what range.

## Sections that do nothing

A record implementing `Sparse` decides for itself whether it has anything to
say. While `isEmpty()` answers `true`, the section is **left out of the file**
and reading it back is silent: the defaults are used, nothing is reported
missing, and the block does not grow back on the next load. An empty block a
previous version wrote is removed the next time the file is saved.

This is what stops an effect with one boss bar from also writing an empty
title, action bar, sound, particle and firework, each with a comment per key.

Omitting only happens when the **default is empty too**. A section that ships
with something in it is read back as that default the moment it is absent, so
leaving it out would not mean "off", it would mean "reset": an owner who
cleared a title got the shipped one back on the next boot. Such a section is
written out empty and stays empty.

`Sparse` is still meant for sections whose defaults are empty by nature — a
section with real defaults must stay in the file, or nobody can find out it
exists.

Blank lines separate the **top-level** groups only. Inside a block, comments
sit directly above their key: a blank line between every one of seven keys
doubles the height of the block without making it easier to read.

## API

`Configs`:

| Method | Contract |
| --- | --- |
| `define(plugin, name, type)` → `Builder<T>` | starts a definition; `Builder.version(int)`, `Builder.migration(fromVersion, Migration)`, `Builder.load()` |
| `reloadAll(plugin)` → `List<ConfigIssue>` | reload every file that plugin defined |
| `release(pluginName)` / `releaseAll()` | drop the cache |
| `loaded()` | all loaded files |

`ConfigFile<T>`:

| Method | Contract |
| --- | --- |
| `get()` | the current snapshot; a field access, never a re-parse |
| `reload()` → `List<ConfigIssue>` | re-read the file; empty list means clean |
| `onReload(Consumer<T>)` | run after each successful reload |
| `save()` | write the current snapshot to disk |
| `update(UnaryOperator<T>)` | change and persist in one step |
| `issues()` | problems found on the last load |
| `name()` | the file's name |
| `schema()` → `Schema` | a read-only description of the record type; never `null`. Since 1.50.0 |

`MutableConfig` (low-level, path-based): `get(path)`, `set(path, value)`,
`remove(path)`, `contains(path)`.

## Schema projection

Since 1.50.0. A config file can describe its own record — keys, declared types,
`@Comment` lines, nesting — so a UI can be generated from it without the caller
reflecting over the record or reaching into `config.internal`.

```java
for (Schema.Field field : storage.schema().fields()) {
    render(field.key(), field.type(), field.comments());
}
```

`Schema` is a record: `Schema(Class<?> type, List<String> comments, List<Field> fields)`.

| Accessor | Contract |
| --- | --- |
| `type()` | the record class described |
| `comments()` | the section's `@Comment` lines, in declaration order; empty when undocumented |
| `fields()` | the components, in canonical-constructor order |

`Schema.Field` is a nested record:
`Field(String name, String key, Class<?> type, Type generic, List<String> comments, Schema nested)`.

| Accessor | Contract |
| --- | --- |
| `name()` | the Java component name — what an error message should quote |
| `key()` | the YAML key: the `@Key` value, or the kebab-case form of `name()` |
| `type()` | the declared type, erased |
| `generic()` | the declared generic type, so the element type of a `List<String>` is recoverable |
| `comments()` | this key's `@Comment` lines, in declaration order |
| `nested()` | the nested `Schema` when the component is a record, otherwise `null` |
| `isSection()` | `nested() != null`, named for the question a caller is actually asking |

Contracts:

- **It is a value, not a handle.** A schema describes the *type*, never the
  values. Two `ConfigFile`s of one record type holding different settings project
  **equal** schemas, and a schema taken before a `reload()` is unchanged after
  it. Reading values stays `get()`; writing stays `update(...)`.
- **Nothing live leaks.** It holds no reference back to the file, the backing
  YAML, or the canonical constructor. `SchemaNode` stays package-private; a
  reflection sweep over the package's public signatures enforces it.
- **Deeply immutable.** Every list is copied on construction and rejects
  mutation with `UnsupportedOperationException`. `nested()` is the only accessor
  that can answer `null`.
- **Any thread.** Building one touches no Bukkit API, no server and no
  filesystem, so it is identical on Spigot, Paper, Purpur and Folia.
- **Exempt from `invalidateAll()`.** It caches nothing derived from the palette,
  so it has nothing to invalidate on reload.
- The projection deliberately carries no current values, no defaults, no
  `FileConfiguration`, no migration history and no `ConfigIssue` state.

## Migrations

Changing a layout version runs the registered migrations in order. Factories
on `Migration`:

- `Migration.rename(from, to)` — move a value.
- `Migration.remove(path)` — drop a key.
- `Migration.transform(path, UnaryOperator<Object>)` — rewrite a value.
- `Migration.all(steps...)` — compose.

Renaming a key without a migration silently loses what the owner configured.
Do not do it.

## Blocks the owner names

Since 1.63.0. A component declared `Map<String, V>` is a section whose keys the
server owner chooses — worlds, regions, materials — rather than keys the code
decided on.

```java
public record Limits(
        @Comment("Per-world multiplier. Add the worlds this server has.")
        Map<String, Double> worlds,
        Map<String, Item> items) {

    public Limits() {
        this(Map.of("world", 7.0), Map.of("ender-pearl", new Item()));
    }

    public record Item(double cooldown, int maxUses) {
        public Item() {
            this(14.0, 1);
        }
    }
}
```

```yaml
worlds:
  arena: 2.5
  survival: 9.0
items:
  ender-pearl:
    cooldown: 14.0
    max-uses: 1
```

`V` may be a leaf (`String`, a number, `boolean`, an enum, a `List`) or a
record, which becomes a block per entry. The key type must be `String`, because
YAML keys are text; anything else is rejected at declaration. A `Map` of `Map`
is rejected too — nest a record instead, so the inner block gets a name,
comments and defaults of its own.

Contracts:

- **Nothing inside is pruned.** This is the point: the keys belong to the
  owner, so [Housekeeping](#housekeeping) does not apply inside the block.
  Keys *within* a record entry are still the code's, and are pruned normally.
- **The no-arg constructor's entries are examples**, written once when the file
  is generated. They are not re-added afterwards: an entry the owner deleted
  stays deleted, and an emptied block stays empty.
- **An entry the owner invented gets the record's own defaults** for whatever
  it left out.
- **One unreadable entry costs that entry, not the block.** It is reported as
  an `INVALID_VALUE` issue at its own path, and the rest load.
- **Insertion order is kept**, so the file does not reshuffle on every save.

## Housekeeping

A config outlives the code that wrote it: fields get renamed, features get cut.
A key no record declares is **removed from the file on load**, and each removal
is reported as an `UNKNOWN_KEY` issue so the log says exactly what left and from
where. This is the strict cleanup ExyliaCommons performed. Three guarantees:

- Migrations run first, so a migration can still read the old layout before it
  goes.
- The library's own `config-version` marker is never touched.
- Nothing inside a `Map` block is touched — see
  [Blocks the owner names](#blocks-the-owner-names).

## When the file is wrong

A typo in the user's file never crashes the plugin: each problem becomes a
`ConfigIssue` (`type`, file, path, expected, actual, `describe()`) and the
default value is used.

## Source and tests

- Public: `config/Configs`, `ConfigFile`, `MutableConfig`, `Key`, `Comment`,
  `Migration`, `ConfigIssue`, `Schema` (with `Schema.Field`).
- Internal: `config/internal/` (`Binder`, `Coercions`, `ConfigFileImpl`,
  `SchemaCache`, `SchemaNode`, `SchemaProjection`, `YamlMutableConfig`).
- Tests: `src/test/java/net/exylia/lib/config/ConfigModuleTest.java`,
  `SchemaProjectionTest.java`, `PublicSignatureSweepTest.java`.
