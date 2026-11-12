# Click spots

`net.exylia.lib.spot.ClickSpots` (1.603.0+) keeps places in the world that open
something when a player clicks them. A world table is the reference case: the
game lives on a screen, and the place only opens it.

A spot is a **block**, an **entity** or a **placed NPC** (ExyliaDisplays NPCs
included), with an optional hologram over it. The block is protected, and a click
counts even inside another plugin's region (cancelled events are still handled).

```java
spots = new ClickSpots(plugin, "tables.yml", new ClickSpots.Hooks() {
    public List<ClickSpots.Kind> kinds() {           // what an admin can place
        return games.stream().map(g -> new ClickSpots.Kind(g.id(), g.name(), g.icon())).toList();
    }
    public void clicked(Player player, ClickSpots.Spot spot) {   // on the player's thread
        openGame(player, spot.kind());
    }
    public int playersAt(ClickSpots.Spot spot) { return playing(spot.kind()); }  // optional
}).permission("myplugin.admin");

spots.reload();   // on /reload
spots.close();    // on disable
```

`lines(spot, kind)` and `look()` restyle the hologram; its lines are read again
every two seconds.

## Admin screens

The library draws them; the plugin only links to them.

- `spots.openAdmin(player, back)`: the list (Admin list layout), the create button and each spot's detail.
- `spots.startCreate(player, kind, id)`: the place wizard straight away (for a `create <kind>` command).
- `spots.openDetail(player, id)`, `spots.confirmRemove(player, id)`: for `edit` and `remove` commands.

Creating a spot is "pick the game, click the block or NPC". The detail screen
moves it (the same wizard), changes the game, toggles the hologram, teleports
there and removes it after a confirm.

The place gesture is `Wizards.of(plugin).askTarget(player, title, prompt, accepted, abandoned)`:
a pick that a block, an entity or an NPC answers, handing back a `WizardTarget`.
Other picks keep ignoring entity clicks.

## Old `PlacedTables` files

A file written by `PlacedTables` loads as spots: a table with seats becomes the
block under its board, one without seats becomes the block `legacyAhead` in front
of its play spot (pass the old `ahead` to the four-argument constructor). The
original file is copied to `<file>.old` before it is first rewritten.
