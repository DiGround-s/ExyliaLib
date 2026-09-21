# Crate module

A crate a plugin gets by handing over its catalogue: one key in, one random
reward out. The roll, the keys, the reels, the payout, the blocks that open it
and the player rows are shared. What a reward is stays with the plugin.
Available since 1.189.0.

Entry point: `net.exylia.lib.util.crate.Crates`.

## Using

```java
private PluginCrates crates;

@Override
public void onEnable() {
    PluginMenus menus = Menus.of(this, "trims");
    PluginActions actions = Actions.of(this, "trims");
    menus.load("crate", YamlConfiguration.loadConfiguration(new File(getDataFolder(), "menus/crate.yml")));
    menus.load("crate_open", YamlConfiguration.loadConfiguration(new File(getDataFolder(), "menus/crate_open.yml")));

    crates = Crates.of(this).start(new CrateCatalogue<Trim>() {
                public Collection<Trim> all() { return registry.all(); }
                public String id(Trim trim) { return trim.id(); }
                public String tier(Trim trim) { return trim.tier(); }
                public String name(Trim trim) { return trim.displayName(); }
                public String icon(Trim trim) { return trim.icon(); }
                public String description(Trim trim) { return trim.description(); }
                public ItemStack token(Trim trim, Player viewer) { return null; } // no token items
                public boolean ownsOtherwise(Player player, Trim trim) { return player.hasPermission(trim.permission()); }
            },
            () -> config.get().crate(),
            () -> messages.get().crate(),
            bound -> config.update(c -> c.withCrate(c.crate().withBlocks(bound))),
            menus, actions, "crate", "crate_open");

    crates.onChange(uuid -> redrawWardrobe(uuid));
}

// Reload: the settings and the catalogue are read on every opening; only the
// bound blocks need rebuilding.
crates.rebuild();

// Admin commands stay in the plugin and call the API.
crates.addKeys(target, 5).thenAccept(keys -> tell(sender, keys));
crates.unlock(target, "sentry").thenAccept(isNew -> ...);
crates.bindBlock(admin.getTargetBlockExact(6));
crates.giveKeyItems(player, 3);
```

The settings nest in the plugin's configuration as a `CrateSettings` section,
and the messages in its messages file as a `CrateMessages` section:

```yaml
crate:
  enabled: true
  start-keys: 0            # given once, when the player's row is first created
  duplicate-refund: 1      # keys back when a reel lands on something already owned
  reward: UNLOCK           # UNLOCK, ITEM or BOTH
  blocks: []               # server,world,x,y,z,yaw,pitch — written by bindBlock
  max-at-once: 4           # capped at 7, the width of the opening screen
  spin-frames: 34          # faces the first reel runs through, capped at 200
  stagger-seconds: 2.0     # the gap between one reel landing and the next
  on-spin: { ... }         # EffectConfig: a hat note by default
  on-win: { ... }          # EffectConfig: the level-up sound
  on-duplicate: { ... }    # EffectConfig: a bass note
  key-item:
    material: TRIPWIRE_HOOK
    name: '{primary}&lCRATE KEY'
    lore: [ ... ]
    glow: true
  tiers:
    common:    { name: Common,    color: '{muted}',     chance: 60.0, priority: 1 }
    rare:      { name: Rare,      color: '{info}',      chance: 25.0, priority: 2 }
    epic:      { name: Epic,      color: '{accent}',    chance: 12.0, priority: 3 }
    legendary: { name: Legendary, color: '{highlight}', chance: 3.0,  priority: 4 }
```

```yaml
crate:
  won: '%prefix%{success}You unboxed %tier% {highlight}%reward%{success}.'
  duplicate: '%prefix%{warning}%tier% {highlight}%reward% {warning}again. {highlight}%refund% {warning}key back.'
  no-keys: '%prefix%{error}That costs {highlight}%amount% {error}keys and you have {highlight}%keys%{error}.'
  busy: '%prefix%{warning}Your crate is still opening.'
  empty: '%prefix%{error}There is nothing in the crate yet.'
  disabled: '%prefix%{error}The crate is off on this server.'
  keys-received: '%prefix%{success}You received {highlight}%amount% {success}crate keys.'
  rewards-claimed: '%prefix%{success}{highlight}%amount% {success}rewards were waiting for you.'
```

