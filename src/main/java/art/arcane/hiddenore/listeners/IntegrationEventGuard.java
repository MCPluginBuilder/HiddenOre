package art.arcane.hiddenore.listeners;

import art.arcane.hiddenore.api.BlockOrigin;
import art.arcane.hiddenore.api.event.HiddenOreBreakEvent;
import art.arcane.hiddenore.api.event.HiddenOreDropsEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

final class IntegrationEventGuard {
  static final long SLOW_DISPATCH_NANOS = 5_000_000L;
  static final long LOG_THROTTLE_NANOS = 60_000_000_000L;
  static final String UNKNOWN_PLUGIN = "unknown";

  private final Logger logger;
  private final BreakEventDispatcher dispatcher;
  private final AtomicLong breakDispatchFaults = new AtomicLong();
  private final AtomicLong slowBreakDispatches = new AtomicLong();
  private final AtomicLong dropFloods = new AtomicLong();
  private final AtomicLong unidentifiedListeners = new AtomicLong();
  private final AtomicLong hostileDropStacks = new AtomicLong();
  private final AtomicLong lastFaultLogNanos = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong lastSlowLogNanos = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong lastFloodLogNanos = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong lastUnidentifiedLogNanos = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong lastHostileStackLogNanos = new AtomicLong(Long.MIN_VALUE);

  IntegrationEventGuard(Logger logger) {
    this(logger, event -> Bukkit.getPluginManager().callEvent(event));
  }

  IntegrationEventGuard(Logger logger, BreakEventDispatcher dispatcher) {
    this.logger = logger;
    this.dispatcher = dispatcher;
  }

  boolean isBreakVetoed(Player player, Block block, Material brokenType, ItemStack tool, BlockOrigin origin) {
    if (!hasListeners(HiddenOreBreakEvent.getHandlerList())) {
      return false;
    }
    return dispatchBreak(new HiddenOreBreakEvent(player, block, brokenType, tool.clone(), origin));
  }

  boolean dispatchBreak(HiddenOreBreakEvent event) {
    long startedNanos = System.nanoTime();
    try {
      dispatcher.call(event);
    } catch (Throwable failure) {
      long faults = breakDispatchFaults.incrementAndGet();
      if (shouldLog(lastFaultLogNanos.get(), System.nanoTime(), LOG_THROTTLE_NANOS)) {
        lastFaultLogNanos.set(System.nanoTime());
        logger.log(Level.WARNING, "HiddenOreBreakEvent dispatch failed (" + faults
            + " total); treating the break as un-vetoed. Listening plugins: "
            + describePlugins(pluginNames(HiddenOreBreakEvent.getHandlerList())), failure);
      }
      return false;
    }

    long elapsedNanos = System.nanoTime() - startedNanos;
    if (elapsedNanos >= SLOW_DISPATCH_NANOS) {
      long slow = slowBreakDispatches.incrementAndGet();
      if (shouldLog(lastSlowLogNanos.get(), System.nanoTime(), LOG_THROTTLE_NANOS)) {
        lastSlowLogNanos.set(System.nanoTime());
        logger.warning("HiddenOreBreakEvent listeners took " + (elapsedNanos / 1_000_000L)
            + "ms on the block break path (" + slow + " slow dispatches total). Listening plugins: "
            + describePlugins(pluginNames(HiddenOreBreakEvent.getHandlerList())));
      }
    }
    return event.isCancelled();
  }

  void reportDropFlood(int candidateStacks, int spawnedStacks) {
    long floods = dropFloods.incrementAndGet();
    if (!shouldLog(lastFloodLogNanos.get(), System.nanoTime(), LOG_THROTTLE_NANOS)) {
      return;
    }
    lastFloodLogNanos.set(System.nanoTime());
    logger.warning("HiddenOreDropsEvent produced " + candidateStacks + " stacks for a single break; spawning only "
        + spawnedStacks + " (" + floods + " floods total). Listening plugins: "
        + describePlugins(pluginNames(HiddenOreDropsEvent.getHandlerList())));
  }

  boolean isSpawnableDrop(ItemStack stack) {
    if (stack == null) {
      return false;
    }
    try {
      return !stack.getType().isAir() && stack.getAmount() > 0;
    } catch (Throwable failure) {
      long hostile = hostileDropStacks.incrementAndGet();
      if (shouldLog(lastHostileStackLogNanos.get(), System.nanoTime(), LOG_THROTTLE_NANOS)) {
        lastHostileStackLogNanos.set(System.nanoTime());
        logger.log(Level.WARNING, "HiddenOreDropsEvent supplied an item stack that could not be read (" + hostile
            + " total); skipping it. Listening plugins: "
            + describePlugins(pluginNames(HiddenOreDropsEvent.getHandlerList())), failure);
      }
      return false;
    }
  }

  long breakDispatchFaults() {
    return breakDispatchFaults.get();
  }

  long slowBreakDispatches() {
    return slowBreakDispatches.get();
  }

  long dropFloods() {
    return dropFloods.get();
  }

  long unidentifiedListeners() {
    return unidentifiedListeners.get();
  }

  long hostileDropStacks() {
    return hostileDropStacks.get();
  }

  static boolean shouldLog(long lastLogNanos, long nowNanos, long intervalNanos) {
    if (lastLogNanos == Long.MIN_VALUE) {
      return true;
    }
    return nowNanos - lastLogNanos >= intervalNanos;
  }

  static String describePlugins(Set<String> names) {
    return names.isEmpty() ? "none" : String.join(", ", names);
  }

  static boolean hasListeners(HandlerList handlers) {
    return handlers.getRegisteredListeners().length > 0;
  }

  private Set<String> pluginNames(HandlerList handlers) {
    Set<String> names = new TreeSet<>();
    for (RegisteredListener listener : handlers.getRegisteredListeners()) {
      names.add(pluginName(listener));
    }
    return names;
  }

  private String pluginName(RegisteredListener listener) {
    String name;
    try {
      Plugin plugin = listener.getPlugin();
      name = plugin == null ? null : plugin.getName();
    } catch (Throwable failure) {
      reportUnidentifiedListener(failure);
      return UNKNOWN_PLUGIN;
    }
    if (name == null || name.isBlank()) {
      reportUnidentifiedListener(null);
      return UNKNOWN_PLUGIN;
    }
    return name;
  }

  private void reportUnidentifiedListener(Throwable failure) {
    long unidentified = unidentifiedListeners.incrementAndGet();
    if (!shouldLog(lastUnidentifiedLogNanos.get(), System.nanoTime(), LOG_THROTTLE_NANOS)) {
      return;
    }
    lastUnidentifiedLogNanos.set(System.nanoTime());
    String message = "A HiddenOre event listener would not name its owning plugin (" + unidentified
        + " total); reporting it as '" + UNKNOWN_PLUGIN + "'";
    if (failure == null) {
      logger.warning(message);
      return;
    }
    logger.log(Level.WARNING, message, failure);
  }

  @FunctionalInterface
  interface BreakEventDispatcher {
    void call(HiddenOreBreakEvent event);
  }
}
