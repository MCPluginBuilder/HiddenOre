# HiddenOre events: the break event and the drops event

HiddenOre fires two events during a mining reward, and the difference between them is deliberate:

| Event                    | Cancellable | Fires                                     | You use it to                                  |
|--------------------------|-------------|-------------------------------------------|------------------------------------------------|
| `HiddenOreBreakEvent`    | **yes**     | before any reward is computed             | refuse                                          |
| `HiddenOreDropsEvent`    | **no**      | after rewards are computed, before they are delivered | edit                                  |

`HiddenOreDropsEvent` is not cancellable on purpose. Mining integrations do irreversible work while handling it
— granting skill experience, consuming durability budgets, running vein-miner passes, writing progression to a
database. If a later listener could annul the event, every one of those consumers would have to be written to
undo itself, and none of them can. So the refusal point is moved earlier, to a separate event that runs before
anything has happened. **Block and drop protection are settled before either event fires**; by the time the
drops event runs, the block is already broken and the question is only what comes out of it.

Both classes live in `art.arcane.hiddenore.api.event` and each owns its own `HandlerList`. There is no shared
base class, and neither event requires the service — a plugin can integrate with HiddenOre using events alone.

---

## Depending on HiddenOre

See [README.md](README.md) — `softdepend: [HiddenOre]` on Bukkit, `join-classpath: true` on Paper. Nothing else
is needed for events: no service lookup, no registration with HiddenOre, no capability handshake.

One rule, and it is the one that bites: **put your HiddenOre handlers in a class of their own and register that
class only when HiddenOre is present.** `PluginManager.registerEvents` resolves the parameter types of every
handler method on the listener, so registering a listener that mentions `HiddenOreBreakEvent` on a server
without HiddenOre throws `NoClassDefFoundError`. `getServer().getPluginManager().isPluginEnabled("HiddenOre")`
is enough of a check; so is a non-null `HiddenOreService` registration.

---

## When they fire

Every one of these must hold or neither event fires:

1. A player breaks a block and `BlockBreakEvent` survives to `HIGHEST` priority uncancelled.
2. The player is **not** in creative mode.
3. The item in the main hand is a pickaxe — wooden, stone, copper, iron, golden, diamond or netherite.
4. The broken block's material is a key of `blocks:` in the configuration.
5. `BlockBreakEvent.isDropItems()` is `true`.
6. The break actually reaches `BlockDropItemEvent`, and that event survives to `MONITOR` uncancelled.
7. HiddenOre is not draining when `BlockBreakEvent` reaches `HIGHEST`, and still not draining when
   `BlockDropItemEvent` reaches `HIGHEST`. Those are the only two points at which the drain flag is read; a
   drain that lands after the second one does not stop the break that is already in flight.

Nothing else produces these events. Explosions, pistons, mob griefing, `Block.setType`, world edits and other
plugins breaking blocks do not, because none of them is a player `BlockBreakEvent` followed by a
`BlockDropItemEvent`.

## The order of events

```
BlockBreakEvent  (HIGHEST)   HiddenOre zeroes vanilla experience for the managed block
                             and remembers the tool and the live configuration
BlockBreakEvent  (MONITOR)   HiddenOre discards that if the break was cancelled, will not drop
                             items, or has already left air behind
        |
        v
BlockDropItemEvent (HIGHEST) HiddenOre clears the vanilla drop list
BlockDropItemEvent (MONITOR) HiddenOre commits, and inside that handler:
        |
        +--> HiddenOreBreakEvent          cancellable. Nothing has been decided yet
        |
        |    reward path chosen; a seeded vein is marked consumed here
        |
        +--> HiddenOreDropsEvent          not cancellable. The list, the experience and
        |                                 the delivery mode are yours to edit
        |
        +--> reward commands are scheduled
        +--> item stacks are spawned or inserted into the inventory
        +--> one experience orb is spawned if experience is above zero
```

Two consequences a reader would otherwise find by debugging:

- **The block is already air when either event fires.** `getBlock()` gives you the position and the world, not
  the material. Use `getBrokenType()` for what was broken.
- **HiddenOre empties the vanilla drop list** at `BlockDropItemEvent` `HIGHEST` for every break it handles, and
  spawns its own items instead. A `BlockDropItemEvent` listener at `MONITOR` sees an empty list for those
  breaks. Read `HiddenOreDropsEvent` instead. Breaks HiddenOre does not handle — creative, a non-pickaxe tool,
  an unmanaged block — keep their vanilla drops untouched.

