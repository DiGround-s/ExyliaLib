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

`MutableConfig` (low-level, path-based): `get(path)`, `set(path, value)`,
`remove(path)`, `contains(path)`.

## Migrations

Changing a layout version runs the registered migrations in order. Factories
on `Migration`:

- `Migration.rename(from, to)` — move a value.
- `Migration.remove(path)` — drop a key.
- `Migration.transform(path, UnaryOperator<Object>)` — rewrite a value.
- `Migration.all(steps...)` — compose.

Renaming a key without a migration silently loses what the owner configured.
Do not do it.

## When the file is wrong

A typo in the user's file never crashes the plugin: each problem becomes a
`ConfigIssue` (`type`, file, path, expected, actual, `describe()`) and the
default value is used.

## Source and tests

- Public: `config/Configs`, `ConfigFile`, `MutableConfig`, `Key`, `Comment`,
  `Migration`, `ConfigIssue`.
- Internal: `config/internal/` (`Binder`, `Coercions`, `ConfigFileImpl`,
  `SchemaCache`, `SchemaNode`, `YamlMutableConfig`).
- Tests: `src/test/java/net/exylia/lib/config/ConfigModuleTest.java`.
