# Discord webhooks

Messages to Discord webhooks, written in config and sent safely. Since 1.245.0.

Entry point: `net.exylia.lib.discord.Webhooks`.

```java
PluginWebhooks webhooks = Webhooks.of(this);
WebhookTarget target = WebhookTarget.parse(config.webhookUrl()).target();

webhooks.send(target, lang.captureStarted(), Map.of(
        "zone", zone.name(),
        "player", player.getName(),
        "player_head", Webhooks.head(player.getUniqueId())));
```

No bot, no gateway connection, no new dependency: webhooks over
`java.net.http`, which the JVM already has.

## Why this is in the library

Several plugins post the same kind of thing to Discord: an event starting, a
winner, a clan member joining. Each copy would need the same four things, and
any one of them done wrong shows up in production:

- **The URL is a trust boundary.** A player who can paste a URL can point the
  server at any host, port or path unless the URL is parsed strictly.
- **Pings.** A player called `@everyone` must not ping a whole Discord server
  because their name went into a message.
- **Rate limits.** Discord rate-limits each webhook. If a 429 is ignored, or a
  dead webhook keeps getting requests, Cloudflare ends up banning the server's
  IP, and then every plugin's webhooks stop working.
- **Never on the main thread.**

## Two trust tiers

| | Who supplies the URL | `@everyone` / `@here` in the template |
| --- | --- | --- |
| `Webhooks.of(plugin)` | the server owner, in a config | pings |
| `Webhooks.of(plugin).playerOwned()` | a player, in game | never pings |

Both views share the same queues and listeners. Only the `@everyone` rule
differs. A clan's Discord is not the server owner's to ping.

### Server-owned: an event announcement

```java
public record Settings(
        @Comment("The webhook events are announced to. Empty disables it.")
        String webhook,
        WebhookTemplate started,
        WebhookTemplate winners) {

    public Settings() {
        this("",
             new WebhookTemplate("CAPTURE STARTED", "Zone **%zone%** is now open.")
                     .withMessage("<@&123456789012345678> A capture just started!"),
             new WebhookTemplate("CAPTURE OVER", "**%winner%** took the zone."));
    }
}

WebhookTarget.Parsed parsed = WebhookTarget.parse(settings.webhook());
if (parsed.ok()) {
    Webhooks.of(this).send(parsed.target(), settings.started(), Map.of("zone", zone.name()));
} else if (parsed.rejection() != WebhookTarget.Rejection.BLANK) {
    getLogger().warning("Webhook ignored: " + parsed.rejection().message());
}
```

### Player-owned: a clan's event log

```java
PluginWebhooks clanLog = Webhooks.of(this).playerOwned();

// Setting it: asked for, checked with Discord, stored.
clanLog.ask(leader, "{primary}Paste your clan's webhook URL").thenAccept(check ->
        tasks.runAtEntity(leader, () -> {
            switch (check.status()) {
                case CONNECTED -> {
                    clan.webhook(check.target().secret());
                    Text.of("{success}Connected to {highlight}%name%").with("%name%", check.name()).send(leader);
                    clanLog.send(check.target(), lang.webhookConnected());
                }
                case CANCELLED -> { }
                default -> Text.of("{error}That webhook does not work.").send(leader);
            }
        }));

// Using it.
WebhookTarget.parse(clan.webhook()).target();    // restored from storage
clanLog.send(target, lang.memberJoined(), Map.of("player", player.getName(), "clan", clan.name()));

// Losing it: the webhook was deleted in Discord.
Webhooks.of(this).onInvalidated(dead -> clans.byWebhook(dead).ifPresent(clan -> {
    clan.webhook(null);
    clan.notifyLeader("{error}Your clan's Discord webhook stopped working and was removed.");
}));
```

## API

`Webhooks`:

| Method | Contract |
| --- | --- |
| `of(plugin)` → `PluginWebhooks` | the plugin's view for server-owned webhooks; cached per plugin |
| `trusted(value)` → `Trusted` | marks a value as the server owner's own text, inserted as written (see below) |
| `head(uuid)` → `String` | the URL of a player's head image, from `webhook-head` in ExyliaLib's `config.yml` (`https://mc-heads.net/avatar/%uuid%/64` by default) |

`PluginWebhooks`:

| Method | Contract |
| --- | --- |
| `send(target, template)` / `send(target, template, values)` → `CompletionStage<WebhookResult>` | queues the message; never blocks, never completes exceptionally |
| `verify(target)` → `CompletionStage<WebhookCheck>` | asks Discord whether the webhook exists, and its name, channel and guild |
| `onInvalidated(Consumer<WebhookTarget>)` | runs when Discord reports a webhook this plugin sends to as gone or unauthorised |
| `ask(player, prompt)` → `CompletionStage<WebhookCheck>` | asks the player for a URL, refuses a bad one with the reason, then verifies it |
| `playerOwned()` → `PluginWebhooks` | the view where `@everyone` never pings |
| `allowsEveryone()` | whether this view lets a literal `@everyone`/`@here` ping |

