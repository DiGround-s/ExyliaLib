# Placeholder module

One registry for `%placeholders%`. There is exactly one kind of resolver and
one registry; PlaceholderAPI is an optional bridge, not a requirement.
Since 1.3.0.

Entry point: `net.exylia.lib.placeholder.Placeholders`.

## Registering

```java
Placeholders.group(this, "clan")
        .add("name", r -> clans.of(r.requireViewer()).name())
        .add("top", r -> clans.leaderboard().at(r.arg(0, 1)))
        .register();
```

A resolver is `(Request) -> Object`. The prefix is declared once, in the
group (`%clan_name%`). The whole group unregisters itself when the plugin
disables.

`Group` builder: `.async()` (a promise the resolver never touches the Bukkit
API — marking it wrong crashes the server later and elsewhere), `.describe(text)`,
`.add(name, resolver)`, `.register()`.

## Syntax in configs

| Form | Meaning |
| --- | --- |
| `%clan_name%` | plain |
| `%eco_balance:comma%` | with a format — formatting belongs to the placeholder, not the plugin |
| `%clan_name|Sin clan%` | fallback when the resolver has no value |

Formats (`placeholder/internal/Formats.java`): `comma`, `compact`/`short`,
`percent`, `upper`, `lower`, `yesno`, `time`, `fixed1`, `fixed2`.

## Resolving

`Placeholders`:

| Method | Contract |
| --- | --- |
| `apply(text, viewer)` / `apply(text, viewer, data)` / `apply(text)` | resolve all placeholders in a string |
| `applyRelational(text, viewer, target)` | relational variant |
| `compile(text)` → `Template` | parse once, render many — a scoreboard line measured 3.4x faster than passing the string each tick |
| `isDynamic(text)` | whether the text contains placeholders |
| `has(name)` / `names()` | registry queries |
| `unresolved(text)` | placeholders nothing resolved |
| `resolveInto(text, viewer)` / `resolvePairs(template, request)` | key/value pairs, used by `Text.forPlayer` |
| `unregisterAll(pluginName)` / `releaseAll()` | cleanup |
| `logger(Logger)` | where resolver failures are reported |

`Template`: `render(viewer)`, `render(viewer, data)`,
`render(viewer, target)`, `render()`, `renderFor(request)`,
`placeholders()`, `isDynamic()`, `raw()`.

`Request` — what a resolver sees: `viewer`, `target`, `args`,
`requireViewer()`, `hasViewer()`, `arg(index, fallback)` (String/int/double),
`argCount()`, `get(key, type)` / `get(key, type, fallback)`,
`isRelational()`. `Request.EMPTY` exists.

## Rules

- **Return `null` to say "no value", never `""`.** The module applies the
  fallback (`%x|default%`) or leaves the placeholder visible so a typo shows.
- A resolver that throws is reported once and treated as no-value. Nothing
  else dies.
- PlaceholderAPI, when installed, gets registered expansions through a bridge
  confined to `placeholder/internal/PapiBridge` + `PapiExpansion`.

## Built-in placeholders

Registered by the library itself:

- `player_*`: `name`, `displayname`, `uuid`, `world`, `health`, `level`,
  `food`, `gamemode`, `ping`, `x`, `y`, `z`.
- `target_*`: `name`, `uuid`.
- `server_*`: `online`, `max`, `tps`.

## Source and tests

- Public: `placeholder/Placeholders.java`, `Template.java`, `Resolver.java`,
  `Request.java`.
- Internal: `placeholder/internal/` (`Registry`, `TemplateCompiler`,
  `CompiledTemplate`, `TemplateCache`, `BuiltIn`, `Formats`, `PapiBridge`,
  `PapiExpansion`, `Part`, `Loggers`).
- Tests: `src/test/java/net/exylia/lib/placeholder/PlaceholderModuleTest.java`.
