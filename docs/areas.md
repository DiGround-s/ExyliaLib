# Areas

Per-player looks of whole areas, the walls of locked ones, and private areas
where every player plays their own copy. Built for games where many players
share one map but each sees their own progress on it: a farm plot, a tycoon
base on a shared hull, sections that open one by one. Everything is packets
to one viewer; the server keeps the real, shared world, so collision and the
anticheat always agree with what the player walks on.

Since 1.676.0. Package `net.exylia.lib.packet.area`.

```java
AreaLooks looks = AreaLooks.of(this);          // once, on enable
PrivateAreas areas = PrivateAreas.of(this);    // once: it is this plugin's visibility rule

// A locked section, drawn as wild ground with a fence and a portcullis.
AreaWalls.Entrance gate = AreaWalls.facing(minX, minZ, maxX, maxZ, spawnX, spawnZ);
Map<Location, BlockData> look = new HashMap<>(wildGround);
look.putAll(AreaWalls.fence(world, minX, minZ, maxX, maxZ, floorY + 1, Material.OAK_FENCE));
look.putAll(gate.bars(world, floorY + 1));
looks.show(player, "mine", look);              // replaces what "mine" looked like

// Bought: it comes down ring by ring from the gate, then the section grows.
looks.reveal(player, "mine", gate.x(), gate.z(), 5, 4, () -> grow(player));

// A base drawn in the materials of its era.
looks.paint(player, "base", world, minX, minY, minZ, maxX, maxY, maxZ, era);

// Players in the same private area do not see each other; others are drawn faint.
areas.together((viewer, target) -> visiting(viewer, target));
areas.place(player, insidePlot ? "plot" : null);   // on move, join and teleport
```

## API

```java
// AreaLooks: fake blocks kept for one viewer, grouped by area name
static AreaLooks of(Plugin plugin);
void show(Player viewer, String area, Map<Location, BlockData> blocks); // replaces the area's look
boolean paint(Player viewer, String area, World world,
              int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Palette palette);
void clear(Player viewer, String area);
void clear(Player viewer);
Set<String> areas(Player viewer);
void reveal(Player viewer, String area, int fromX, int fromZ, int steps, long ticksApart, Runnable then);
static List<List<Location>> rings(Collection<Location> blocks, int fromX, int fromZ, int steps);

// AreaWalls: shapes only, nothing touches the world
record Entrance(int edge, int mid, boolean onX, int outward) {
    int x(); int z();
    Location outside(World world, double floorY, double up);
    Map<Location, BlockData> bars(World world, int baseY);   // 3 x 3 iron bars
}
static Entrance facing(int minX, int minZ, int maxX, int maxZ, double x, double z);
static Entrance alongRoad(int minX, int minZ, int maxX, int maxZ, double x, double z, double yaw); // or null
static Map<Location, BlockData> border(World world, int minX, int minZ, int maxX, int maxZ,
                                       int baseY, int height, BlockData block);
static Map<Location, BlockData> fence(World world, int minX, int minZ, int maxX, int maxZ, int y, Material fence);

// PrivateAreas: the plugin's visibility rule
static PrivateAreas of(Plugin plugin);
void together(BiPredicate<UUID, UUID> rule);
boolean place(Player player, String area);   // null: in none; true when it changed
String area(UUID player);
void refresh(Player player);

// Palette (1.676.0)
BlockData shown(BlockData real);              // what a palette viewer is shown in its place
```

## Contracts

- **One look per area per viewer.** `show` replaces it: positions the new look
  no longer covers go back to the real blocks, the rest are redrawn. Looks are
  kept with `FakeBlocks.keep`, so a chunk resend or a block update nearby shows
  the look again, not the truth. Drawn in the viewer's [palette](palettes.md).
- **Forgotten on quit and on a world change** (the client reloads its chunks):
  draw them again where the player arrived.
- **`paint`** reads the box chunk by chunk, each on the thread that owns that
  chunk, and shows the result once every chunk is read. It returns `false` and
  reads nothing when the palette swaps nothing in that world. It reads every
  block of the box: keep it to one section, not a whole map.
- **`reveal`** clears the area's look in `steps` rings by flat distance from a
  point, `ticksApart` ticks apart, on the viewer's thread, then runs `then`
  there (at once when the area shows nothing). A ring the area no longer holds
  (redrawn meanwhile) clears nothing.
- **`PrivateAreas` is the plugin's visibility rule** (`Visibility.rule(rule,
  true)` and `ghosts`): a plugin using it has no rule of its own. Same area:
  hidden in the world, kept on the tab list, no collision. Different places,
  or one in and one out: drawn faint. `together` lets pairs in the same area
  see each other; `refresh` the player when its answer changes.

## Limits

- **Nothing changes on the server.** A fake wall stops nobody, and the server
  collides with the real block: only swap a block for one of the same shape,
  and never draw something walkable where the real world has air. Fences and
  bars are for looking closed; keep players out with the plugin's own gate.
- **Looks cost what fake blocks cost:** one multi block change per chunk
  section when shown, and the kept blocks are rewritten into every chunk
  resend for that viewer.
