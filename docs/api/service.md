# HiddenOreService: managed blocks, veins and placement provenance

`art.arcane.hiddenore.api.HiddenOreService` is the read side of HiddenOre. It answers four kinds of question:
which materials HiddenOre manages, where a block came from, where the hidden veins are in a seeded world, and
whether the calling thread is allowed to ask about a given position at all. It never mutates world state, never
grants a reward and never schedules anything.

```java
public interface HiddenOreService {
    int MAX_NEARBY_RADIUS = 128;
    int MAX_NEARBY_RESULTS = 4096;

    boolean isSeeded();

    boolean isManagedBase(Material material);

    BlockOrigin originOf(Block block);

    ChunkProvenance provenanceOf(Chunk chunk);

    boolean isVeinConsumed(Block block);

    HiddenVein veinAt(Block block);

    List<HiddenVein> veinSiblings(Block block);

    List<HiddenVein> veinsNear(Location center, int radius);

    boolean ownsRegion(World world, int chunkX, int chunkZ);
}
```

Acquire it from the ServicesManager as shown in [README.md](README.md). There is one registration, at
`ServicePriority.Normal`, created during HiddenOre's enable and removed during its drain.

---

## Region ownership is the central constraint

HiddenOre stores per-block records in chunk persistent data and reads live block materials. Both are
region-owned state. Five of the nine methods therefore refuse to answer from a thread that does not own the
region containing the position you asked about:

```java
throw new IllegalStateException("HiddenOre API access requires the owning world region thread");
```

| Method                        | Called from a thread that does not own the position                                    |
|-------------------------------|-----------------------------------------------------------------------------------------|
| `originOf(Block)`             | throws `IllegalStateException`                                                          |
| `provenanceOf(Chunk)`         | throws `IllegalStateException`                                                          |
| `isVeinConsumed(Block)`       | throws `IllegalStateException`                                                          |
| `veinAt(Block)`               | throws `IllegalStateException` — except in `pure_random` mode, where it returns `null` before the ownership check runs |
| `veinSiblings(Block)`         | throws `IllegalStateException` — except in `pure_random` mode, where it returns an empty list first |
| `veinsNear(Location, int)`    | never throws for ownership. Chunks your region does not own are **skipped silently**    |
| `isSeeded()`                  | safe. Reads one volatile reference to an immutable record                               |
| `isManagedBase(Material)`     | safe. Reads that same record and does one map lookup                                    |
| `ownsRegion(World, int, int)` | safe. This is the probe                                                                 |

**Branch on the probe. Do not catch the exception.** The exception exists to stop a bug from corrupting
your reads, not to be used as control flow — and in `pure_random` mode `veinAt`, `veinSiblings` and `veinsNear`
answer without ever reaching it, so a `null` return is not evidence that you were on the right thread.

```java
public static BlockOrigin originOrUntracked(HiddenOreService hiddenOre, Block block) {
    World world = block.getWorld();

    if (!hiddenOre.ownsRegion(world, block.getX() >> 4, block.getZ() >> 4)) {
        return BlockOrigin.UNTRACKED;
    }

    return hiddenOre.originOf(block);
}
```

`ownsRegion` takes chunk coordinates, not block coordinates. Shift by four: `block.getX() >> 4`,
`block.getZ() >> 4`. It answers `false` — never throws — for a `null` world.

### Getting onto the right thread

```java
public static void offRegion(Plugin plugin, HiddenOreService hiddenOre, Block block) {
    World world = block.getWorld();
    int chunkX = block.getX() >> 4;
    int chunkZ = block.getZ() >> 4;

    if (hiddenOre.ownsRegion(world, chunkX, chunkZ)) {
        handle(hiddenOre.originOf(block));
        return;
    }

    Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ,
        () -> handle(hiddenOre.originOf(block)));
}
```

`Bukkit.getRegionScheduler()` is Paper API and exists on both Paper and Folia; on Paper it runs the task on the
main thread, on Folia on the owning region thread. On Spigot, where that API does not exist, use
`Bukkit.getScheduler().runTask(plugin, runnable)` — there `ownsRegion` is equivalent to
`Bukkit.isPrimaryThread()`, because the main thread owns every region.