`%prefix%` is the plugin's own, as `Prefixes` holds it. A line left blank is not
sent.

## What is shared and what is not

| The crate | The plugin |
| --- | --- |
| The roll: a rarity by weight among those that hold anything, then a reward inside it, each equally likely | The catalogue: `CrateCatalogue<T>` |
| Keys on the account and key items in the hand | The commands that give, take and check them |
| The two screens, their animation and their sounds | The two menu files, in its own `resources` |
| The duplicate refund and the three reward modes | Its token item, if it has one |
| The bound blocks and their protection | The admin command that binds them |
| The player rows | Its placeholders, which read `keys`, `owns`, `count`, `odds` |

## The screens

The plugin ships two menu files and loads them into its `PluginMenus` under the
ids it passes to `start`. The crate reads them by id on every opening, so a
menu reload needs no call. Buttons are written against the namespace of the
`PluginActions` passed to `start`, where the crate registers two actions:

| Action | Does |
| --- | --- |
| `<namespace>:crate` | Opens the question: how many crates |
| `<namespace>:crate_open <amount>` | Spends that many keys from the account and opens the reels |

The question's `amounts` section always gets seven rows, one per reel column,
with the choices spread where their reels will fall and the unused columns
drawn with `blank_template`. A choice the player cannot afford is drawn with
`empty_template`.

The opening screen's `reels` section is one list of seven columns of six cells,
read down one column before the next. Its templates:

| Template | Drawn for |
| --- | --- |
| `item_template` | a face going past |
| `middle_template` | the face crossing the landing row |
| `winner_template` | where a reel stopped on something new |
| `duplicate_template` | where a reel stopped on something already owned |
| `near_template` | what a stopped reel came within a slot of |
| `lane_template` | the landing row in a column no reel uses |
| `blank_template` | every other cell of an unused column |

The `again` section is drawn with `waiting_template` until the last reel lands,
then with `item_template`.

### Placeholders

| Where | Placeholders |
| --- | --- |
| `reels` rows | `%reward_id%`, `%reward_name%`, `%reward_material%`, `%reward_description%`, `%reward_tier%` (colour and name), `%reward_tier_id%`, `%reward_tier_color%`, `%refund%` (keys a duplicate gave back) |
| `amounts` rows | `%amount%`, `%keys%`, `%missing%` (keys that choice still needs) |
| anywhere | `%keys%`, `%unlocked_count%` (catalogue rewards owned, unlocked or `ownsOtherwise`), `%catalogue_count%`; `%amount%` on the opening screen |

The winner and duplicate templates' clicks are the plugin's own actions — wear
it, preview it — so they are only YAML.

### Reference `menus/crate.yml`

```yaml
# The crate, and the only question it asks: how many at once.
#
# One key opens one crate. Picking four spends four keys and opens four reels
# side by side on the next screen, menus/crate_open.yml.
#
# "amounts" always fills its seven slots and spreads the choices across them
# the way the reels are spread on the next screen, so where you click is where
# that reel will fall. The slots it does not need are drawn with
# "blank_template".
#
# Placeholders in "amounts": %amount%, %keys%, %missing%
# Placeholders anywhere: %keys%, %unlocked_count%, %catalogue_count%

title: "{primary}&lCRATE"
size: 27

open_sounds:
  - "minecraft:block.ender_chest.open|1.0|1.4"
click_sounds:
  - "minecraft:block.note_block.hat|1.0|1.0"
close_sounds:
  - "minecraft:block.barrel.close|1.0|0.7"

filler:
  global:
    material: BLACK_STAINED_GLASS_PANE
    hide_tooltip: true

sections:

  amounts:
    slots: "10,11,12,13,14,15,16"

    item_template:
      material: ENDER_CHEST
      glowing: true
      hide-attributes: true
      amount: "%amount%"
      name: "{primary}&lOPEN %amount%"
      lore:
        - "{secondary}Cost:"
        - " {letters_black}▎ {highlight}%amount% {letters}keys of your {info}%keys%"
        - ""
        - "{warning}➥ Click to open"
        - ""
      actions:
        - "<namespace>:crate_open %amount%"

    empty_template:
      material: GRAY_DYE
      hide-attributes: true
      name: "{muted}&lOPEN %amount%"
      lore:
        - "{secondary}Cost:"
        - " {letters_black}▎ {highlight}%amount% {letters}keys of your {info}%keys%"
        - ""
        - "{error}🔒 %missing% more keys needed"
        - ""

    blank_template:
      material: BLACK_STAINED_GLASS_PANE
      hide_tooltip: true

items:

  keys:
    slot: 4
    material: TRIAL_KEY
    glowing: true
    name: "{primary}&lYOUR KEYS"
    lore:
      - "{secondary}Holding:"
      - " {letters_black}▎ {highlight}%keys% {letters}keys"
      - ""
      - "{secondary}Collection:"
      - " {letters_black}▎ {success}%unlocked_count%{letters_black}/{info}%catalogue_count% {letters}unboxed"
      - ""

  # The plugin's own main menu, if it has one.
  back:
    slot: 22
    sound: "minecraft:item.bundle.remove_one|1.0|0.5"
    material: 'basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjIzZmI2NzQyOTcxNmIyMWJjNmU4ZTdkNjY5Y2VkZGY2NWIxM2UwNzkwYTVjZTU1YjJlMDc3YjgyZDE5ZTEyNCJ9fX0='
    name: "{error}&lBACK"
    lore:
      - ""
      - "{warning}➥ Click to go back"
      - ""
    actions:
      - "<namespace>:open"
```