Vanilla experience is also zeroed for managed blocks, at `BlockBreakEvent` `HIGHEST`. All experience from a
managed break arrives through `HiddenOreDropsEvent`.

## Threading

Both events are dispatched synchronously from inside HiddenOre's `BlockDropItemEvent` handler. That means:

- They fire on the **region thread that owns the broken block** — the main thread on Paper and Spigot, one
  specific region thread on Folia. `isAsynchronous()` is `false` for both.
- Reading and mutating the mining player's inventory and experience is legal there, as is calling any
  `HiddenOreService` method **for that block's position**: `ownsRegion` for the broken block is guaranteed
  `true` inside these handlers. It is not guaranteed for a block in another chunk far away — probe first.
- **Do not block.** No I/O, no `CompletableFuture.join`, no `callSyncMethod`, no lock held across the call. That
  one thread ticks every player, entity and block in the region; a listener that blocks stalls all of them.
  Break listeners that take 5ms or longer in total are logged with a warning naming every plugin listening, but
  the warning never changes the outcome — a listener that hangs cannot be interrupted.
- If you need remote data, cache it. Prime it on `PlayerJoinEvent`.

---

## `HiddenOreBreakEvent`

```java
public final class HiddenOreBreakEvent extends Event implements Cancellable {
    public HiddenOreBreakEvent(Player player, Block block, Material brokenType, ItemStack tool, BlockOrigin origin);

    public Player getPlayer();
    public Block getBlock();
    public Material getBrokenType();
    public ItemStack getTool();
    public BlockOrigin getOrigin();

    public boolean isCancelled();
    public void setCancelled(boolean cancel);

    public HandlerList getHandlers();
    public static HandlerList getHandlerList();
}
```

| Accessor         | What it gives you                                                                                  |
|------------------|------------------------------------------------------------------------------------------------------|
| `getPlayer()`    | The miner                                                                                            |
| `getBlock()`     | The block position, already broken to air                                                            |
| `getBrokenType()`| The material that was broken — the value to test                                                      |
| `getTool()`      | A **copy** of the main-hand item as it was at `BlockBreakEvent` time. Mutating it changes nothing      |
| `getOrigin()`    | `PLAYER_PLACED` or `PRESUMED_GENERATED`, never `UNTRACKED` — an unmanaged block never reaches this event |

Read [service.md](service.md) before you act on `getOrigin()`. `PRESUMED_GENERATED` means "tracked material,
no placement record" and is not proof of world generation.

### What cancelling does

| Suppressed by a cancel                         | Not suppressed by a cancel                                                |
|------------------------------------------------|----------------------------------------------------------------------------|
| The hidden item reward                          | The block break itself. The block is already gone and stays gone            |
| Rule experience                                 | The configured guaranteed base drop for that block                          |
| Reward commands                                 | `HiddenOreDropsEvent`, which still fires — carrying only the base drop, a `null` vein and zero experience |
| The vein discovery sound                        | The zeroing of vanilla experience, which already happened                   |
| Marking a seeded vein consumed — the vein stays payable for the next miner | Other listeners of the same event, which run normally |

A cancel is therefore "HiddenOre contributes nothing to this break", not "this break did not happen". If you
want the break itself refused, cancel `BlockBreakEvent` — HiddenOre honours that and never gets involved.

Cancelling a break for a `PLAYER_PLACED` block while `veins.allow_placed_blocks` is `false` adds nothing:
HiddenOre already withholds every reward from those blocks.

`ignoreCancelled = true` behaves normally. A listener declaring it is skipped once an earlier listener has
cancelled.

---

## `HiddenOreDropsEvent`

```java
public class HiddenOreDropsEvent extends Event {
    public HiddenOreDropsEvent(Player player, Block block, Material brokenType, ItemStack tool,
                               @Nullable HiddenVein vein, List<ItemStack> drops, int experience, boolean toInventory);

    public Player getPlayer();
    public Block getBlock();
    public Material getBrokenType();
    public ItemStack getTool();
    @Nullable public HiddenVein getVein();

    public List<ItemStack> getDrops();
    public int getExperience();
    public void setExperience(int experience);
    public boolean isToInventory();
    public void setToInventory(boolean toInventory);

    public HandlerList getHandlers();
    public static HandlerList getHandlerList();
}
```

### What it hands you

`getDrops()` returns the **live, mutable** list HiddenOre is about to spawn. Add to it, remove from it, clear
it. There is no setter — mutate in place, and mutate it during the event, because HiddenOre reads the list as
soon as the last listener returns and ignores anything you do to it afterwards.

When HiddenOre fires the event the list holds at most two stacks, in this order:

