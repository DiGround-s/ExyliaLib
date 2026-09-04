# Rewards

Giving a player what they earned.

```java
PluginRewards rewards = Rewards.of(this);

rewards.give(winner, event.rewards());
```

A reward is something a server owner configured — an item, a command, some money
— along with the odds of getting it and who is allowed to. This module holds
them, decides which ones happen, and hands them over on the right thread.

**Since 1.34.0.**

---

## Compatible with ExyliaCommons by construction

Every reward already configured across the ecosystem lives in a database column
written by the old module: `capture_pending_rewards`, `event_pending_rewards`,
`sc_powerup_zones`, and the reward lists inside every event and capture point.

`RewardCodec` reads and writes exactly that shape. A plugin migrating to this
library changes its imports and nothing else — no migration, no dual-read
window, no lost configuration.

### The stored form

A JSON array, the way a bare `new Gson().toJson(List<RewardEntry>)` over the old
Lombok bean produced it:

```json
[{"id":"…","name":"…","type":"ITEM","itemSnapshot":"bytes:…",
  "itemAmount":16,"chance":25.5,"condition":"%player_level% >= 10",
  "permission":"event.vip","deliveryMessage":"{success}You won!","priority":5}]
```

Fixed, and not ours to change:

- **Field names** are the old bean's fields. Renaming one orphans every row.
- **Null fields are omitted.** A command reward carries no `itemSnapshot` key at
  all. Writing it back would grow every row, and `rewardsJson` is a
  `VARCHAR(8192)` in the tables that already exist.
- **An empty list stores as SQL `NULL`, not `[]`**, because that is what
  commons' `serializeCollection` did.
- **`type` is one of `COMMAND`, `ITEM`, `MESSAGE`** in any row an unmigrated
  plugin has to read.

### What the new fields do to an old reader

`value`, `currency`, `minAmount`, `maxAmount` and `weight` are written **only
when they differ from their default**. A reward the old module could have
written serialises to exactly what it would have written, byte for byte, and
Gson on the old side ignores any extra key it does meet.

A type it has never heard of deserialises to a null `type`, so that plugin skips
one reward rather than losing the list. This library does the same, and reports
it.

### The legacy command column

Both pending-reward tables carry a `commandsJson` holding a plain
`["/give …"]`, written before rewards had types. Rows from that era are still
out there and still owed to a player:

```java
List<RewardEntry> owed = RewardCodec.decodeLegacyCommands(row.commandsJson());
```

---

## Editing a list on screen

Since 1.56.0.

```java
Rewards.of(this).editor(zone.rewards())
        .title("{primary}&lPOWER-UP REWARDS")
        .onSave(edited -> manager.save(zone, edited))
        .onCancel(() -> setupMenu.open(player))
        .open(player);
```

The [editor](editors.md) screen: pagination, add, edit, delete, copy, paste,
save and cancel. Creating a reward asks what it gives first, because the type
decides which fields the form can even name; an item reward then opens the icon
picker, and everything else goes straight to one prefilled dialog.

A reward copied here pastes into any other reward editor, in this plugin or
another.

## Reward types

| Type | Payload | Since |
| --- | --- | --- |
| `COMMAND` | `command` — run by the console, with or without a leading `/` | 1.34.0 |
| `ITEM` | `itemSnapshot` — a material, a head string, or `bytes:` | 1.34.0 |

| `MESSAGE` | `message`, in Exylia text notation | 1.34.0 |
| `ECONOMY` | `value` as written, plus an optional `currency` | 1.34.0 |
| `EXPERIENCE` | `value`, in points | 1.34.0 |
| `POTION` | `value`, as `util/Effects` reads it: `SPEED:1:300` | 1.34.0 |

The first three are the ones commons stored. `RewardType.isLegacy()` says which.

**An item reward is stored whole.** Since 1.111.0 the editor reads an inserted
item through `Source.whole`, so the name it was given, the lore under it, its
enchantments and its attributes are what the player is handed. Before that it
was read as an *icon*, which drops the name and lore on purpose — right for a
menu row, wrong for the reward itself, and the reason a written sword arrived
plain.

