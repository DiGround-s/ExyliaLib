# Tuner

Fine adjustment with the mouse wheel while the player looks at the result: a
hologram nudged a hundredth of a block at a time, an NPC turned five degrees,
a display scaled up a notch. One session per player, a boss-bar HUD, and the
consumer decides what each value is and what saving means.

```java
StoredThing[] work = {original};
Tuner.of(plugin, "spawn")
        .key("hologram:spawn")                      // one player at a time per subject
        .axis(TunerAxis.blocks("X", () -> work[0].x(), x -> work[0] = work[0].withX(x)))
        .axis(TunerAxis.blocks("Y", () -> work[0].y(), y -> work[0] = work[0].withY(y))
                .snap(y -> Math.round(y * 2) / 2.0))
        .axis(TunerAxis.relative("Forward", d -> work[0] = work[0].forward(player, d)))
        .axis(TunerAxis.degrees("Yaw", () -> work[0].yaw(), yaw -> work[0] = work[0].withYaw(yaw)))
        .axis(TunerAxis.of("Scale", () -> work[0].scale(), s -> work[0] = work[0].withScale(s))
                .steps(0.05, 0.1, 0.25, 0.5).range(0.1, 10).unit("x"))
        .hud(messages.hud())                        // translated; defaults are English
        .hints(messages.hints())
        .onChange(session -> preview(work[0]))      // redraw the live thing, do not save
        .onEnd((session, end) -> {
            if (end.kept()) save(work[0]); else preview(original);
        })
        .start(player)
        .orElse(null);                              // empty: somebody else holds the key
```

## Controls

| Key | Does |
|-----|------|
| Wheel up / down | Current value + / − one step |
| Sneak + wheel | Bigger / smaller step |
| F (swap hands) | Next axis; sneak + F the previous one |
| Click | Save and finish |
| Sneak + click (or a right-click on a block) | Snap: block centre, nearest 15°, whatever the axis says |

There is no cancel key: with an empty hand the client sends no drop and no
right-click in the air, so a key would work only sometimes. Offer cancel as a
command or a chat button calling `session.cancel()`.

The hotbar slot follows the wheel (so every notch counts at any ping, where a
cancelled slot change loses the notches sent before the client hears of it) and
is put back when the session ends.

While a session runs, placing, breaking, using, hitting entities and dropping
are refused.

## Axes

| Factory | Steps | Snap | Notes |
|---------|-------|------|-------|
| `TunerAxis.of(label, get, set)` | 0.01, 0.05, **0.1**, 0.5, 1 | none | the base |
| `TunerAxis.blocks(...)` | same | centre of the block | |
| `TunerAxis.degrees(...)` | 1, **5**, 15, 45 `°` | nearest 15° | folded into -180..180 |
| `TunerAxis.relative(label, moveBy)` | block steps | none | shows the distance moved; `moveBy` gets each delta |

Then `.steps(...)`, `.step(index)`, `.unit(...)`, `.range(min, max)` (a bounded
axis fills the bar to show where it is), `.snap(op)`.

## HUD

Two boss bars: `hud` (default `Tuner.DEFAULT_HUD`) and `hints` (blank for
none). Placeholders: `%subject%`, `%axis%` (formatted), `%value%`, `%step%`
(both with the unit), `%index%`, `%count%`. Values substitute on the parsed
component, so a notch costs no parse. Call `session.redraw()` after changing
something the bars show from outside.

## How it ends

`onEnd` runs exactly once with a `TunerEnd`:

| End | `kept()` | When |
|-----|----------|------|
| `SAVED` | yes | click, or `session.save()` |
| `CANCELLED` | no | `session.cancel()` |
| `ABORTED` | no | `session.abort()`: the subject is gone |
| `REPLACED` | yes | the player started another session |
| `QUIT`, `DEATH` | yes | |
| `MOVED_AWAY` | yes | world change, or a teleport over 32 blocks |
| `DISABLED` | yes | the owning plugin was disabled |

## Threads

Gestures arrive on the player's thread (their region on Folia); axes,
`onChange` and `onEnd` run there. A session ended elsewhere (a console command,
the plugin disabling) calls back on that thread instead.

## Reload

Keeps nothing derived from the palette: not applicable.
