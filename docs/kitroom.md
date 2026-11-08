# Kit room

Since 1.511.0.

Categories of items players take copies of to build their own kits. Staff fill
the room in game; players browse it and every click hands a copy into their
inventory; the plugin checks what they built before saving it, so a kit can only
ever hold what the room offered.

```java
KitRoom room = KitRooms.of(plugin);

room.edit(admin, () -> AdminHub.open(admin));        // staff: categories and their items
room.open(player, List.of("gear", "potions"));      // players: take copies; empty list = every category
if (!room.allows(items, List.of("gear", "potions"))) refuse();   // before saving a build
```

| Call | What it does |
| --- | --- |
| `KitRooms.of(plugin)` | the plugin's room, read from its own database (table `exylia_kitroom`, `DataKind.SETUP`) |
| `categories()` / `categories(ids)` / `category(id)` | the tabs in order; `ids` filters, empty means all |
| `open(player, ids)` | the player screen: items in rows 0-3, up to six tabs in 45-47 and 51-53, header at 49 |
| `edit(viewer, back)` | the staff editor: a list editor of categories; editing one asks its name, then opens its items in the item list editor |
| `allows(items, ids)` | whether every non-empty stack is one of those categories' items (`isSimilar`, amount ignored) |
| `loaded()` | completes once the room is read |

- **The room is per plugin.** Each plugin keeps its own categories in its own
  database, so FFA and SandBox can offer different gear.
- **An empty room starts with defaults**: gear in three tiers, consumables,
  splash potions and arrows, all items from 1.21.0. Emptying it on purpose seeds
  it again on the next start.
- **Amounts are not checked.** Players take as many copies as they like; what a
  room limits is *which* items, not how many.
- **Saving a build is the plugin's job**, and so is the place it is built in
  (FFA uses its kit editor room, with the inventory put away in a snapshot).

Code: `kitroom/KitRooms`, `KitRoom`, `KitRoomCategory`; `kitroom/internal/`
(`KitRoomMenu` compiled into the library's menus, `CategoryDescriptor`,
`KitRoomDefaults`).
