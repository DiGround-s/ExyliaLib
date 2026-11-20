# Block palettes

Each player sees the same world with some blocks swapped for others: oak
planks drawn as spruce planks, hay as dried kelp. One map, built once, shown
to each player in the materials of their own era. Nothing changes on the
server: physics, drops, protections and other plugins all see the real
blocks.

Since 1.636.0. Package `net.exylia.lib.packet.palette`.

```java
Palette spruce = Palette.builder()
        .swap(Material.OAK_PLANKS, Material.SPRUCE_PLANKS)
        .swap(Material.OAK_STAIRS, Material.SPRUCE_STAIRS)
        .swap(Material.OAK_SLAB, Material.SPRUCE_SLAB)
        .worlds(Set.of("clicker"))     // empty (default) = every world
        .build();                      // once, after the worlds load
spruce.rejected();                     // swaps left out, with the reason

BlockPalettes palettes = BlockPalettes.of(this);
if (palettes.set(player, spruce)) {    // false: PacketEvents is missing
    palettes.refresh(player);          // repaint what they already hold
}
palettes.clear(player);                // back to the real world (then refresh)
```

## API

```java
// Palette (immutable, share one per era)
static Palette of(Map<Material, Material> swaps);
static Builder builder();
Builder swap(Material from, Material to);   // every state, shared properties kept
Builder swap(BlockData from, Material to);  // one exact state
Builder swaps(Map<Material, Material> swaps);
Builder worlds(Collection<String> names);
Palette build();                            // throws IllegalStateException before any world loads
Set<String> worlds();
boolean appliesIn(World world);
List<String> rejected();
int swappedStates();

// BlockPalettes
static BlockPalettes of(Plugin plugin);
static boolean isSupported();
boolean set(Player player, Palette palette);
void clear(Player player);
@Nullable Palette get(Player player);
void refresh(Player player);
```

## Contracts

- **One palette per player**, whoever set it. `set` replaces, `clear`
  removes. A palette is forgotten when the player quits, and a disabled
  plugin's palettes come off (their players' chunks are sent again).
- **`set` does not resend anything.** Chunks sent from then on are drawn in the
  palette. Set it on join, before chunks go out, or call `refresh`.
- **`refresh`** sends again every chunk the player holds, nearest first,
  32 per tick, each on its own region thread (Folia safe). It uses
  `World.refreshChunk`, so **everyone else holding those chunks gets them
  again too**. Call it when the palette changes, never on a timer.
- **Properties carry over.** A material swap covers every state of the
  source, and the shown block keeps each property both blocks share: stairs
  keep facing, half and shape; slabs their type; logs their axis.
- **What is repainted:** chunk data (single-value, list and global section
  palettes), block changes, multi block changes, falling blocks and the
  block-break effect. Fake blocks and anti-ESP masks from
  [packets](packets.md) are drawn in the viewer's palette too.
- **The world filter** is by world name; in other worlds the player sees the
  real blocks. Changing world needs nothing: the new world's chunks are sent
  through the filter.

## Which swaps are accepted

A swap is accepted only if the client predicts exactly what the server
simulates, for **every** state of the block. Otherwise the whole swap is left
out of the palette, listed in `rejected()`, and logged once (debug warning)
the first time the palette is `set`:

- both sides are blocks, neither is air (the client counts non-air blocks);
- no fluids or waterlogged plants (water, lava, bubble columns, kelp,
  seagrass), and no block entities (chests, signs, banners, spawners...);
- the same collision boxes in every state;
- the same movement: friction, climbing, bed bouncing, and the special
  blocks (slime, honey, soul sand, cobweb, powder snow, berry bushes,
  scaffolding) only swap with themselves;
- a waterlogged state never becomes a block that cannot hold water.

## Limits

- **Items are not swapped.** A player holding oak planks places what they
  see as spruce. Swap the items yourself if the era needs it.
- **Light comes from the server.** Swapping a see-through block for a solid
  one of the same shape (glass and stone) keeps the light the real block
  casts.
- **Cost:** with no palette set anywhere, one boolean check per packet. A
  palette viewer's chunks are decoded and encoded again for them (a list
  section costs its palette size, a global section 4096 lookups); every
  lookup is an array read.
- **Needs PacketEvents.** Without it `isSupported()` is false, `set` returns
  false and sets nothing, and `build()` returns an empty palette whose
  `rejected()` says why.
- **Property carry-over and shape checks need a running server**; the unit
  tests cover the state table, the section rewrite and the box comparison.
