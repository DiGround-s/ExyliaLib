# Dialogues

RPG conversations in a box at the bottom of the screen, with no resource pack: the
speaker's name and face, each line typed out, and answers under the last line.
*Since 1.590.0.*

```java
Component guide = Text.of("{highlight}Guide").build();
Dialogue talk = Dialogue.of(List.of(
                Dialogue.Line.of(Text.of("{letters}Welcome, traveller.").build()).speaker(guide).face("Notch"),
                Dialogue.Line.of(Text.of("{letters}Shall I show you around?").build()).speaker(guide).face("Notch")))
        .answers(List.of(Text.of("{letters}Lead the way").build(), Text.of("{letters}Not now").build()))
        .onLine(index -> playVoice(player, index));

Dialogues.of(this).open(player, talk).thenAccept(result -> {
    if (result.finished() && result.answer() == 0) tour(player);
});
```

## Two modes

| `mode` | Where | How the player answers |
| --- | --- | --- |
| `HUD` (default) | a [HUD](hud.md) widget over the hotbar | They keep walking and looking round. A sneak, a click on the air or a click on an NPC goes on; the first press finishes the line. Under the last line the wheel moves the choice (the held item stays put), a number key picks that answer, a sneak or click confirms. |
| `SCREEN` | a [screen](screens.md) with a cursor, the box at the bottom | They stand still. A click on the box goes on; under the last line they click an answer. Closing the screen cancels. |

## Contracts

- One conversation per player across plugins: opening another, `Dialogues.close(player)`, quitting or dying ends
  the first with `Result.CANCELLED`. The future completes on the player's thread.
- `onLine(index)` runs on the player's thread as each line starts: sounds, actions, a camera cue.
- A Bedrock player, a server without PacketEvents, and (in `HUD` mode) a player who hid their HUD read every line
  in chat and answer in a choice input.
- The press that opened the conversation (a click on an NPC) never skips its first line.
- `hints(...)` replaces the small line under the box (reading, choosing on the HUD, choosing on a screen); the
  library's own are in `phrases.yml`.
- Faces are a player's skin (`face("Notch")`); until it loads a stranger's face stands in.
- `width` (pixels the text wraps at, 220 by default) and `speed` (letters a second, 40; 0 shows lines whole).

## Source and tests

`net.exylia.lib.dialogue`; `internal/DialogueRuntime` (presses, wheel, layout). `DialogueRuntimeTest` covers the
wheel and number keys.