### Reference `menus/crate_open.yml`

```yaml
# The crates opening: one reel per crate, falling down its own column.
#
# Each reel has its own faces, its own prize and its own finishing line,
# crate.stagger-seconds after the one before it, so they land one at a time.
# Where they sit is worked out from how many there are and is always centred.
#
# The prize lands on the third row down - the row the two markers point at and
# the row "lane_template" draws right across.
#
# Nothing is cleared when a reel stops. What it came within one slot of stays
# on screen under "near_template".
#
# "reels" is one list covering seven columns of six, read down one column
# before starting the next: 1,10,19,28,37,46 then 2,11,20,29,38,47 and so on.
# Moving those slots moves the reels; keep them column by column.
#
# "again" is drawn as "waiting_template" until the last reel lands.
#
# Nothing here spends anything. The keys were spent and the prizes written down
# before the first frame, so closing this window mid-fall costs nothing.
#
# Placeholders in "reels": %reward_id%, %reward_name%, %reward_material%,
#   %reward_description%, %reward_tier%, %reward_tier_id%, %reward_tier_color%,
#   %refund%
# Placeholders anywhere: %amount%, %keys%, %unlocked_count%, %catalogue_count%

title: "{primary}&lOPENING &8» {highlight}%amount%"
size: 54

open_sounds:
  - "minecraft:block.ender_chest.open|1.0|1.4"
click_sounds: []
close_sounds:
  - "minecraft:block.barrel.close|1.0|0.7"

filler:
  global:
    material: BLACK_STAINED_GLASS_PANE
    hide_tooltip: true

sections:

  reels:
    slots: "1,10,19,28,37,46,2,11,20,29,38,47,3,12,21,30,39,48,4,13,22,31,40,49,5,14,23,32,41,50,6,15,24,33,42,51,7,16,25,34,43,52"

    # A face going past.
    item_template:
      material: "%reward_material%"
      hide-attributes: true
      name: "%reward_name%"
      lore:
        - "{secondary}Rarity:"
        - " {letters_black}▎ %reward_tier%"
        - ""
        - "{secondary}Information:"
        - " {letters_black}▎ %reward_description%"
        - ""

    # The face crossing the row the prize will land on.
    middle_template:
      material: "%reward_material%"
      hide-attributes: true
      glowing: true
      name: "%reward_name%"
      lore:
        - "{secondary}Rarity:"
        - " {letters_black}▎ %reward_tier%"
        - ""
        - "{secondary}Information:"
        - " {letters_black}▎ %reward_description%"
        - ""
        - "{warning}➥ On the line"
        - ""

    # Where a reel stopped: something new. The actions are the plugin's own.
    winner_template:
      material: "%reward_material%"
      hide-attributes: true
      glowing: true
      name: "%reward_name%"
      lore:
        - "{secondary}Rarity:"
        - " {letters_black}▎ %reward_tier%"
        - ""
        - "{secondary}Information:"
        - " {letters_black}▎ %reward_description%"
        - ""
        - "{success}✔ It is yours for good"
        - ""
        - "{warning}➥ Left click to wear it"
        - "{muted}➥ Right click to preview"
        - ""
      actions:
        - "left: <namespace>:choose %reward_id%"
        - "right: <namespace>:preview %reward_id%"

    # Where a reel stopped: something already owned.
    duplicate_template:
      material: "%reward_material%"
      hide-attributes: true
      name: "%reward_name%"
      lore:
        - "{secondary}Rarity:"
        - " {letters_black}▎ %reward_tier%"
        - ""
        - "{secondary}Information:"
        - " {letters_black}▎ %reward_description%"
        - ""
        - "{warning}You already owned this one"
        - " {letters_black}▎ {highlight}%refund% {letters}keys came back"
        - ""
        - "{warning}➥ Left click to wear it"
        - "{muted}➥ Right click to preview"
        - ""
      actions:
        - "left: <namespace>:choose %reward_id%"
        - "right: <namespace>:preview %reward_id%"

    # What a stopped reel came within a slot of.
    near_template:
      material: "%reward_material%"
      hide-attributes: true
      name: "{muted}%reward_name%"
      lore:
        - "{secondary}Rarity:"
        - " {letters_black}▎ %reward_tier%"
        - ""
        - "{secondary}Information:"
        - " {letters_black}▎ %reward_description%"
        - ""
        - "{error}✖ This one went past"
        - ""
        - "{muted}➥ Right click to preview"
        - ""
      actions:
        - "right: <namespace>:preview %reward_id%"

    # The landing line, where no reel is falling through it.
    lane_template:
      material: LIME_STAINED_GLASS_PANE
      name: "{success}&lTHE LINE"
      lore:
        - " {letters_black}▎ {letters}What stops here is yours"

    # A column no reel is using.
    blank_template:
      material: BLACK_STAINED_GLASS_PANE
      hide_tooltip: true

  # The way back, once there is something to go back from.
  again:
    slots: "45"

    item_template:
      material: ENDER_CHEST
      glowing: true
      hide-attributes: true
      name: "{primary}&lOPEN MORE"
      lore:
        - " {letters_black}▎ {letters}Back to the crate"
      actions:
        - "<namespace>:crate"

    waiting_template:
      material: BLACK_STAINED_GLASS_PANE
      hide_tooltip: true

items:

  # The two markers capping the row the prizes land on.
  marker_left:
    slot: 18
    material: LIME_STAINED_GLASS_PANE
    name: "{success}&l▶"
    lore:
      - " {letters_black}▎ {letters}What stops here is yours"

  marker_right:
    slot: 26
    material: LIME_STAINED_GLASS_PANE
    name: "{success}&l◀"
    lore:
      - " {letters_black}▎ {letters}What stops here is yours"

  keys:
    slot: 0
    material: TRIAL_KEY
    name: "{primary}&lYOUR KEYS"
    lore:
      - "{secondary}Holding:"
      - " {letters_black}▎ {highlight}%keys% {letters}keys"
      - ""
      - "{secondary}Collection:"
      - " {letters_black}▎ {success}%unlocked_count%{letters_black}/{info}%catalogue_count% {letters}unboxed"
      - ""
```

