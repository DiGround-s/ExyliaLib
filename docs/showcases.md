# Showcase module

Places that show a plugin's cosmetics off on their own, one after another, for
as long as somebody is there to watch. Since 1.188.0.

Entry point: `net.exylia.lib.util.showcase.Showcases`.

## Using

```java
private PluginShowcases showcases;

@Override
public void onEnable() {
    showcases = Showcases.of(this)
            .visibleTo(player -> players.seesOthers(player))
            .start(() -> config.get().showcase(),
                    placed -> config.update(c -> c.withShowcase(c.showcase().withLocations(placed))),
                    this::playOne);
}

private @Nullable ShowcaseTurn playOne(ShowcaseStage stage) {
    Effect effect = stage.pick(registry.all(), Effect::id);
    if (effect == null) return null;
    SequenceRun run = sequences.play(effect.sequence(), stage.audience());
    return ShowcaseTurn.of(effect.sequence().durationMillis(), run::cancel);
}

// On reload
showcases.rebuild();

// Admin commands
showcases.add(admin.getLocation());
Location removed = showcases.removeNear(admin.getLocation()); // null when none is within 5 blocks
int had = showcases.clear();
List<String> places = showcases.all();
```

The settings nest in the plugin's configuration as a `ShowcaseSettings` section:

```yaml
showcase:
  locations: []        # server,world,x,y,z,yaw,pitch — written by the add command
  pause-seconds: 3.0   # the rest between one turn ending and the next starting
  radius: 24.0         # how close a player has to be for it to play and to see it
  only: []             # the ids it picks from; empty picks from every one
```

## What is shared and what is not

A kill effect needs two bodies and a sequence, an armour trim one body turning
on the spot, an arrow trail a flight. Each of those is the plugin's
`ShowcaseAct`. Everything around it is this module:

- the list of places in the plugin's own configuration, and the commands'
  `add`, `removeNear`, `clear` and `all`;
- a loop per place, on that place's region, once a second;
- a turn starts only once the last one and its rest are over, and only while
  somebody allowed to watch is within the radius;
- `stage.pick` never returns the one this place played last while there is
  another, and only what `only` names when it names anything;
- `stage.someone()` and `stage.someoneBut(other)` cast the watchers as bodies:
  two players standing there see one of them strike the other;
- `stage.audience()` is the same watchers as a `SequenceTarget`.

## Contracts

- **The act runs on the place's region** (the main thread off Folia) and must
  not block. A turn that has nothing to show yet returns `null` and is asked
  again a second later.
- **A turn is cancelled when the next starts**, when its place is removed or
  rebuilt, and when the plugin is disabled. It is not cancelled when it ends:
  a body given a life longer than the turn stands through the rest and goes the
  moment the next one arrives, so the stage never blinks empty between turns.
  `ShowcaseTurn.cancel()` may be called more than once.
- **Nobody is acted on.** A plugin that plays configured sequences strips the
  lines that act on a real player — `COMMAND`, `POTION`, `TITLE`, `ACTION_BAR`,
  `MESSAGE`, `CAMERA` — before a showcase plays them.
- **A place in a world that is not loaded** starts when that world loads.
- **Settings are read afresh** on every rebuild and every turn: a reload that
  changes `pause-seconds`, `radius` or `only` needs no call. Only a change to
  `locations` made outside the commands needs `rebuild()`, which a reload does.

## Lifecycle

`Showcases.release(plugin)` runs when the plugin is disabled: every loop is
cancelled and every turn taken away. Nothing is derived from the palette, so
there is nothing to invalidate on reload.

## Cost

With nobody near, one walk of the world's player list per place per second.
