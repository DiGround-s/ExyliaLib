# Hotbars

A lobby hotbar: named sets of buttons drawn in a player's inventory, each with
its slot, look, clicks, permission and cooldown, frozen in place. Since 1.559.0.

Entry point: `net.exylia.lib.hotbar.Hotbars`.

```java
hotbars = Hotbars.of(this, actions)                  // actions: the namespace the lines compile in
        .register(HotbarSet.of("lobby", List.of(
                HotbarItem.of("selector", 0, selectorItem)
                        .withActions(List.of("right: lobby:selector"))
                        .withCooldown(Duration.ofMillis(500)),
                HotbarItem.of("cosmetics", 4, cosmeticsItem)
                        .withPermission("lobby.cosmetics", lockedCosmeticsItem)   // null: hidden instead
                        .withCommands(List.of("right: player: cosmetics")))))
        .onCooldown((player, left) -> messages.send(player, "hotbar-wait", left));

hotbars.show(player, "lobby");    // entering the lobby
hotbars.hide(player);             // leaving it
hotbars.refresh(player);          // a permission or a button's state changed
hotbars.showing(player);          // Optional<String>: the set they wear
```

`HotbarItem(id, slot, item, permission, locked, cooldown, actions, commands)`: the
slot is an inventory index (`0-8` the hotbar, as `setItem` numbers it), `item` a
lib [Item](items.md), `actions` and `commands` click-prefixed lines written as a
menu or overlay item writes them (`right:`, `left:`, `shift_right:`, `drop:`,
`swap:`, no prefix for every click).

`HotbarSet(name, items, lock, hideRest, pickup, refresh)`: `HotbarSet.of(name, items)`
is the lobby default — every slot frozen (`OverlayLock.FULL`), the slots it does
not draw blank, no pickups, never redrawn. `withInventory(lock, hideRest, pickup)`
and `withRefresh(UiRefresh)` (for names or lore with placeholders) change it.

## What it is built on

[Overlays](overlays.md): nothing is written into the real inventory, so a crash
or a disconnect can never leave a working button behind as a real item, the
player's own items are where they were when the hotbar comes off, and moving,
dropping, swapping and picking the buttons out are refused at packet level. It
needs PacketEvents: without it `Hotbars.isAvailable()` is `false` and `show`
draws nothing. A chest or menu window makes it step aside until it closes, as
every overlay does.

**A button is drawn, not held.** Hitting something with one hits like a bare
hand, and using one never uses an item. Combat gear (a sword, a bow, a rod, a
pearl) must be real items the plugin gives, never a hotbar button.

## Presses

- Each button answers only the clicks its lines name. A press runs its commands
  and then its actions, with the overlay's keys (`OverlayKeys.CLICK`, `SLOT`,
  `TARGET`, `BLOCK`) in the action context.
- **Cooldown** per player and button, through [Cooldowns](cooldowns.md): a press
  inside it plays the refusal sound and calls `onCooldown(player, remaining)`.
- **Permission**: a button with one is drawn only for holders. The others see
  its `locked` look (a card saying how to unlock it; pressing it only plays the
  refusal sound) or an empty slot when there is none.
- Every action line is compiled at `register`, so an action that is not
  registered fails there with `IllegalArgumentException`.
- The hotbar registers one action of its own in the namespace handed to `of`:
  `exylialib_hotbar_press`.

## Refresh

Permissions are read again every two seconds for everybody wearing a set and
redrawn only when one changed; `refresh(player)` does it at once. Registering a
set again under the same name redraws everybody wearing it. If another plugin
puts its own overlay on a player, this plugin's set is forgotten for them rather
than drawn back over it.

## Lifecycle and threads

Every method is safe from any thread; drawing and presses run on the player's
thread (Folia-safe). When the plugin disables, its overlays come off and its
hotbars are dropped. One instance per plugin: `of` answers the same one until
then.

## Where the code is

`src/main/java/net/exylia/lib/hotbar/` — `Hotbars`, `HotbarSet`, `HotbarItem`.
Tests: `src/test/java/net/exylia/lib/hotbar/HotbarsTest.java`.