## Threading

- **Every call is synchronous.** It computes its answer before it returns. Nothing on this interface takes a
  callback, returns a future, or completes later. There is no thread on which results arrive; results are the
  return value.
- `isSeeded()`, `isManagedBase(Material)` and `ownsRegion(World, int, int)` may be called from **any** thread.
  That claim is narrow and earned: the first two read a single volatile reference to an immutable record and
  do not touch a world, a chunk or a block, and the third exists to be asked from the wrong thread.
- Everything else must be called from the owning region thread. Inside a `HiddenOreBreakEvent` or
  `HiddenOreDropsEvent` handler you are already on it for that event's block, and `ownsRegion` for that block
  is guaranteed `true` — but not for a block a hundred blocks away, which may belong to another region.
- **Do not block on these calls from a region thread.** They perform chunk persistent-data reads and block
  reads. `veinsNear` at full radius is the expensive one; budget it like a chunk scan, not like a lookup.
- HiddenOre never calls you back. Nothing here can be re-entered.

---

## Provenance: what HiddenOre actually knows

`originOf(Block)` returns one of three values.

| Value                        | Meaning                                                                                          |
|------------------------------|--------------------------------------------------------------------------------------------------|
| `BlockOrigin.PLAYER_PLACED`  | HiddenOre holds a placement record for this exact position, and the block there is still a managed material |
| `BlockOrigin.PRESUMED_GENERATED` | The block is a managed material and HiddenOre holds **no** placement record for that position |
| `BlockOrigin.UNTRACKED`      | The block's current material is not in `blocks:`, so HiddenOre has no opinion at all               |

### `PRESUMED_GENERATED` is not "worldgen made this"

The constant is named for the presumption, not for a conclusion. It means exactly one thing: *this material is
tracked and there is no placement record*. Every one of these blocks reports `PRESUMED_GENERATED`:

- a block placed by a player **before HiddenOre was installed**;
- a block placed by a player **before that material was added to `blocks:`**;
- a block placed while HiddenOre was disabled, draining, or had failed to enable;
- a block placed by another plugin, a schematic paste, a world edit, or a mob;
- a block moved into position by a mechanism HiddenOre does not observe;
- and, yes, a block produced by world generation.

**There is no backfill.** HiddenOre does not and cannot reconstruct history for positions it did not observe
being placed. If you build an anti-grief rule, a "this was mined from natural stone" claim, or a rollback
decision on `PRESUMED_GENERATED`, it will be wrong for every block that predates your install. The value is
sound in the other direction: `PLAYER_PLACED` is a positive record and means a player placed that block while
HiddenOre was watching.

### What creates, moves and clears a record

| Event                                                        | Effect on the record                                                     |
|--------------------------------------------------------------|--------------------------------------------------------------------------|
| A player places a block whose material is managed *at that moment* | A record is written for that position                              |
| A piston pushes or pulls a recorded block                    | The record moves with it, including sticky retraction                    |
| A player breaks the block and it drops something              | The record is cleared                                                    |
| A player breaks the block and it drops **nothing** — bare hands on stone, the wrong tool | No `BlockDropItemEvent` fires, so the record is **not** cleared |
| A material is removed from `blocks:` and later added back     | Records survive. They are position records, not material records         |
| `veins.allow_placed_blocks` is turned on, then off            | Records survive. The setting decides whether they *block rewards*, not whether they are kept |
| The block is destroyed by an explosion, fluid, or anything else that does not fire `BlockBreakEvent` | The record is **not** cleared and remains at that position |

The last row, and the drop-nothing row above it, are the honest edge: a record can outlive the block it
described. Clearing is driven by `BlockDropItemEvent`, so a destruction that never reaches one leaves the
record where it was. `originOf` covers most of it by answering `UNTRACKED` when the position no longer holds a
managed material, but if a managed material returns to that exact position by a path HiddenOre does not
observe, the stale record makes it read `PLAYER_PLACED`.

---

## Asking many times: the chunk snapshot

`originOf` performs **one chunk persistent-data read per call**: decode the packed position array, binary
search it, discard it. In a loop over a region that is one decode per block.