`WebhookTarget` (record of `id`, `token`):

| Method | Contract |
| --- | --- |
| `parse(String)` → `Parsed` | `ok()`, `target()` or `rejection()`; never throws |
| `secret()` | the canonical URL with the token, for storage; `parse` reads it back |
| `masked()` / `toString()` | `https://discord.com/api/webhooks/1234…/••••`; safe in logs |

`WebhookTarget.Rejection`: `BLANK`, `MALFORMED`, `NOT_HTTPS`, `HOST`, `EXTRA`
(credentials, a port, a query or a fragment), `PATH`, `INCOMPLETE`. Each has a
`message()` for the player, in English, to pass through `Phrases.tr`.

`WebhookResult`: `SENT`, `DROPPED` (a full queue, the plugin disabled or the
server stopping), `INVALIDATED` (404/401), `FAILED` (refused, or unreachable
after three attempts), `RATE_LIMITED` (still limited after three attempts),
`SKIPPED` (the template is disabled or has nothing to send).

`WebhookCheck`: `status()`, `target()`, `name()`, `channelId()`, `guildId()`,
`connected()`. Statuses: `CONNECTED`, `NOT_FOUND`, `UNAUTHORIZED`,
`RATE_LIMITED`, `UNREACHABLE`, `CANCELLED` (only from `ask`).

## Values

Values are a `Map` keyed by bare name: `"player"` fills `%player%`. The
`%player%` form is accepted as a key too. A placeholder with no value is left
visible, so a typo shows up.

**A value is somebody else's text unless it is marked otherwise.** Before it is
inserted, it goes through four steps:

1. Its Minecraft and MiniMessage formatting is stripped for good (`Text.strip`).
2. Line breaks are flattened, so a value cannot fake a line of its own.
3. Markdown is escaped (`\ * _ ~ ` | > # - [ ]`), so `**x**` and masked links
   read exactly as typed.
4. Mentions are broken with a zero-width space after `@` and `<`. Injected
   `@everyone`, `<@&id>` and `<#id>` show as text and ping nobody, even past
   `allowed_mentions`.

The footer, the author name and the username are not rendered as markdown by
Discord, so a value there is only stripped and flattened; escaping it would put
visible backslashes on the screen.

`Webhooks.trusted(value)` is for text the server owner wrote, such as a server
name from the config whose `**bold**` is meant to render. Its Minecraft
formatting is still converted, and it still cannot ping: pings come from the
template only.

In a URL part (`avatar-url`, `url`, `author.icon`, `author.url`, `thumbnail`,
`image`) the value is inserted unchanged. The part is then left out unless it
is a valid `http(s)` URL, because a broken link would make Discord refuse the
whole message.

## Template reference

A `WebhookTemplate` nests in any config record. A hand-written file reads the
same keys with `WebhookTemplate.read(section)`.

```yaml
started:
  # Whether this message is sent at all.
  enabled: true
  # Plain message above the embed, and the only part that can ping.
  message: '<@&123456789012345678> A capture just started!'
  # The name and avatar it is posted under. Empty keeps the webhook's own.
  username: ''
  avatar-url: ''
  # A palette token such as {primary}, or #rrggbb.
  color: '{primary}'
  title: 'CAPTURE STARTED'
  url: ''
  description: 'Zone **%zone%** is now open.'
  author:
    name: '%player%'
    icon: '%player_head%'
    url: ''
  thumbnail: ''
  image: ''
  fields:
    - 'Duration|%duration% ⌚|inline'
    - 'Players|%players%'
  footer: '%server%'
  timestamp: true
  # Messages from this template within this window merge into one.
  coalesce: 0s
```

- **Embed or not.** An embed is sent when any of `title`, `description`,
  `fields`, `author.name`, `thumbnail`, `image` or `footer` has something in
  it; a colour and a timestamp alone are not an embed. A template with only a
  `message` sends that message. A template with neither is skipped (`SKIPPED`)
  and reported once.
- **Formatting.** Template text keeps its Discord markdown. `&l` becomes bold
  up to the next colour code, `&r` or the end of the line. Every other legacy
  code, palette token and MiniMessage tag is removed. Discord's own syntax
  (`<@&id>`, `<#id>`, `<:emoji:id>`, `<t:unix:R>`) is left alone.
- **Colour.** A palette token is read from the live palette on every send, so
  a `colors.yml` change applies right away. An unknown token or a colour name
  means no colour.
- **Fields.** `name|value` or `name|value|inline`. The template is split, not
  the filled text, so a `|` in a value stays inside its column. An empty name
  or value is drawn blank, since Discord refuses an empty one.
- **Limits.** Anything too long is cut with `…` rather than refused: content
  2000, username 80, title 256, description 4096, field name 256, field value
  1024, 25 fields, footer 2048, author 256. Past the 6000-character embed total,
  the description is cut first, then the last fields are dropped.
- **Username.** Discord refuses names containing `discord` or `clyde`; such a
  username is left out and the webhook's own name is used.
- **Coalescing.** With `coalesce: 3s`, a message waits up to three seconds.
  Later messages from the same template, the same plugin and to the same
  webhook are merged into it, one description line each (the plain message
  when there is no embed), up to the limits. Every one of their stages
  completes with the merged message's result. A clan log at twenty joins a
  second then becomes a few messages instead of twenty.

## Mentions

`allowed_mentions` is sent with every message, and its `parse` is always empty
unless `@everyone` is allowed. Pings come only from the template's `message`,
read **before** any value is substituted:

| Written in `message` | Pings |
| --- | --- |
| `<@&id>` | that role |
| `<@id>`, `<@!id>` | that user |
| `@everyone`, `@here` | everyone, in `Webhooks.of(plugin)` only |

Up to 100 roles and 100 users. Past that, the rest do not ping and it is
reported once. Mentions in an embed never notify anyone, in Discord or here.

## Delivery

- **Off the main thread.** Requests run on the library's async scheduler
  (`runAsync`). A wait (a rate limit, a retry, a coalescing window) is a
  delayed task, never a sleeping thread. The returned stages and the
  `onInvalidated` listener complete off the main thread; hop with `Tasks`
  before touching a player.
- **In order.** One FIFO queue per webhook, drained by one task at a time.
- **Bounded.** Up to 50 messages wait per webhook and 1000 in total. Past that,
  the oldest waiting message to that webhook is dropped (`DROPPED`); with
  nothing of its own waiting, the new one is. Drops are counted and reported at
  most once a minute.
- **Rate limits.** A 429 is retried after its `retry_after`, or after
  `Retry-After` when Cloudflare answers. A global limit pauses every webhook.
  `X-RateLimit-Remaining: 0` makes the webhook wait out
  `X-RateLimit-Reset-After` before the next message.
- **Retries.** A 5xx or a network error is retried after 1s and then 2s, three
  attempts in all. Any other 4xx is not retried, and Discord's reason is
  reported once.
- **Dead webhooks.** A 404 or 401 means the webhook was deleted or its token is
  wrong, and it will not come back. The webhook is remembered as dead for six
  hours, its queue is dropped (`INVALIDATED`) and `onInvalidated` runs once for
  every plugin that had messages for it. After that, sends and `verify` answer
  immediately without a request. That is the part that keeps the server off
  Cloudflare's invalid-request ban list.
- **HTTP.** Redirects are never followed. Connecting times out after 5s and a
  request after 10s.

## Security notes

- **The URL is rebuilt, never reused.** `parse` accepts only `https://` on
  `discord.com`, `discordapp.com`, `ptb.discord.com` or `canary.discord.com`,
  the path `/api/webhooks/<17–20 digit id>/<token>` and an optional trailing
  slash. It rejects credentials, ports, queries, fragments, IP addresses,
  look-alike hosts (`discord.com.evil.com`) and any other path. Every request
  goes to `https://discord.com/api/webhooks/<id>/<token>`, built from the
  parsed parts.
- **The token stays out of logs.** `toString()` is masked. Warnings name the
  masked target. The response to `verify`, which repeats the token, is never
  logged.
- **Asking a player is private.** `ask` uses a
  [`sensitive()`](input.md#answers-that-are-secrets) text input: a dialog where
  the client has one. Otherwise the chat line is taken at `LOWEST` priority,
  before any other plugin reads it, and it is never shown back.
- **Store `secret()` like a password.** The URL is all anyone needs to post as
  the webhook.

## Lifecycle

- A plugin's listeners are forgotten, and its messages still waiting dropped,
  one tick after its `onDisable`. A message sent from `onDisable` therefore
  still has its chance; one already sent finishes either way. Release is per
  plugin load, so a reload's new load keeps its own listeners.
- When the library stops, each queued message gets one attempt inline, for 3
  seconds in all. Whatever is left is dropped. Sends after that answer
  `DROPPED`.
- **Reload.** The module keeps nothing derived from the palette (colours are
  resolved on every send), so it has no `invalidateAll()` and is not hooked
  into `ExyliaLib.loadPalette`. Converted template text is cached by its raw
  text, in a Caffeine cache capped at 1024 entries.

## Where the code is

| | |
| --- | --- |
| Public API | `src/main/java/net/exylia/lib/discord/` (`Webhooks`, `PluginWebhooks`, `WebhookTarget`, `WebhookTemplate`, `WebhookResult`, `WebhookCheck`) |
| Payload, escaping, mentions, limits | `discord/internal/Payloads` (no server, no network) |
| Queues, rate limits, dead webhooks | `discord/internal/WebhookRuntime` (`installForTests` replaces the network and the scheduler) |
| Sensitive chat answers | `input/TextInput.sensitive`, `input/internal/InputListener.onSensitiveChat` |
| Tests | `src/test/java/net/exylia/lib/discord/` |