`ECONOMY` carries its amount as **text**, not a `double`: a decimal that goes
through a `double` on its way to the database does not come back the same.

---

## Building a reward

```java
RewardEntry coins = RewardEntry.economy("500")
        .name("{primary}&lSTARTER BONUS")
        .chance(50.0)
        .permission("event.vip")
        .build();
```

`RewardEntry` is **immutable**. Commons' was a mutable bean shared between the
config that loaded it, the executor that read it and the editor that wrote to
it; here an edit produces a new entry:

| | |
| --- | --- |
| `toBuilder()` | edit **this** reward — keeps its `id()` |
| `copy()` | duplicate it — new `id()` |

Identity is what `equals` compares. An edited reward is still the same reward; a
copy is a different one.

### Chance and weight are different questions

- **`chance`** is a percentage, `0`–`100`, and asks *does this one happen*.
  `100` is guaranteed and does not roll at all.
- **`weight`** asks *which of these happens*, and only matters to
  `roll` / `pick`. Default `1`; an entry of weight `0` or less is never picked.

The two compose: `roll` picks a winner by weight and then gives it, which means
the winner still faces its own `chance`. A rare reward that wins the draw can
still miss.

### Amounts

`itemAmount(n)` is a fixed amount. `amountBetween(min, max)` is a random one,
inclusive at both ends, and wins over the fixed value. Bounds typed the wrong
way round are put in order rather than refused. An item is never given in an
amount below one.

---

## Giving

```java
RewardDelivery delivery = rewards.give(player, event.rewards());
```

| Method | Does |
| --- | --- |
| `give(Player, List<RewardEntry>)` | gives all of them |
| `give(Player, RewardEntry)` | gives one |
| `giveOnPlayerThread(Player, List, Consumer<RewardDelivery>)` | the same, from any thread |
| `roll(Player, List<RewardEntry>)` | picks one by weight and gives it |
| `pick(List<RewardEntry>)` | chooses one without giving it |
| `giveLater(UUID, List<RewardEntry>)` | keeps them for a player who is not here |
| `claim(Player, Consumer<RewardDelivery>)` | hands over everything they were owed; the callback runs only if there was something |

`give` must be called on the thread that owns the player.
`giveOnPlayerThread` is the one to call from a database callback or any other
async path — it moves the work there itself, which is what makes it correct on
Folia.

### What happens to each reward, in order

1. **Permission**, then **condition**, then the **roll**.

   Deliberately not commons' order. Who *may* receive a reward does not depend
   on chance, and asking in the other order made a rare reward report a lost
   roll when the real answer was a misspelled permission.

2. Amount is rolled, the reward is handed over.
3. Its `deliveryMessage` is sent, if it landed.

Each reward is independent: one that fails costs the player nothing else on the
list.

The list is given in `priority` order, highest first. Rewards of equal priority
keep the order the file wrote them in.

### Outcomes

`RewardOutcome` distinguishes what commons collapsed into one boolean:

| | Means | Counts as |
| --- | --- | --- |
| `GIVEN` | they got it | given |
| `NOT_ROLLED` | the dice said no | skipped |
| `NO_PERMISSION` | they lack the permission | skipped |
| `CONDITION_FAILED` | the condition did not hold | skipped |
| `NO_ROOM` | nowhere to put it, and it could not be dropped or kept | failure |
| `QUEUED` | kept for their return | — |
| `FAILED` | something is broken | failure |

A skip is a configured outcome. A failure is a bug or a typo. Commons reported
both as failure, so `getSuccessRate()` described the dice rather than the
configuration.

`RewardDelivery` carries every `RewardResult`, so a caller can say *which*
reward failed: `given()`, `skipped()`, `failed()`, `isClean()`, `failures()`.

---

## Overflow — nothing is destroyed

ExyliaCommons discarded the leftovers `Inventory.addItem` hands back, so an item
a player had no room for was **destroyed** with no message, no log and no
failure. There is no policy here that reproduces that.