`provenanceOf(Chunk)` does that decode **once** and hands you the result:

```java
public interface ChunkProvenance {
    Chunk chunk();

    boolean contains(int worldX, int worldY, int worldZ);

    int size();

    boolean isEmpty();
}
```

`contains` is a binary search over a private copy of the array. No I/O, no allocation, no chunk access — the
snapshot is safe to query as fast as you like, and it does not change when the world does. It is a snapshot:
placements made after you took it are not in it.

`contains` is **total**. Coordinates outside the chunk, outside world height, or at `Integer.MIN_VALUE` answer
`false` instead of throwing, so you can walk a three-by-three chunk area against one snapshot without guarding
the edges. Compare against `chunk()` yourself if you need the strict reading.

`size()` is the number of recorded positions. `isEmpty()` is the cheap early-out: most chunks on most servers
have no records at all, and skipping those is the single biggest win in a scan.

### The one behavioural difference from `originOf`

`originOf` checks the block's **current material** before it answers. `ChunkProvenance.contains` does not — it
is the raw record. A position can be `contains == true` while `originOf` on the same position returns
`UNTRACKED`, because whatever is there now is not a managed material.

To reproduce `originOf` semantics from a snapshot, do the material check yourself:

```java
public static BlockOrigin fromSnapshot(HiddenOreService hiddenOre, ChunkProvenance provenance, Block block) {
    if (!hiddenOre.isManagedBase(block.getType())) {
        return BlockOrigin.UNTRACKED;
    }

    return provenance.contains(block.getX(), block.getY(), block.getZ())
        ? BlockOrigin.PLAYER_PLACED
        : BlockOrigin.PRESUMED_GENERATED;
}
```

`ChunkProvenance` is a membership test, not an enumeration. It will tell you whether a position is recorded and
how many records exist; it will not list them. If you need the positions, you have to walk the volume you care
about — a full-height chunk walk is 98,304 block reads, which is why the worked example below takes a Y range.

---

## Veins

A **hidden vein** is a position that pays out when mined. In `seeded` mode those positions are derived from the
world seed, the chunk coordinates and the order of the `drops:` list, so they exist before anyone touches them
and can be found, outlined and counted. In `pure_random` mode nothing is pre-placed and every vein query
answers empty — always check `isSeeded()` first, or your feature will silently do nothing on half of the
servers that install it.

```java
public record HiddenVein(int x, int y, int z, int veinId, Material item, Material oreDisplay) {
    public boolean seeded();

    public static Material oreDisplayFor(Material item, int y);
}
```

| Component     | Meaning                                                                                             |
|---------------|------------------------------------------------------------------------------------------------------|
| `x`, `y`, `z` | Absolute world coordinates. There is no world reference on the record — you supplied it              |
| `veinId`      | Identifies the vein **within its chunk only**. Ids restart at 0 in every chunk. `-1` means "not a seeded vein" |
| `item`        | The material this position pays out                                                                  |
| `oreDisplay`  | A vanilla ore block suitable for showing the player, or **`null`**                                    |
| `seeded()`    | `veinId >= 0`                                                                                        |

`oreDisplay` is `null` for every drop material outside the vanilla ore set — a server configured to drop, say,
`NETHERITE_INGOT` or an economy token has no ore block to show. Null-check it before you send a block change.
`oreDisplayFor(Material, int)` is the same mapping as a static function, and applies the deepslate variant for
`y < 0`.

Because `veinId` is chunk-scoped, two veins in different chunks routinely share an id. Key your own state on
the coordinates, never on the id alone.

### `veinAt(Block)`

Returns the vein at exactly this block, or `null`. It answers `null` — without throwing — when HiddenOre is in
`pure_random` mode, when the block's material is not managed, when no vein occupies that position, when that
position has already been consumed, and when the block is player-placed while `veins.allow_placed_blocks` is
`false`. In other words, a non-null answer means "this position is still payable".

### `veinSiblings(Block)`

