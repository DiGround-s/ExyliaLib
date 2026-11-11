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
   its [network group](network.md#groups-which-servers-share-what-since-1576)
   (optional, written to `config.yml` `network-group`), and how messages travel
   between servers (through the database, or Redis);
2. the password, as a `sensitive()` text input: never in chat, logs or a
   Discord bridge; `-` for none;
3. with Redis, its host and port, then its password the same way.

The values are then **tested off the main thread**: a SQL pool is opened and
closed (Hikari connects eagerly), MongoDB is pinged, Redis is pinged. A failure
names the cause (passwords masked) and saves nothing; the form opens again with
what was typed. The test (1.581.0+) also refuses a server name that a running
server of that database already uses (its row in `exylia_servers` written in
the last 150 seconds): two servers under one name both own the same balances
and places.

The same test looks at the shared database for each moving plugin's tables:

- **Nothing there yet** (the first server): the admin confirms *Move N plugins
  to MySQL?*.
- **Some plugins already have rows** (a second server joining): one form asks,
  per plugin, *Add mine, the network's wins on a clash* (the default), *Use the
  network's, keep mine in my file*, or *Do not move this plugin*.

Two moving plugins that both use one table name are named in a warning: their
rows would mix in one table.

Then the setup:

- writes each moved plugin down in `plugins/ExyliaLib/moves.yml` with its old
  H2 file and the choice (ExyliaLib's own rows always add);
- points ExyliaLib's `database.yml` and each of those plugins' at the shared
  database (`type`, the engine block, `cross-server`, `redis.enabled`,
  `redis.server-id`, the old name added to `redis.previous-ids` when it
  changed; comments and every other key kept);
- turns `share-database` on, so a plugin installed later starts there too;
- records the moved plugins in `setup.yml`.

A plugin already on a database server is the owner's choice and is left alone.

### How the rows follow (since 1.581.0)

After the restart nothing is imported in bulk. Each table is copied from the
plugin's old H2 file **the moment the plugin first opens it**, before its first
read ([database.md](database.md#a-plugin-that-moved-to-a-shared-database-since-1581)).
So a plugin never sees an empty table and seeds defaults into it, no cache
holds an empty state, and a table of a module turned on a week later still
arrives. The old file is only read; it stays as the copy of what the server had.

**This server only**, while on a network, puts ExyliaLib and the plugins the
setup moved back on `type: h2` after a restart, forgets their pending moves and
turns `share-database` off. What they saved in the shared database stays there.

## `share-database`

`config.yml` `share-database: true` makes a plugin with no `database.yml` start
with a copy of ExyliaLib's, when ExyliaLib's names a database server rather than
H2. An existing `database.yml` is never touched. Off by default; the setup turns
it on for a network. See [database.md](database.md#configuration).

## Where the code is

`net.exylia.lib.internal`: `SetupWizard` (state, invitation, dumps a setup older
than 1.581.0 left to import), `SetupMenu` (the screens, built in code with `Phrases`, like the
updates screens), `SetupNetwork` (forms, probe, the `database.yml` writes),
`SetupCommand`. The copies are `database.internal.Carry` and `Moves`; the
server names `network.internal.ServerPresence`. The copy for a new plugin is
`DatabaseRuntime.seedFromLibrary`. The probes are `MongoBackend.ping()` and
`RedisRuntime.probe(RedisSettings)`.
