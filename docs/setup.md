# Setup wizard

The first-run setup of a server: the language, menu style, look and database of
every Exylia plugin, chosen from screens in game. Since 1.572.0.

```
/exylialib setup          the hub, any time (exylialib.admin)
/exylialib setup later    the invitation's LATER button
```

## When it is offered

Only after a **fresh install**: ExyliaLib starting with no `config.yml`. That
start writes `plugins/ExyliaLib/setup.yml` with `status: pending`, and every
admin (`exylialib.admin`) who joins is sent a centred invitation once per start,
with **[✔ SET UP]** and **[✘ LATER]**. LATER asks again after the next restart;
the hub's **Finish** card writes `status: done` and the invitations stop.

A server that ran the library before 1.572.0 has a `config.yml` and no
`setup.yml`, so it is never invited. The command still opens the wizard there.

## The steps

A hub (27 slots) with four cards, each opening a page, and Finish in the centre
of the bottom row. Every choice is written and applied the moment it is clicked,
so the page redraws in the new language or colours.

| Step | Writes | Applied |
| --- | --- | --- |
| Language: en, es, pt, fr, ru, tr | `config.yml` `language` | at once for the library; running plugins on `default` switch on their next restart |
| Menus: chest windows, screens, players choose | `screens.yml` `default-style`, `player-choice`; *players choose* also turns `settings.command.enabled` on | at once; the `/settings` command after a restart |
| Look: five colour themes, small capitals, server address | `colors.yml` (`primary`, `secondary`, `secondary-light`, `letters`, `letters-black`, `server-ip`), `config.yml` `small-text` | at once, every plugin repaints |
| Database: this server only, or a network | every Exylia plugin's `database.yml`, `config.yml` `share-database` | on the next restart |

A theme changes the five identity colours only; `error`, `success`, `warning`,
`info` and the rest keep their meaning. A palette that matches no theme shows as
*Custom* and is never overwritten unless a theme is clicked.

## The database step

**Network** asks, in a form (a dialog on 1.21.6+, chat questions before):

1. engine (MySQL, MariaDB, PostgreSQL, MongoDB), host, port (empty for the
   engine's default), database name, user, this server's name on the proxy,
   and how messages travel between servers (through the database, or Redis);
2. the password, as a `sensitive()` text input: never in chat, logs or a
   Discord bridge; `-` for none;
3. with Redis, its host and port, then its password the same way.

The values are then **tested off the main thread**: a SQL pool is opened and
closed (Hikari connects eagerly), MongoDB is pinged, Redis is pinged. A failure
names the cause (passwords masked) and saves nothing; the form opens again with
what was typed.

On success the admin confirms *Move N plugins to MySQL?* and the setup:

- exports the rows of every Exylia plugin still on its own H2 file
  (`plugins/<Plugin>/dumps/`, through the [transfer module](transfer.md)),
  ExyliaLib's own included;
- points ExyliaLib's `database.yml` and each of those plugins' at the shared
  database (`type`, the engine block, `cross-server`, `redis.enabled`,
  `redis.server-id`; comments and every other key kept);
- turns `share-database` on, so a plugin installed later starts there too;
- records the moved plugins and the dumps in `setup.yml`.

A plugin already on a database server is the owner's choice and is left alone.
A plugin whose export fails stays on its own file, and the admin is told.

After the restart, a tick after every plugin has enabled, each dump is imported
into its plugin's tables there. An import refuses a table that already holds
rows, so a network's second server, or a plugin that was already there, never
doubles anything: the refusal is logged and the dump stays on disk for
`/exylialib import`.

**This server only**, while on a network, puts ExyliaLib and the plugins the
setup moved back on `type: h2` after a restart and turns `share-database` off.
What they saved in the shared database stays there.

## `share-database`

`config.yml` `share-database: true` makes a plugin with no `database.yml` start
with a copy of ExyliaLib's, when ExyliaLib's names a database server rather than
H2. An existing `database.yml` is never touched. Off by default; the setup turns
it on for a network. See [database.md](database.md#configuration).

## Where the code is

`net.exylia.lib.internal`: `SetupWizard` (state, invitation, imports after the
restart), `SetupMenu` (the screens, built in code with `Phrases`, like the
updates screens), `SetupNetwork` (forms, probe, the `database.yml` writes),
`SetupCommand`. The copy for a new plugin is
`DatabaseRuntime.seedFromLibrary`. The probes are `MongoBackend.ping()` and
`RedisRuntime.probe(RedisSettings)`.