Returns the **remaining** positions of the same vein **in the same chunk**, excluding the block you asked
about. Veins are grown by a random walk that is clamped to the chunk, so a vein never crosses a chunk border
and this is the complete rest of it. Positions already mined, positions that are player-placed while
`allow_placed_blocks` is `false`, and positions whose block is no longer a managed material are all filtered
out. The returned list is immutable.

Note the asymmetry with `veinAt`: `veinSiblings` does not check whether the block *you passed* was consumed or
placed. Ask about a spent vein block and you still get its unspent siblings back.

### `veinsNear(Location, int)`

Scans for unconsumed veins around a point.

- `radius` is in blocks and is measured **spherically** against the vein position.
- A radius above `MAX_NEARBY_RADIUS` (128) throws `IllegalArgumentException`. A radius of zero or less returns
  an empty list. In `pure_random` mode both checks are skipped and you get an empty list regardless, so do not
  rely on the exception as validation — clamp with `Math.min(radius, HiddenOreService.MAX_NEARBY_RADIUS)`.
- At most `MAX_NEARBY_RESULTS` (4096) veins are returned. When the cap is hit, the scan stops where it is.
  **The list is in chunk-iteration order, not distance order** — a truncated result is not "the nearest 4096".
  If you need the nearest, sort what you get, and use a radius small enough not to truncate.
- **Chunks that are not loaded are skipped.** Nothing is loaded on your behalf.
- **Chunks your region does not own are skipped.** On Folia a 128-block query near a region border returns only
  the part of the sphere your thread owns, with no error and no indication that anything was left out. On Paper
  and Spigot the main thread owns everything, so this does not arise.

A 128-block radius spans up to 17 by 17 chunks — 289 chunks of persistent-data reads, vein computation and
block reads. Treat it as a scan, not a query.

Vein layouts are computed on demand and cached per world, up to 4096 chunks, evicted least-recently-used, and
recomputed after a configuration reload. Reordering, inserting or deleting entries in `drops:` reshuffles every
undiscovered vein in the world.

### `isVeinConsumed(Block)`

`true` when this exact position has already been spent. Consumption is recorded in chunk persistent data, so it
survives restarts, and it is per position, not per vein: the other blocks of a vein remain payable.

Unlike the three vein-lookup methods, this one does **not** short-circuit in `pure_random` mode. It reads the
consumption records unconditionally, and it applies the region-ownership check first, so it throws off-region in
either mode. Only the seeded path ever *writes* a consumption record — but a world that ran in `seeded` mode
before being switched to `pure_random` keeps every record it wrote, and this method keeps answering `true` for
those positions. Do not read `false` as "the server is in `pure_random` mode"; ask `isSeeded()` for that.

**Consumed does not mean paid.** A seeded position is consumed the moment it is mined on the seeded reward
path, before the pickaxe tier is tested against the rule. Mining a diamond vein position with a stone pickaxe
spends the position and yields nothing, and `veinAt` on it returns `null` from then on. The two ways a position
is mined *without* being consumed are a cancelled `HiddenOreBreakEvent` and a player-placed block under a
restrictive `allow_placed_blocks`; both skip the seeded path entirely.

---

## Worked example: auditing a chunk

A plugin that reports how much of the managed stone in a chunk was placed by players. It shows the whole
pattern: probe, hop to the owning region, take one snapshot, then query it as many times as you like.