## API

| Method | Contract |
| --- | --- |
| `Crates.of(plugin)` | The plugin's crate, one instance per plugin |
| `start(catalogue, settings, messages, saveBlocks, menus, actions, menu, openingMenu)` | Registers the two actions and the listeners, binds the blocks, reads everybody online. Again: stops first, then starts over |
| `rebuild()` | Registers the bound blocks again from the settings |
| `stop()` | Pays out the reels in the air the quit way; takes down its actions, blocks and listeners |
| `onChange(uuid -> ...)` | After a player's keys or unlocks change; only players online here, on their thread |
| `load(player)` | Done once their row is in memory — for a plugin that strips what they no longer own on join |
| `isLoaded(uuid)`, `owns(uuid, id)`, `unlocked(uuid)`, `keys(uuid)` | From memory; `false`, empty or `0` for a player not in memory |
| `ownedCount(player)` | Catalogue rewards owned, unlocked or `ownsOtherwise` |
| `fetchKeys(uuid)` | The keys of a player here or not |
| `unlock`, `lock`, `clearUnlocks`, `addKeys`, `setKeys` | For a player here or not; answer whether it was new, whether they had it, how many there were, the keys afterwards |
| `open(player)`, `spin(player, amount)` | The question, or the reels with keys from the account |
| `keyItem(viewer)`, `isKey(item)`, `giveKeyItems(player, amount)` | Key items that can be held, traded and dropped |
| `tierIds()`, `tierId(written)`, `tier(id)`, `count(tierId)`, `odds(tierId)` | The rarities, how many rewards each holds, and the odds an opening honours |
| `bindBlock(block)`, `unbindBlock(block)`, `boundBlocks()`, `clearBlocks()` | The blocks that open it, saved through `saveBlocks` |

