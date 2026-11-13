# Screen cookbook

Short recipes for [screens](screens.md). Each one is built from patterns in the API page and the tests. Every tag,
attribute and property is listed in the [markup reference](screens-markup.md); costs are in
[Performance](screens-performance.md).

Setup used by all of them:

```java
ScreenSet screens = Screens.of(plugin).files(MyPlugin.class, "screens");
ScreenMarkup hub = screens.markup("practice_hub");     // screens/practice_hub.html
hub.problems().forEach(plugin.getLogger()::warning);
Screens.of(plugin).open(player, hub.screen(Map.of("tab", "play")));
```

## Tabs that remember

`state` holds the chosen tab, `default` is what shows first, `persist="player"` (1.503.0) keeps the choice across
sessions and restarts. A state handed to `screen(Map)` or a route still wins.

```html
<tabs state="tab" default="play" persist="player">
  <tab value="play">Play</tab>
  <tab value="settings">Settings</tab>
</tabs>
<column id="page-play" if="tab == 'play'">...</column>
<column id="page-settings" if="tab == 'settings'">...</column>
```

```css
tab[selected] { background: {primary}30; }
#page-play, #page-settings { enter: translate(0, 6px) 4t ease-out; }
```

Give each page an `id` so a tab switch is a new page arriving, not the old one changing.

## A list with search

`<search>` stores the typed text in its `state`; the source reads it back and filters. The text is the player's,
so register the source with `rows` and `with(...)` (drawn as written).

```html
<search state="q" prompt="Search a kit" max="16"/>
<grid source="kits" columns="4" rows="2" track="%kit_id%">
  <card action="queue %kit_id%"><b>%kit_name%</b></card>
  <empty>No kit matches %q%</empty>
</grid>
<pager for="kits"/>
```

```java
hub.rows("kits", session -> {
    String q = String.valueOf(session.state("q", "")).toLowerCase(Locale.ROOT);
    return kits.all().stream()
            .filter(kit -> q.isEmpty() || kit.id().contains(q))
            .map(kit -> UiEntry.of(kit)
                    .with("kit_id", kit.id())
                    .withFormatted("kit_name", kit.displayName())
                    .build())
            .toList();
});
```

`track` keeps a row's boxes when the filter reorders the list; the read is memory only (the render runs every
second).

## Dialog and confirm

In markup, a `modal` shown while a state is set, closed by clicking outside or Shift:

```html
<button action="set confirm yes">Reset</button>
<modal if="confirm" dismiss="clear confirm">
  <text>Reset your stats?</text>
  <row>
    <button action="clear confirm; reset_stats">Yes</button>
    <button action="clear confirm">No</button>
  </row>
</modal>
```

Shorter, with the built-in dialog (what follows `then` runs only on yes):

```html
<button action="confirm 'Leave the party?' dangerous yes=Leave no=Stay then party_leave; toast success 'Left the party'">Leave</button>
```

From Java: `session.confirm("Leave the party?", true, "Leave", null, s -> parties.leave(s.viewer()))`. A text prompt
is `session.ask(prompt, hint, max, current)`; in markup `input text <key> '<prompt>' then <actions>`. An
[`Inputs`](input.md) prompt started from a click needs `session.aside(...)`.

## A game canvas

The markup holds the canvas and the score; Java moves the sprites.

```html
<canvas id="table"/>
<b>Stake ${stake}</b>
<button action="flip">Flip</button>
```

```css
#table { width: 220px; height: 120px; }
```

```java
ScreenScene table = session.scene("table");
table.sprite("coin").item(new ItemStack(Material.SUNFLOWER)).size(36, 36).at(92, 76);
table.sprite("coin").move(92, 10, 8, Motion.Ease.OUT);       // the client glides it
table.sprite("shadow").rect(28, 3, "{letters_black}").at(96, 114);
table.remove("coin");
```

Decide the outcome first, then show it: the screen has one viewer, so nothing hidden is sent early.

## A body wearing a kit

```html
<img skin="%me_name%" class="me-body" turn wearing="${tab == 'play' ? wear : ''}"/>
...
<card action="queue %kit_id%" action-hover="set wear %kit_wearing%">...</card>
```

```java
row.put("kit_wearing", Screens.outfit(helmet, chestplate, leggings, boots, firstHotbarItem));
```

Hovering a card sets `wear`; the body turns in that kit. Keep one turning body per screen (it is 96 displays).

## Routing a chest menu to a screen

The screen names the menu ids it stands for, with the state to set (`%name%` filled from the menu's context).

```html
<screen routes="player/queue_category:tab=play
                player/queue_kit:tab=play,category=%category_id%
                player/settings:tab=settings">
```

In `SCREEN` menu style the plugin's `menus.open(player, "player/queue_kit", context)` opens this screen; the
screen reads `%category_id%`, `source="main"` and `ns:id` actions from the menu itself, so no Java is needed. Needs
the screen to come from a `ScreenSet`. See [Routing a menu id](screens.md#routing-a-menu-id-to-a-screen).

## A title that shimmers (1.610.0)

Opt-in classes from the base theme, no keyframes to write:

```html
<p class="lib-shimmer">Season 4</p>
<button class="lib-pulse" action="play">Play now</button>
<row class="lib-wave"><card>...</card><card>...</card><card>...</card></row>
```

`text-gradient: var(--success) var(--success-light) 40t` in a sheet gives any other colours or speed. Keep it to the
one thing the eye should land on; past six sliding texts a screen the rest hold still.

## Dynamic classes

Classes filled per render (1.503.0) take part in CSS matching:

```html
<card class="card ${owned ? 'owned' : 'locked'}" disabled="${not owned}">...</card>
```

```css
.card.owned  { outline: 1px {success}; }
.card.locked { background: {muted}18; }
```

The same works with a row field: `class="tile %tone%"`.