```java
rewards.overflow(OverflowPolicy.DROP);   // the default
```

| Policy | Does |
| --- | --- |
| `DROP` | drops what does not fit at the player's feet — **the default** |
| `QUEUE` | keeps it until there is room; needs a `PendingRewards` store, and drops it if that store refuses |
| `FAIL` | gives up and reports `NO_ROOM` |

What is queued is **what was left over**, not the original: a player who
received four of six is owed two, at a fixed amount and with `chance` forced to
`100`, because the roll that earned them already happened and must not be rolled
again.

Its `permission` and `condition` are kept and re-checked when it is finally
handed over. A reward owed to somebody who has since lost the rank it required
is not owed any more.

---

## Pending rewards

The library does not decide where rewards wait. Capture keeps them in
`capture_pending_rewards`, Events in `event_pending_rewards`, and both tables
are full of rows a player is still owed — a store the library imposed would
either ignore those rows or force a migration.

So a plugin hands its own store over and keeps its own table:

```java
rewards.pending(new PendingRewards() {
    @Override
    public void keep(UUID player, List<RewardEntry> owed) {
        // Not inline: this runs on the player's thread. See below.
        tasks.runAsync(() -> repository.save(new PendingReward(player, event, owed)));
    }

    @Override
    public List<RewardEntry> claim(UUID player) {
        return repository.takeAllFor(player);
    }
});
```

The two are not alike, and getting this wrong costs TPS:

- **`claim` is called off the main thread.** Read the database directly.
- **`keep` is called on whatever thread owed the reward** — for an overflowing
  item that is the player's, which on Spigot and Paper is the main one. Hand the
  write to `Tasks.of(plugin).runAsync(...)` and return; do not write inline.

`claim` must not return the same rewards twice: whatever it hands back is about
to be given. Clear the rows before delivery rather than after — a duplicated
reward is an exploit, a lost one is a support ticket.

---

## Configuration

```java
List<RewardEntry> rewards = Rewards.read(section, "rewards",
        (where, problem) -> getLogger().warning(where + ": " + problem));
```

The keys are the stored field names, so a file and a database column say the
same thing:

```yaml
rewards:
  - type: ITEM
    itemSnapshot: DIAMOND
    minAmount: 4
    maxAmount: 12
    chance: 25.0
    permission: event.vip
  - type: COMMAND
    command: "eco give %player_name% 500"
  - "broadcast %player_name% won"      # a bare string is a command
```

A bare string is read as a command, because hundreds of deployed files already
write reward lists that way and none of them are going to be rewritten.

A row that cannot be read is reported and skipped; the rest still load.

### Conditions

A left side, an operator and a right side, with placeholders resolved on both:

```yaml
condition: "%player_level% >= 10"
```

Operators: `>=`, `<=`, `>`, `<`, `==`, `!=`, plus the literals `true` and
`false`. `==` and `!=` compare text, case-insensitively; the rest compare
numbers.

**An unreadable condition gives the reward.** Commons returned `false` for
anything it could not parse, so a typo deleted the reward and nobody found out
until a player complained. Here it is reported once and the reward is handed
over: the config said who should be *excluded*, and a condition nobody can read
excludes nobody.

This is the deliberate opposite of the menu module, where an unreadable
condition hides a slot. Hiding a button is invisible; handing out a reward that
should have been withheld is loud, and loud is what gets a typo fixed.

---

## Items

```java
String stored = Rewards.snapshot(itemInHand);   // "bytes:…"
ItemStack item = Rewards.item(stored);
```

`snapshot` is byte-identical to commons' `ItemSnapshot.from(ItemStack)`: the
same `serializeAsBytes` under the same standard Base64 under the same `bytes:`
prefix, down to the `"AIR"` it wrote for nothing. A reward written here is
readable by a plugin still on the old module.

Reading understands the same grammar the [item module](items.md) does:
`bytes:`, `basehead-`, `urlhead-`, `playerhead-`, or a plain material.