## Contracts

- **The roll.** A rarity by weight among the rarities that hold at least one
  reward, then a reward inside it, each equally likely. A rarity with nothing
  in it takes no share; weights need not sum to 100. A reward naming a rarity
  that is not declared falls in the first one. An empty crate charges nothing.
- **Reward modes.** `UNLOCK` writes the id to the player's row; `ITEM` hands the
  catalogue's token over and unlocks nothing, so it is never a duplicate;
  `BOTH` unlocks and hands the token over only when it was new. A catalogue
  whose `token` answers `null` makes `ITEM` and `BOTH` behave as `UNLOCK`.
- **Duplicates** refund `duplicate-refund` keys. A permission that grants a
  reward (`ownsOtherwise`) does not make it a duplicate: the unlock is written
  anyway, so it outlives the rank.
- **Keys** are spent before the first frame and never go below zero. Two
  clicks in one tick cannot spend the last key twice. A key item held in the
  main hand, right-clicked on a bound block, opens one crate on the spot; any
  other click on the block opens the question. Key items are inert: they are
  never placed, eaten or thrown, whatever they are drawn as.
- **One spin at a time** per player; a second is refused with `busy`.
- **Payout** happens as each reel lands, in the order they land. Closing the
  window changes nothing. Quitting, or the plugin being disabled, pays out
  every reel still falling at once: unlocked on the row, tokens kept in the
  plugin's pending table until the next join — or, when that table would not
  take one, a key handed back instead.
- **Rewards** go through the plugin's `PluginRewards`, which `start` sets to
  `OverflowPolicy.QUEUE` into `PendingRewards.database(plugin)`, claimed on
  join with the `rewards-claimed` line. A plugin that sets its rewards up
  differently after `start` changes the crate's too.
- **Ids** are stored trimmed and lower-cased; `owns`, `lock` and `unlock`
  normalise what they are given. An id with a comma is refused.
- **Bound blocks** are protected and answer only through the crate. A block in
  a world that is not loaded yet is registered when that world loads. The
  plugin's own clickable blocks are left alone.
- **Folia.** Player work runs on the player's entity scheduler, the blocks on
  their region. Nothing touches the database on a game thread.

## Storage

Table `exylia_crate_players`, in the plugin's own database (`database.yml`):

| Column | Type | |
| --- | --- | --- |
| `id` | `VARCHAR(128)`, key | `<plugin>:<uuid>` |
| `plugin` | `VARCHAR(64)` | the plugin's name |
| `uuid` | `VARCHAR(36)` | the player |
| `keys` | integer | keys on the account |
| `unlocked` | unbounded text | unlocked ids, comma separated, in the order they came |
| `created_at`, `updated_at` | `BIGINT` | epoch milliseconds |

A row is read when its player joins and answered from memory until they leave;
every change is written through at once, behind the player's previous write.
The start keys are written on the row a join creates, so they are given once
ever. A row changed for somebody who is not here is read, changed and written,
and never cached; one written by an admin before the player ever joined carries
no start keys.

## Lifecycle

`Crates.release(plugin)` runs when the plugin is disabled, before its menus,
blocks, actions and tasks go: reels in the air are cancelled and paid out
onto rows the plugin's database, released a tick later, still writes.
`releaseAll()` does the same on shutdown. Nothing is derived from the palette,
so there is nothing to invalidate on reload.

## Where the code is

| | |
| --- | --- |
| Public API | `util/crate/Crates`, `PluginCrates`, `CrateCatalogue`, `CrateSettings`, `CrateMessages`, `CrateTier`, `CrateReward` |
| Internal | `util/crate/internal/CrateStore`, `CrateRow`, `Prizes`, `TierTable`, `Reel`, `CrateScreens`, `BoundBlocks` |
| Tests | `util/crate/CrateReelTest`, `CrateTierTest`, `CratePrizesTest`, `CrateSettingsTest` |
