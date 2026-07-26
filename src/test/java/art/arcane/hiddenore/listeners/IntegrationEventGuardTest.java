package art.arcane.hiddenore.listeners;

import art.arcane.hiddenore.api.BlockOrigin;
import art.arcane.hiddenore.api.event.HiddenOreBreakEvent;
import art.arcane.hiddenore.api.event.HiddenOreDropsEvent;
import org.bukkit.Material;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IntegrationEventGuardTest {
  private final List<Registration> registrations = new ArrayList<>();

  @After
  public void unregisterProbes() {
    for (Registration registration : registrations) {
      registration.handlers().unregister(registration.listener());
    }
    registrations.clear();
  }

  @Test
  public void dispatchBreak_reportsAVetoWhenAListenerCancels() {
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> event.setCancelled(true));

    assertTrue(guard.dispatchBreak(breakEvent()));
    assertEquals(0L, guard.breakDispatchFaults());
    assertTrue(logger.records().isEmpty());
  }

  @Test
  public void dispatchBreak_reportsNoVetoWhenNobodyCancels() {
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
    });

    assertFalse(guard.dispatchBreak(breakEvent()));
    assertEquals(0L, guard.breakDispatchFaults());
  }

  @Test
  public void dispatchBreak_failsOpenAndCountsWhenDispatchThrows() {
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
      throw new IllegalStateException("hostile listener");
    });

    assertFalse(guard.dispatchBreak(breakEvent()));
    assertFalse(guard.dispatchBreak(breakEvent()));

    assertEquals(2L, guard.breakDispatchFaults());
    assertEquals(1, logger.records().size());
    assertEquals(Level.WARNING, logger.records().get(0).getLevel());
    assertTrue(logger.records().get(0).getMessage().contains("HiddenOreBreakEvent dispatch failed"));
    assertEquals("hostile listener", logger.records().get(0).getThrown().getMessage());
  }

  @Test
  public void dispatchBreak_survivesAnErrorNotJustAnException() {
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
      throw new NoClassDefFoundError("com/example/Missing");
    });

    assertFalse(guard.dispatchBreak(breakEvent()));
    assertEquals(1L, guard.breakDispatchFaults());
  }

  @Test
  public void dispatchBreak_countsASlowListenerWithoutChangingTheOutcome() {
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
      sleepPastTheSlowThreshold();
      event.setCancelled(true);
    });

    assertTrue(guard.dispatchBreak(breakEvent()));
    assertEquals(1L, guard.slowBreakDispatches());
    assertEquals(0L, guard.breakDispatchFaults());
    assertEquals(1, logger.records().size());
    assertTrue(logger.records().get(0).getMessage().contains("on the block break path"));
  }

  @Test
  public void isBreakVetoed_shortCircuitsBeforeTouchingTheServerWhenNobodyIsListening() {
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger());

    assertFalse(guard.isBreakVetoed(null, null, Material.DEEPSLATE_DIAMOND_ORE, null, BlockOrigin.PRESUMED_GENERATED));

    assertEquals(0L, guard.breakDispatchFaults());
    assertTrue(logger.records().isEmpty());
  }

  @Test
  public void isBreakVetoed_failsOpenThroughTheRealDispatcherWithAHostileRegistration() {
    registerOnBreak(throwingPluginRegistration());
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger());

    assertFalse(guard.isBreakVetoed(null, null, Material.DEEPSLATE_DIAMOND_ORE,
        new TestItemStack(Material.DIAMOND_PICKAXE, 1), BlockOrigin.PRESUMED_GENERATED));

    assertEquals(1L, guard.breakDispatchFaults());
    assertEquals(1L, guard.unidentifiedListeners());
    assertTrue(describedPlugins(logger).contains(IntegrationEventGuard.UNKNOWN_PLUGIN));
  }

  @Test
  public void dispatchBreak_failsOpenWhenTheFaultLogCannotIdentifyAHostileRegistration() {
    registerOnBreak(throwingPluginRegistration());
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
      throw new IllegalStateException("hostile listener");
    });

    assertFalse(guard.dispatchBreak(breakEvent()));

    assertEquals(1L, guard.breakDispatchFaults());
    assertEquals(1L, guard.unidentifiedListeners());
    assertTrue(describedPlugins(logger).contains(IntegrationEventGuard.UNKNOWN_PLUGIN));
  }

  @Test
  public void dispatchBreak_returnsTheVerdictWhenTheSlowLogCannotIdentifyAHostileRegistration() {
    registerOnBreak(throwingPluginRegistration());
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
      sleepPastTheSlowThreshold();
      event.setCancelled(true);
    });

    assertTrue(guard.dispatchBreak(breakEvent()));

    assertEquals(1L, guard.slowBreakDispatches());
    assertEquals(0L, guard.breakDispatchFaults());
    assertEquals(1L, guard.unidentifiedListeners());
    assertTrue(describedPlugins(logger).contains(IntegrationEventGuard.UNKNOWN_PLUGIN));
  }

  @Test
  public void dispatchBreak_survivesARegistrationWhosePluginIsNull() {
    registerOnBreak(nullPluginRegistration());
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
      throw new IllegalStateException("hostile listener");
    });

    assertFalse(guard.dispatchBreak(breakEvent()));

    assertEquals(1L, guard.unidentifiedListeners());
    assertTrue(describedPlugins(logger).contains(IntegrationEventGuard.UNKNOWN_PLUGIN));
    assertNull(identificationRecord(logger).getThrown());
  }

  @Test
  public void dispatchBreak_survivesAPluginWhoseNameThrows() {
    registerOnBreak(registration(hostileNamePlugin()));
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
      throw new IllegalStateException("hostile listener");
    });

    assertFalse(guard.dispatchBreak(breakEvent()));

    assertEquals(1L, guard.unidentifiedListeners());
    assertTrue(describedPlugins(logger).contains(IntegrationEventGuard.UNKNOWN_PLUGIN));
    assertEquals("hostile getName", identificationRecord(logger).getThrown().getMessage());
  }

  @Test
  public void dispatchBreak_survivesAPluginWhoseNameIsBlank() {
    registerOnBreak(registration(namedPlugin("   ")));
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
      throw new IllegalStateException("hostile listener");
    });

    assertFalse(guard.dispatchBreak(breakEvent()));
    assertTrue(describedPlugins(logger).contains(IntegrationEventGuard.UNKNOWN_PLUGIN));
  }

  @Test
  public void pluginIdentification_degradesOnlyTheBrokenRegistration() {
    registerOnBreak(throwingPluginRegistration());
    registerOnBreak(registration(namedPlugin("Prospector")));
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
      throw new IllegalStateException("hostile listener");
    });

    assertFalse(guard.dispatchBreak(breakEvent()));

    String described = describedPlugins(logger);
    assertTrue(described.contains("Prospector"));
    assertTrue(described.contains(IntegrationEventGuard.UNKNOWN_PLUGIN));
  }

  @Test
  public void dropFlood_isCountedAlwaysAndLoggedOnce() {
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
    });

    guard.reportDropFlood(4096, MiningListener.MAX_DROP_STACKS);
    guard.reportDropFlood(4096, MiningListener.MAX_DROP_STACKS);
    guard.reportDropFlood(4096, MiningListener.MAX_DROP_STACKS);

    assertEquals(3L, guard.dropFloods());
    assertEquals(1, logger.records().size());
    assertTrue(logger.records().get(0).getMessage().contains("4096 stacks for a single break"));
  }

  @Test
  public void dropFlood_survivesAHostileRegistrationOnTheDropsEvent() {
    registerOnDrops(throwingPluginRegistration());
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
    });

    guard.reportDropFlood(4096, MiningListener.MAX_DROP_STACKS);

    assertEquals(1L, guard.dropFloods());
    assertEquals(1L, guard.unidentifiedListeners());
    assertTrue(describedPlugins(logger).contains(IntegrationEventGuard.UNKNOWN_PLUGIN));
  }

  @Test
  public void spawnableDrop_acceptsOnlyRealStacks() {
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
    });

    assertFalse(guard.isSpawnableDrop(null));
    assertFalse(guard.isSpawnableDrop(new TestItemStack(Material.DIAMOND, 0)));
    assertFalse(guard.isSpawnableDrop(new TestItemStack(Material.DIAMOND, -1)));
    assertTrue(guard.isSpawnableDrop(new TestItemStack(Material.DIAMOND, 1)));
    assertEquals(0L, guard.hostileDropStacks());
    assertTrue(logger.records().isEmpty());
  }

  @Test
  public void spawnableDrop_rejectsAHostileStackInsteadOfEscaping() {
    CapturingLogger logger = new CapturingLogger();
    IntegrationEventGuard guard = new IntegrationEventGuard(logger.logger(), event -> {
    });

    assertFalse(guard.isSpawnableDrop(new HostileItemStack()));
    assertFalse(guard.isSpawnableDrop(new HostileItemStack()));

    assertEquals(2L, guard.hostileDropStacks());
    assertEquals(1, logger.records().size());
    assertEquals("hostile getType", logger.records().get(0).getThrown().getMessage());
  }

  @Test
  public void throttle_logsTheFirstEventThenOncePerInterval() {
    assertTrue(IntegrationEventGuard.shouldLog(Long.MIN_VALUE, 0L, 100L));
    assertFalse(IntegrationEventGuard.shouldLog(1_000L, 1_099L, 100L));
    assertTrue(IntegrationEventGuard.shouldLog(1_000L, 1_100L, 100L));
    assertTrue(IntegrationEventGuard.shouldLog(1_000L, 5_000L, 100L));
  }

  @Test
  public void throttle_survivesNanoTimeWraparound() {
    assertTrue(IntegrationEventGuard.shouldLog(Long.MAX_VALUE - 50L, Long.MIN_VALUE + 60L, 100L));
    assertFalse(IntegrationEventGuard.shouldLog(Long.MAX_VALUE - 50L, Long.MIN_VALUE + 40L, 100L));
  }

  @Test
  public void pluginDescription_namesEveryListenerAndSaysNoneWhenThereAreNo() {
    assertEquals("none", IntegrationEventGuard.describePlugins(Set.of()));
    assertEquals("Adapt, Prospector",
        IntegrationEventGuard.describePlugins(new TreeSet<>(List.of("Prospector", "Adapt"))));
  }

  @Test
  public void listenerProbe_reportsAnEmptyHandlerListAsUnlistened() {
    assertFalse(IntegrationEventGuard.hasListeners(new HandlerList()));
    assertFalse(IntegrationEventGuard.hasListeners(HiddenOreBreakEvent.getHandlerList()));
  }

  @Test
  public void listenerProbe_reportsARegisteredListenerAsListened() {
    registerOnBreak(registration(namedPlugin("Prospector")));

    assertTrue(IntegrationEventGuard.hasListeners(HiddenOreBreakEvent.getHandlerList()));
  }

  private static void sleepPastTheSlowThreshold() {
    try {
      Thread.sleep(IntegrationEventGuard.SLOW_DISPATCH_NANOS / 1_000_000L + 2L);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static LogRecord identificationRecord(CapturingLogger logger) {
    for (LogRecord record : logger.records()) {
      if (record.getMessage().contains("would not name its owning plugin")) {
        return record;
      }
    }
    throw new AssertionError("no listener identification record was logged");
  }

  private static String describedPlugins(CapturingLogger logger) {
    for (LogRecord record : logger.records()) {
      String message = record.getMessage();
      int marker = message.indexOf("Listening plugins: ");
      if (marker >= 0) {
        return message.substring(marker);
      }
    }
    return "";
  }

  private void registerOnBreak(RegisteredListener listener) {
    register(HiddenOreBreakEvent.getHandlerList(), listener);
  }

  private void registerOnDrops(RegisteredListener listener) {
    register(HiddenOreDropsEvent.getHandlerList(), listener);
  }

  private void register(HandlerList handlers, RegisteredListener listener) {
    handlers.register(listener);
    registrations.add(new Registration(handlers, listener));
  }

  private static RegisteredListener registration(Plugin plugin) {
    return new RegisteredListener(inertListener(), inertExecutor(), EventPriority.NORMAL, plugin, false);
  }

  private static RegisteredListener throwingPluginRegistration() {
    return new RegisteredListener(inertListener(), inertExecutor(), EventPriority.NORMAL, null, false) {
      @Override
      public Plugin getPlugin() {
        throw new IllegalStateException("hostile getPlugin");
      }
    };
  }

  private static RegisteredListener nullPluginRegistration() {
    return new RegisteredListener(inertListener(), inertExecutor(), EventPriority.NORMAL, null, false);
  }

  private static Listener inertListener() {
    return new Listener() {
    };
  }

  private static EventExecutor inertExecutor() {
    return (listener, event) -> {
    };
  }

  private static Plugin namedPlugin(String name) {
    return pluginProxy((Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
      case "getName" -> name;
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == arguments[0];
      case "toString" -> "Plugin";
      default -> null;
    });
  }

  private static Plugin hostileNamePlugin() {
    return pluginProxy((Object proxy, Method method, Object[] arguments) -> {
      if ("getName".equals(method.getName())) {
        throw new IllegalStateException("hostile getName");
      }
      return switch (method.getName()) {
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == arguments[0];
        case "toString" -> "Plugin";
        default -> null;
      };
    });
  }

  private static Plugin pluginProxy(java.lang.reflect.InvocationHandler handler) {
    return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
  }

  private static HiddenOreBreakEvent breakEvent() {
    return new HiddenOreBreakEvent(null, null, Material.DEEPSLATE_DIAMOND_ORE, null, BlockOrigin.PRESUMED_GENERATED);
  }

  private record Registration(HandlerList handlers, RegisteredListener listener) {
  }

  private static final class TestItemStack extends ItemStack {
    private final Material type;
    private final int amount;

    private TestItemStack(Material type, int amount) {
      this.type = type;
      this.amount = amount;
    }

    @Override
    public Material getType() {
      return type;
    }

    @Override
    public int getAmount() {
      return amount;
    }

    @Override
    public ItemStack clone() {
      return new TestItemStack(type, amount);
    }
  }

  private static final class HostileItemStack extends ItemStack {
    @Override
    public Material getType() {
      throw new IllegalStateException("hostile getType");
    }
  }

  private static final class CapturingLogger {
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Logger logger = Logger.getAnonymousLogger();

    private CapturingLogger() {
      logger.setUseParentHandlers(false);
      logger.setLevel(Level.ALL);
      logger.addHandler(new Handler() {
        @Override
        public void publish(LogRecord record) {
          records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
      });
    }

    private Logger logger() {
      return logger;
    }

    private List<LogRecord> records() {
      return records;
    }
  }
}