One limit worth knowing: **giving** a head reward hands over a plain
`PLAYER_HEAD`; the texture is not applied. Storing a head as `bytes:` keeps it.
Drawing one in a menu goes through the item module and is unaffected.

---

## Drawing a reward

Both work without a running server, so a menu can be laid out and tested
without one:

| | Returns |
| --- | --- |
| `displayName()` | the configured `name`, or a description of what it gives |
| `resolvedIcon()` | the explicit `icon`, else the item itself, else a material that reads as its type |

A half-configured reward describes itself as such (`(not set)`, `(no item)`)
rather than as nothing, so a broken row is visible in the menu that has to fix
it.

`resolvedIcon()` hands a `bytes:` snapshot over **whole**, so the row draws as
the item the reward actually gives. Naming it is what is skipped: deriving a
*label* from a snapshot would cost an NBT read per row, so `displayName()` calls
it `item`. Before 1.77.0 the icon was a `CHEST`, and forty custom rewards drew
as forty identical chests.

---

## What a future editor menu will use

ExyliaCommons carried a hardcoded menu for editing rewards. That menu is not
here yet; everything it will need already is, and none of it is internal:

| Needs | Has |
| --- | --- |
| edit a row without disturbing a delivery | `RewardEntry` is immutable; `toBuilder()` keeps the id |
| duplicate a row | `copy()` |
| draw a row | `displayName()`, `resolvedIcon()` |
| a server owner drops an item in | `Rewards.snapshot(ItemStack)` |
| save the list | `RewardCodec.encode(List)` |
| load it back | `RewardCodec.decode(String)` |

---

## The rest of the surface

Everything above is what a plugin normally touches. The rest, for completeness:

| | |
| --- | --- |
| `Rewards.registered()` | how many plugins are using the module |
| `Rewards.release(String)` / `releaseAll()` | called by the library, not by a plugin |
| `PluginRewards.plugin()` | the plugin this view belongs to |
| `RewardCodec.encode(RewardEntry)` | one reward, as a JSON object |
| `RewardCodec.decode(String, BiConsumer)` | as `decode`, reporting what it skipped |
| `RewardType.parse(String)` | a stored type name, or `null` if unknown |
| `RewardDelivery.results()` / `isAnyGiven()` / `EMPTY` | |
| `RewardOutcome.isGiven()` / `isSkipped()` / `isFailure()` | |
| `RewardEntry.preview()` | what it gives, without the configured name |
| `RewardEntry.isGuaranteed()` / `isRanged()` | |
| `RewardEntry.ALWAYS` | `100.0`, the guaranteed chance |

`RewardEntry` exposes an accessor per stored field — `id()`, `name()`, `type()`,
`command()`, `itemSnapshot()`, `message()`, `value()`, `currency()`, `icon()`,
`itemAmount()`, `minAmount()`, `maxAmount()`, `chance()`, `weight()`,
`condition()`, `permission()`, `deliveryMessage()`, `priority()` — a factory per
type plus `of(RewardType)`, and a builder setter for each of them except the
amount, which is set as a whole by `fixedAmount(int)` or `amountBetween(int,
int)` so a range and a fixed value cannot both be half-set.

---

## Statistics

`givenCount()` and `failedCount()` count what actually happened. Rewards that
lost a roll or failed a condition are **not** counted as failures.

A broken reward is reported to the console once per problem rather than once per
player, which is how a real problem stays visible on a busy event.
`forgetProblems()` clears that, so a reload complains afresh.

---

## Threading and lifecycle

- `give` runs on the caller's thread and must be the player's.
  `giveOnPlayerThread` and `claim` arrange it themselves.
- A pending store is read off the main thread and the delivery comes back onto
  the player's.
- A disabled plugin's view is dropped in `ExyliaLib.onPluginDisable`, before the
  database module releases its repositories: a claim reads the plugin's pending
  table.

## Reload

Nothing to invalidate: this module holds no parsed `Component` and nothing
derived from the palette. A reward's text is parsed when it is sent, through
`Text`, which the palette reload already covers. See [reload.md](reload.md).