```java
package com.example.quarryguard;

import art.arcane.hiddenore.api.ChunkProvenance;
import art.arcane.hiddenore.api.HiddenOreService;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

public final class ChunkAudit {
    private final Plugin plugin;
    private final HiddenOreService hiddenOre;

    public ChunkAudit(Plugin plugin, HiddenOreService hiddenOre) {
        this.plugin = plugin;
        this.hiddenOre = hiddenOre;
    }

    public void audit(CommandSender sender, World world, int chunkX, int chunkZ, int minY, int maxY) {
        if (hiddenOre.ownsRegion(world, chunkX, chunkZ)) {
            report(sender, count(world, chunkX, chunkZ, minY, maxY));
            return;
        }

        plugin.getServer().getRegionScheduler().execute(plugin, world, chunkX, chunkZ,
            () -> report(sender, count(world, chunkX, chunkZ, minY, maxY)));
    }

    private Counts count(World world, int chunkX, int chunkZ, int minY, int maxY) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return new Counts(0, 0);
        }

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        ChunkProvenance provenance = hiddenOre.provenanceOf(chunk);

        if (provenance.isEmpty()) {
            return new Counts(0, 0);
        }

        int placed = 0;
        int presumed = 0;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int lowY = Math.max(minY, world.getMinHeight());
        int highY = Math.min(maxY, world.getMaxHeight() - 1);

        for (int x = baseX; x < baseX + 16; x++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                for (int y = lowY; y <= highY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();

                    if (!hiddenOre.isManagedBase(type)) {
                        continue;
                    }

                    if (provenance.contains(x, y, z)) {
                        placed++;
                    } else {
                        presumed++;
                    }
                }
            }
        }

        return new Counts(placed, presumed);
    }

    private void report(CommandSender sender, Counts counts) {
        sender.sendMessage("player-placed: " + counts.placed()
            + ", tracked with no placement record: " + counts.presumed());
    }

    private record Counts(int placed, int presumed) {
    }
}
```

The `isChunkLoaded` guard is deliberate: `world.getChunkAt` loads the chunk synchronously if it is not
resident, and a scan that quietly loads chunks is a scan that quietly costs disk I/O. Drop the guard only if
loading is what you want.

## Worked example: outlining a vein

```java
package com.example.quarryguard;

import art.arcane.hiddenore.api.HiddenOreService;
import art.arcane.hiddenore.api.HiddenVein;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;

public final class VeinProbe {
    private final Plugin plugin;
    private final HiddenOreService hiddenOre;

    public VeinProbe(Plugin plugin, HiddenOreService hiddenOre) {
        this.plugin = plugin;
        this.hiddenOre = hiddenOre;
    }

    public void outline(Player player, Block block) {
        World world = block.getWorld();
        int chunkX = block.getX() >> 4;
        int chunkZ = block.getZ() >> 4;

        if (!hiddenOre.ownsRegion(world, chunkX, chunkZ)) {
            plugin.getServer().getRegionScheduler().execute(plugin, world, chunkX, chunkZ,
                () -> outline(player, block));
            return;
        }

        if (!hiddenOre.isSeeded()) {
            return;
        }

        HiddenVein vein = hiddenOre.veinAt(block);

        if (vein == null) {
            return;
        }

        show(player, world, vein);

        for (HiddenVein sibling : hiddenOre.veinSiblings(block)) {
            show(player, world, sibling);
        }
    }

    public int countNearby(Location center, int radius) {
        World world = center.getWorld();

        if (world == null || !hiddenOre.ownsRegion(world, center.getBlockX() >> 4, center.getBlockZ() >> 4)) {
            return 0;
        }

        int clamped = Math.min(radius, HiddenOreService.MAX_NEARBY_RADIUS);
        List<HiddenVein> veins = hiddenOre.veinsNear(center, clamped);

        return veins.size();
    }

    private void show(Player player, World world, HiddenVein vein) {
        Material display = vein.oreDisplay();

        if (display == null) {
            display = Material.STONE;
        }

        player.sendBlockChange(new Location(world, vein.x(), vein.y(), vein.z()), display.createBlockData());
    }
}
```

Owning the region containing `center` does not mean you own the whole sphere. `countNearby` above returns a
count for the part of the radius this thread may read, which is the whole sphere on Paper and the owned part on
Folia.

---

## The minimum

If all you want is "did a player put this here", you need two calls and no scheduling:

```java
if (hiddenOre.ownsRegion(block.getWorld(), block.getX() >> 4, block.getZ() >> 4)
    && hiddenOre.originOf(block) == BlockOrigin.PLAYER_PLACED) {
    player.sendMessage("That block was placed by a player.");
}
```

Called from an ordinary Bukkit event handler for that block — `BlockBreakEvent`, `PlayerInteractEvent` — the
probe is already `true`, and it costs a single call to keep the code correct on Folia.

---

## Failure policy

HiddenOre assumes callers get it wrong. It fails loudly where a wrong answer would be worse than an exception,
and quietly where an exception would be worse than no answer.

