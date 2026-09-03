# Chat module

Who reads whose chat messages. A plugin registers one rule and the library
takes the receivers that rule refuses off every message before the server
delivers it — an event whose players talk only to each other, an arena whose
chat never leaves it, a spectator who reads a match without being read back.
Since 1.89.0.

Entry point: `net.exylia.lib.chat.Chats`.

```java
// an event's chat is its own: outside is deaf to it, and it is deaf to outside
Chats.rule(this, (listener, speaker) -> {
    var theirs = events.eventOf(listener);
    var his = events.eventOf(speaker);
    if (theirs.isEmpty() && his.isEmpty()) return true;   // both outside
    if (theirs.isEmpty() || his.isEmpty()) return false;  // never crosses
    return theirs.get() == his.get();
});
```

## API

| Method | Contract |
| --- | --- |
| `rule(plugin, rule)` | registers this plugin's rule, replacing its previous one |
| `clear(plugin)` | drops this plugin's rule |
| `canHear(listener, speaker)` | the answer every rule agrees on, for text a plugin delivers itself |
| `bypass(player, bypass)` | puts a player above every rule, or back under them |
| `bypassing(uuid)` | whether they are above the rules right now |

`ChatRule` is a functional interface: `boolean canHear(Player listener, Player
speaker)`.

## Behavior

- **Nothing is cancelled and nothing is re-sent.** The message keeps the format,
  hover text and click events its chat plugin gave it; only the audience is
  smaller. The console keeps the full line, because non-player audiences are
  never removed.
- **Every rule has to agree.** Rules are combined with AND, the way
  `VisibilityRule` is: a message reaches a player only when no plugin objects.
  One rule per plugin, dropped when that plugin is disabled.
- **The question is asymmetric.** `canHear(a, b)` and `canHear(b, a)` are
  separate answers, which is what lets a spectator read the players of a match
  while the players read nothing back.
- **A speaker always reads themselves.** Rules are never asked about that.
- **A bypass is the only way past a no.** Since rules agree with AND, no rule
  can undo another plugin's refusal; `Chats.bypass` takes the player out of the
  question instead. They read every message and every message of theirs is read,
  in both directions. Staff reading an isolated chat is what it is for. It lasts
  while the player is online — a quit drops it, and so does a restart — so a
  plugin that wants it remembered stores it and sets it again on join.
- **Nothing is remembered between messages.** A rule is asked while the message
  is being delivered, so a player who joins an event mid-sentence is already
  inside it for the next line.
- **A broken rule loses its say, not the server's chat.** A rule that throws is
  ignored for that message and reported once, by plugin name.
- **A server with no rules pays nothing.** Both handlers read one map and
  return.

## What is needed

A chat plugin that lets the server deliver the message: Paper's own chat and
every renderer-based chat plugin do. Both chat events are handled — the modern
`AsyncChatEvent` and the legacy `AsyncPlayerChatEvent` — because which one
carries the message is the chat plugin's choice, and on Paper they describe the
same audience.

A plugin that instead cancels the event and sends each line itself has taken
delivery over; those copies are out of reach, and an audience handed over as
unmodifiable is reported once and left alone. Both cases fail open: chat keeps
working, isolation does not apply.

## What this does not cover

Chat, and only chat. Join and quit lines, death messages, broadcasts and
private messages are not chat events and are not touched. Neither is a message
that arrives from another server through Redis, because it never was a chat
event here — filter it where it is delivered, with `Chats.canHear`.

## Threads

Rules are asked on the chat thread, which is not the main thread. A rule must
be cheap and must only read shared state: no world access, no entity access,
nothing that blocks. Registering and clearing a rule is safe from any thread.

## Reload

Nothing derived from the palette is cached here, so the module has no
`invalidateAll()` and is deliberately absent from `ExyliaLib.loadPalette`.

## Source

- Public: `chat/Chats.java`, `chat/ChatRule.java`.
- Internal: `chat/internal/ChatRuntime.java` (the rules and their AND),
  `chat/internal/ChatListener.java` (the two chat events and the quit that
  drops a bypass).