1. the hidden reward item, if one was rolled — at most one stack, quantity already multiplied by Fortune if the
   rule enables it;
2. the configured guaranteed base drop, one item.

The base drop is omitted when `suppress_block_drop_on_custom_drop` is enabled **and** a reward item or reward
command fired. Clearing the list entirely is legal and results in a break that drops nothing at all.

`getExperience()` is the rolled amount for this break — a uniform integer from `0` to the paying rule's
`exp_drop`, inclusive, or `0` when nothing paid out. `setExperience` clamps negatives to `0`. The final value is
delivered as a **single** experience orb at the centre of the block, and only if it is above zero.

`isToInventory()` starts at the value of `auto_pickup_drops`. Set it to `true` and every stack is inserted into
the player's inventory, with the overflow dropped at the block. Set it to `false` and everything drops.

`getVein()` is `null` when no reward was rolled, when the break was vetoed, and when the block was player-placed
under a restrictive `allow_placed_blocks`. When it is non-null, `vein.seeded()` distinguishes a real seeded vein
(`veinId >= 0`) from a `pure_random` payout (`veinId == -1`), and `vein.oreDisplay()` may be `null` for a
non-vanilla drop material. See [service.md](service.md) for the whole record.

`getTool()` is a copy, like the break event's. Mutating it changes nothing.

By the time this event fires, a seeded vein position has **already** been marked consumed. `isVeinConsumed` on
that block answers `true` from here, and answers `false` from the break event handler.

Consumption happens before the pickaxe tier is tested, so a seeded position mined with too weak a pickaxe is
spent and pays nothing: `getVein()` is `null`, the list holds only the base drop, and the position will never
pay again. Only a cancelled `HiddenOreBreakEvent` and a player-placed block under a restrictive
`allow_placed_blocks` leave the position payable.

### What it does not hand you

There is no cancel, no way to replace the drop list object, no access to the reward commands that are about to
run, and no way to change which vein was hit. Refusal belongs in `HiddenOreBreakEvent`.

---

## Worked example

A plugin that refuses rewards from player-placed blocks and from players without a permission, pays a bonus on
seeded diamond veins, and tells the miner how much of the vein is left.

```java
package com.example.quarryguard;

import art.arcane.hiddenore.api.BlockOrigin;
import art.arcane.hiddenore.api.HiddenOreService;
import art.arcane.hiddenore.api.HiddenVein;
import art.arcane.hiddenore.api.event.HiddenOreBreakEvent;
import art.arcane.hiddenore.api.event.HiddenOreDropsEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class QuarryListener implements Listener {
    private final HiddenOreService hiddenOre;

    public QuarryListener(HiddenOreService hiddenOre) {
        this.hiddenOre = hiddenOre;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHiddenOreBreak(HiddenOreBreakEvent event) {
        Player player = event.getPlayer();

        if (event.getOrigin() == BlockOrigin.PLAYER_PLACED) {
            event.setCancelled(true);
            return;
        }

        if (!player.hasPermission("quarryguard.mine")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onHiddenOreDrops(HiddenOreDropsEvent event) {
        HiddenVein vein = event.getVein();

        if (vein == null) {
            return;
        }

        if (vein.seeded() && vein.item() == Material.DIAMOND) {
            List<ItemStack> drops = event.getDrops();
            drops.add(new ItemStack(Material.EMERALD, 1));
            event.setExperience(event.getExperience() + 5);
        }

        if (event.getPlayer().hasPermission("quarryguard.autopickup")) {
            event.setToInventory(true);
        }

        if (vein.seeded()) {
            int remaining = hiddenOre.veinSiblings(event.getBlock()).size();
            event.getPlayer().sendMessage("Blocks left in this vein: " + remaining);
        }
    }
}
```

The `veinSiblings` call at the end is the practical demonstration of the threading rule: it needs the owning
region thread, and inside this handler you are on it for this block, so no probe and no scheduling are
required. The same call for a block in another region would need both.

Register it the ordinary way, in `onEnable`, and only when HiddenOre is present:

```java
getServer().getPluginManager().registerEvents(new QuarryListener(hiddenOre), this);
```

Bukkit unregisters your listeners when your plugin disables. HiddenOre keeps no registry of its own and there
is nothing to unregister with it.

## The minimum

Watching, with no veto and no service:

```java
@EventHandler
public void onHiddenOreDrops(HiddenOreDropsEvent event) {
    if (event.getVein() != null) {
        statistics.record(event.getPlayer().getUniqueId(), event.getVein().item());
    }
}
```

Refusing, with no service:

```java
@EventHandler(ignoreCancelled = true)
public void onHiddenOreBreak(HiddenOreBreakEvent event) {
    if (claims.isForeign(event.getPlayer(), event.getBlock().getLocation())) {
        event.setCancelled(true);
    }
}
```

---

## Failure policy

HiddenOre assumes a listener will throw, be slow, hand back a hostile item stack, or try to flood the world
with entities. The reward path is **fail-open**: a listener that misbehaves never blocks a reward, because a
third-party bug that makes mining unrewarding on a whole server is worse than one that pays out when it should
not have.

| Misbehaviour                                             | What HiddenOre does                                                                 |
|----------------------------------------------------------|--------------------------------------------------------------------------------------|
| A break listener throws                                  | Bukkit catches and logs it per listener; the remaining listeners still run; any cancel it had not yet set does not happen |
| The whole break dispatch fails                           | Counted as a fault, logged with the stack trace and the names of every plugin listening, and the break is treated as **un-vetoed** |
| Break listeners take 5ms or more in total                | Throttled warning naming every plugin listening. The outcome is unchanged             |
| A drops listener throws                                  | Bukkit catches and logs it; HiddenOre proceeds with the list, experience and delivery flag as they stand |
| A drops listener clears the list                         | Nothing spawns, including the base drop. This is a supported outcome, not a fault     |
| The drop list exceeds **256** stacks                     | Only the first 256 entries are examined and spawned. The rest are dropped on the floor of the API, and a throttled warning names every plugin listening |
| A `null`, air, or zero-quantity stack                    | Skipped silently                                                                      |
| A stack whose `getType()` or `getAmount()` throws        | Skipped, counted, and logged with the stack trace                                     |
| `setExperience` with a negative value                    | Clamped to `0`                                                                        |
| A listener that will not name its owning plugin          | Reported as `unknown` in the logs above, and counted                                  |
| The same listener registered twice                       | Called twice. HiddenOre does not deduplicate — that is Bukkit's contract, not HiddenOre's |
| No listeners at all for `HiddenOreBreakEvent`            | The event object is never constructed. Registering costs you nothing when you are the only consumer |
| HiddenOre drains before `BlockDropItemEvent` `HIGHEST`    | Neither event fires and the vanilla drop list is left alone, but the vanilla experience zeroed at `BlockBreakEvent` `HIGHEST` is not restored |
| HiddenOre drains after `BlockDropItemEvent` `HIGHEST`     | That break is already committed. Both events fire and the reward is delivered normally |

Every log line HiddenOre writes above is throttled to at most one per minute per category, and every one of
them names the plugins registered on the relevant handler list, so an operator can find the culprit without a
profiler. The per-listener exception trace is Bukkit's own and is not throttled.
Faults are counted for the log message; **a listener is never quarantined, never skipped, and never
unregistered by HiddenOre**. There is no configuration key that turns event faults into a denial.

---

## Configuration keys that change these events

`plugins/HiddenOre/config.yml`:

| Key                                       | Default in code | Shipped value | Effect                                                        |
|-------------------------------------------|-----------------|---------------|----------------------------------------------------------------|
| `blocks`                                  | required        | `stone`, `deepslate` | Which breaks reach the events at all                     |
| `auto_pickup_drops`                       | `false`         | `false`       | The initial value of `isToInventory()`                          |
| `suppress_block_drop_on_custom_drop`      | `false`         | `true`        | Whether the base drop is omitted from the list when a reward item or command fired |
| `veins.generation`                        | `seeded`        | `seeded`      | Whether `getVein()` can carry a seeded vein (`veinId >= 0`) or only a `pure_random` payout (`-1`) |
| `veins.allow_placed_blocks`               | `false`         | `false`       | When `false`, a `PLAYER_PLACED` break gets no reward regardless of what listeners do |
| `veins.discovery_sound.sound` / `.volume` / `.pitch` | `BLOCK_BEACON_POWER_SELECT`, `1.0`, `1.0` | same | The sound played when a vein is first struck. Suppressed by a cancelled break |
| `drops[].tool_tiers`                      | required        | per rule      | A break with a lower-tier pickaxe still fires both events, with no reward item |
| `drops[].fortune_multiplier`              | `false`         | per rule      | Whether the reward stack quantity is multiplied before you see it |
| `drops[].exp_drop`                        | material default | per rule     | The upper bound of the inclusive experience roll. Capped at 1,000 |

The values in force for a break are the ones that were live when `BlockBreakEvent` fired, not when the drops
event fires. A configuration reload during a break cannot change that break's outcome halfway through.