| What you do                                            | What happens                                                                  |
|--------------------------------------------------------|--------------------------------------------------------------------------------|
| Call a position method off-region                      | `IllegalStateException`. Nothing is read, nothing is written                    |
| Call `veinsNear` with `radius > 128`                   | `IllegalArgumentException` naming the limit — in `seeded` mode only            |
| Call `veinsNear` with `radius <= 0`                    | Empty list                                                                     |
| Call `veinAt`, `veinSiblings` or `veinsNear` in `pure_random` mode | `null` or an empty list, from any thread, without an ownership check |
| Call `isVeinConsumed` in `pure_random` mode            | The ownership check still applies and still throws off-region. It is not one of the three methods that short-circuit |
| Pass `null` as a `Block`, `Chunk` or `Location`        | `NullPointerException`. Arguments are not null-checked for you                 |
| Pass `null` to `isManagedBase`                         | `NullPointerException`                                                         |
| Pass a `Location` whose world is `null` to `veinsNear` | Empty list                                                                     |
| Pass a `null` world to `ownsRegion`                    | `false`                                                                        |
| Ask about an unloaded chunk                            | `provenanceOf`/`originOf` load it through the `Chunk`/`Block` you handed in; `veinsNear` skips it |
| Keep the service after HiddenOre drains                | Every method still answers and none throws for that reason: `originOf` returns `UNTRACKED`, `isVeinConsumed` returns `false`, `veinAt` returns `null`, `veinSiblings` and `veinsNear` return empty lists, and `provenanceOf` returns an empty snapshot — after its ownership check, which still applies |
| Retain a `ChunkProvenance` for hours                   | Legal. It is a detached copy and holds no chunk data, but it grows staler every second |
| Mutate what you get back                               | Impossible. `ChunkProvenance` exposes no array, `HiddenVein` is a record, and both list-returning methods return immutable lists |

HiddenOre never quarantines a caller, never disables itself in response to misuse, and never rate-limits these
methods. There is no fault counter on the read API — the cost of misuse is entirely yours.

---

## Configuration keys that change the answers

`plugins/HiddenOre/config.yml`:

| Key                                 | Default in the shipped file | Meaning for this API                                                          |
|-------------------------------------|-----------------------------|--------------------------------------------------------------------------------|
| `blocks`                            | `stone`, `deepslate`        | The managed materials. `isManagedBase` is exactly membership of this map's keys, and a block outside it is always `UNTRACKED` |
| `veins.generation`                  | `seeded`                    | `seeded` or `pure_random`. `isSeeded()` reports it. In `pure_random`, all three vein methods answer empty |
| `veins.allow_placed_blocks`         | `false`                     | When `false`, player-placed blocks are excluded from `veinAt`, `veinSiblings` and `veinsNear`. Placement records are written either way |
| `drops`                             | eight item rules and one command rule | Rule order determines seeded vein positions. Reordering reshuffles every undiscovered vein |
| `drops[].veins_per_chunk`, `vein_min_size`, `vein_max_size` | per rule | How many vein positions exist and how large they are. Capped at 64 veins per chunk, 256 blocks per vein, and 1,024 worst-case target blocks per chunk across all item rules |
| `drops[].min_y`, `max_y`            | `-64` / `320`               | The Y band a rule's veins occupy                                              |

`ore-removal.*` changes world generation, not this API. A world with ore removal disabled still reports veins
in exactly the same places.

---

## Switching over `BlockOrigin`

`BlockOrigin` has three constants today and may gain more. A `switch` **expression** over an enum is
exhaustive, so it stops compiling — and throws `IncompatibleClassChangeError` on an already-compiled jar — the
moment a constant is added.

**Always write a `default` arm** in third-party code:

```java
public String describe(HiddenOreBreakEvent event) {
    return switch (event.getOrigin()) {
        case PLAYER_PLACED -> "placed by a player";
        case PRESUMED_GENERATED -> "no placement record";
        case UNTRACKED -> "not a managed material";
        default -> "unknown";
    };
}
```

Most consumers do not need the switch at all: `origin == BlockOrigin.PLAYER_PLACED` is the only test with a
defensible meaning, because it is the only value backed by a positive record.
