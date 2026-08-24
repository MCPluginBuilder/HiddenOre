package art.arcane.hiddenore;

import art.arcane.hiddenore.api.HiddenOreAPI;
import art.arcane.hiddenore.api.HiddenOreService;
import art.arcane.hiddenore.generation.GenerationRules;
import art.arcane.hiddenore.listeners.MiningListener;
import art.arcane.hiddenore.listeners.PlacementListener;
import art.arcane.hiddenore.listeners.WorldLifecycleListener;
import art.arcane.hiddenore.rules.MiningRuleManager;
import art.arcane.hiddenore.service.HiddenOreCommandService;
import art.arcane.hiddenore.service.HiddenOreIntegrationService;
import art.arcane.hiddenore.service.HiddenOrePlaceholderService;
import art.arcane.hiddenore.service.HiddenOreTelemetry;
import art.arcane.hiddenore.util.common.ConsoleAudienceFallback;
import art.arcane.hiddenore.util.common.Messages;
import art.arcane.hiddenore.util.common.SplashScreen;
import art.arcane.hiddenore.util.project.ConfigWatcher;
import art.arcane.hiddenore.util.project.SoundResolver;
import art.arcane.hiddenore.vein.SeededVeinGenerator;
import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.util.bukkit.ChunkPositionSet;
import io.github.slimjar.app.builder.SpigotApplicationBuilder;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HiddenOre extends JavaPlugin implements ReloadAware {
  // bstats.org plugin id
  private static final int BSTATS_PLUGIN_ID = 27610;
  private static final long LOG_THROTTLE_NANOS = TimeUnit.MINUTES.toNanos(1L);
  private static volatile BukkitAudiences audiences;
  private final Set<UUID> debugPlayers = ConcurrentHashMap.newKeySet();
  private final ConcurrentMap<String, LogThrottle> logThrottles = new ConcurrentHashMap<>();
  private GenerationRules generationRules;
  private ConfigWatcher configWatcher;
  private HiddenOreCommandService commandService;
  private HiddenOreIntegrationService integrationService;
  private HiddenOrePlaceholderService placeholderService;
  private ChunkPositionSet placedBlocks;
  private ChunkPositionSet consumedVeins;
  private HiddenOreAPI api;
  // HiddenOreMetrics owns all bstats types; never reference them from this class (slimjar link trap)
  private HiddenOreMetrics metrics;
  private volatile RuntimeState runtimeState;
  private volatile AppliedConfigSnapshot appliedConfigSnapshot;
  private volatile boolean draining;
  private boolean serviceRegistered;

  public HiddenOre() {
    info("Loading dependencies...");
    new SpigotApplicationBuilder(this)
      .build();
    info("Dependencies loaded.");
  }

  @Override
  public void onEnable() {
    draining = false;

    try {
      audiences = BukkitAudiences.create(this);
      File configFile = new File(getDataFolder(), "hiddenore.yml");
      if (!configFile.exists()) {
        saveResource("hiddenore.yml", false);
      }
      File langFile = new File(getDataFolder(), "language.yml");
      if (!langFile.exists()) {
        saveResource("language.yml", false);
      }
      placedBlocks = new ChunkPositionSet(this, "placed_blocks");
      consumedVeins = new ChunkPositionSet(this, "consumed_veins");
      api = new HiddenOreAPI(this);
      generationRules = new GenerationRules(this);
      reloadAll();
      generationRules.start();
      getServer().getPluginManager().registerEvents(new MiningListener(this), this);
      getServer().getPluginManager().registerEvents(new PlacementListener(this), this);
      getServer().getPluginManager().registerEvents(new WorldLifecycleListener(this), this);
      commandService = new HiddenOreCommandService(this);
      commandService.register();
      integrationService = new HiddenOreIntegrationService(this);
      integrationService.register();
      placeholderService = new HiddenOrePlaceholderService(this);
      placeholderService.register();
      getServer().getServicesManager().register(HiddenOreService.class, api, this, ServicePriority.Normal);
      serviceRegistered = true;
      debug("HiddenOre service registered for third-party integrations.");
      configWatcher = new ConfigWatcher(this);
      AppliedConfigSnapshot startupSnapshot = appliedConfigSnapshot;
      if (startupSnapshot == null) {
        throw new IllegalStateException("HiddenOre configuration snapshot is unavailable after startup reload");
      }
      configWatcher.startWithAppliedSnapshot(startupSnapshot.configYaml(), startupSnapshot.languageYaml());
      SplashScreen.print(this, true);
    } catch (Exception exception) {
      logException(Level.SEVERE, exception, "HiddenOre failed to enable.");
      try {
        SplashScreen.print(this, false);
      } catch (RuntimeException splashException) {
        logException(Level.SEVERE, splashException, "Error rendering the HiddenOre startup failure screen.");
      } finally {
        drain();
        getServer().getPluginManager().disablePlugin(this);
      }
      return;
    }

    if (generationRules != null) {
      if (generationRules.isEnabled()) {
        info("Ore replacement in newly generated chunks is enabled; verify ore-removal.enabled if this is unintended.");
      }
    }

    if (BSTATS_PLUGIN_ID > 0 && getRuntimeState().metrics()) {
      try {
        metrics = HiddenOreMetrics.start(this, BSTATS_PLUGIN_ID);
      } catch (RuntimeException exception) {
        logException(Level.WARNING, exception, "Failed to initialize HiddenOre metrics.");
      }
    }
  }

  @Override
  public void onDisable() {
    drain();
  }

  @Override
  public void onPreUnload(ReloadAware.PreUnloadReason reason) {
    info("BileTools pre-unload hook fired (%s). Draining HiddenOre runtime services.", reason);
    drain();
  }

  public void info(String message, Object... args) {
    log(Level.INFO, message, args);
  }

  public void debug(String message, Object... args) {
    log(Level.FINE, message, args);
  }

  public void warn(String message, Object... args) {
    log(Level.WARNING, message, args);
  }

  public void warnThrottled(String key, String message, Object... args) {
    Logger logger = getLogger();
    if (!logger.isLoggable(Level.WARNING)) {
      return;
    }
    long suppressed = claimLog(key);
    if (suppressed < 0L) {
      return;
    }
    logger.warning(withSuppressed(format(message, args), suppressed));
  }

  public void logException(Level level, Throwable failure, String message, Object... args) {
    Logger logger = getLogger();
    if (logger.isLoggable(level)) {
      logger.log(level, format(message, args), failure);
    }
  }

  private synchronized void drain() {
    if (draining) {
      return;
    }
    draining = true;
    // Stop the bStats scheduler first so no chart callable observes a half-drained runtime.
    if (metrics != null) {
      metrics.shutdown();
      metrics = null;
    }
    if (serviceRegistered) {
      serviceRegistered = false;
      getServer().getServicesManager().unregister(HiddenOreService.class, api);
    }
    if (placeholderService != null) {
      placeholderService.unregister();
      placeholderService = null;
    }
    if (integrationService != null) {
      integrationService.unregister();
      integrationService = null;
    }
    if (configWatcher != null) {
      configWatcher.stop();
      configWatcher = null;
    }
    if (generationRules != null) {
      generationRules.close();
      generationRules = null;
    }
    debugPlayers.clear();
    if (audiences != null) {
      try {
        audiences.close();
      } catch (Throwable ex) {
        logException(Level.WARNING, ex, "Error closing Adventure audiences.");
      }
      audiences = null;
    }
  }

  public static BukkitAudiences audiences() {
    return audiences;
  }

  public static void sendMessage(CommandSender sender, Component component) {
    if (sender == null || component == null) {
      return;
    }
    // Routing (incl. the instanceof Audience check) lives in ConsoleAudienceFallback:
    // an instanceof on a slimjar-provided type in this class fails the plugin load on
    // Spigot before ApplicationBuilder.build().
    ConsoleAudienceFallback.route(sender, component, audiences);
  }

  public MiningRuleManager getRuleManager() {
    return getRuntimeState().ruleManager();
  }

  public Messages getMessages() {
    return getRuntimeState().messages();
  }

  public synchronized void reloadAll() {
    if (draining) {
      throw new IllegalStateException("HiddenOre is shutting down");
    }

    File configFile = new File(getDataFolder(), "hiddenore.yml");
    File langFile = new File(getDataFolder(), "language.yml");
    AppliedConfigSnapshot snapshot = new AppliedConfigSnapshot(
        readYaml(configFile, "hiddenore.yml"),
        readYaml(langFile, "language.yml")
    );
    applyReloadSnapshot(configFile, langFile, snapshot);
    ConfigWatcher watcher = configWatcher;
    if (watcher != null) {
      watcher.resetAfterManualReload(snapshot.configYaml(), snapshot.languageYaml());
    }
  }

  public synchronized void reloadAll(String configYaml, String languageYaml) {
    if (draining) {
      throw new IllegalStateException("HiddenOre is shutting down");
    }

    File configFile = new File(getDataFolder(), "hiddenore.yml");
    File langFile = new File(getDataFolder(), "language.yml");
    applyReloadSnapshot(configFile, langFile, new AppliedConfigSnapshot(configYaml, languageYaml));
  }

  private void applyReloadSnapshot(File configFile, File langFile, AppliedConfigSnapshot snapshot) {
    YamlConfiguration config = loadYaml(snapshot.configYaml(), configFile, "hiddenore.yml");
    YamlConfiguration langConfig = loadYaml(snapshot.languageYaml(), langFile, "language.yml");
    applyReload(configFile, langFile, config, langConfig);
    appliedConfigSnapshot = snapshot;
  }

  private void applyReload(File configFile, File langFile, YamlConfiguration config,
                           YamlConfiguration langConfig) {

    MiningRuleManager nextRuleManager;
    boolean autoPickup;
    boolean suppressBlockDrop;
    boolean metricsEnabled;
    String language;
    GenerationRules.GenerationPolicy generationPolicy;
    try {
      nextRuleManager = new MiningRuleManager(config);
      autoPickup = optionalBoolean(config, "auto_pickup_drops", false);
      suppressBlockDrop = optionalBoolean(config, "suppress_block_drop_on_custom_drop", false);
      metricsEnabled = optionalBoolean(config, "metrics", true);
      language = optionalString(config, "language", "en_US");
      generationPolicy = GenerationRules.parsePolicy(config);
    } catch (IllegalArgumentException exception) {
      throw invalidConfiguration(configFile, exception);
    }

    Messages nextMessages;
    ReloadNotification reloadNotification;
    try {
      nextMessages = new Messages();
      nextMessages.reload(langConfig, langFile.getAbsolutePath(), language);
      reloadNotification = parseReloadNotification(langConfig);
    } catch (IllegalArgumentException exception) {
      throw invalidConfiguration(langFile, exception);
    }

    SeededVeinGenerator nextVeinGenerator = new SeededVeinGenerator(nextRuleManager.getAllDropRules());

    runtimeState = new RuntimeState(nextRuleManager, nextMessages, nextVeinGenerator, generationPolicy,
        reloadNotification, autoPickup, suppressBlockDrop, metricsEnabled);
    HiddenOreTelemetry.countConfigReload();
  }

  public boolean isDebug(UUID uuid) {
    return debugPlayers.contains(uuid);
  }

  public void setDebug(UUID uuid, boolean debug) {
    if (debug) {
      debugPlayers.add(uuid);
    } else {
      debugPlayers.remove(uuid);
    }
  }

  public boolean toggleDebug(UUID uuid) {
    if (isDebug(uuid)) {
      setDebug(uuid, false);
      return false;
    } else {
      setDebug(uuid, true);
      return true;
    }
  }

  public boolean isAutoPickup() {
    return getRuntimeState().autoPickup();
  }

  public boolean suppressBlockDropOnCustomDrop() {
    return getRuntimeState().suppressBlockDrop();
  }

  public SeededVeinGenerator getVeinGenerator() {
    return getRuntimeState().veinGenerator();
  }

  public ChunkPositionSet getPlacedBlocks() {
    return placedBlocks;
  }

  public ChunkPositionSet getConsumedVeins() {
    return consumedVeins;
  }

  public HiddenOreAPI getApi() {
    return api;
  }

  public RuntimeState getRuntimeState() {
    RuntimeState current = runtimeState;
    if (current == null) {
      throw new IllegalStateException("HiddenOre runtime is not available");
    }
    return current;
  }

  public RuntimeState runtimeStateOrNull() {
    return runtimeState;
  }

  public GenerationRules getGenerationRules() {
    return generationRules;
  }

  public boolean isDraining() {
    return draining;
  }

  private String readYaml(File file, String name) {
    try {
      return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Failed to load HiddenOre configuration file '" + file.getAbsolutePath()
          + "' (" + name + "): " + exception.getMessage(), exception);
    }
  }

  private YamlConfiguration loadYaml(String content, File file, String name) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
      return configuration;
    } catch (InvalidConfigurationException exception) {
      throw new IllegalArgumentException("Failed to load HiddenOre configuration file '" + file.getAbsolutePath()
          + "' (" + name + "): " + exception.getMessage(), exception);
    }
  }

  private IllegalArgumentException invalidConfiguration(File file, IllegalArgumentException cause) {
    return new IllegalArgumentException("Invalid HiddenOre configuration file '" + file.getAbsolutePath()
        + "': " + cause.getMessage(), cause);
  }

  private boolean optionalBoolean(YamlConfiguration configuration, String path, boolean defaultValue) {
    Object value = configuration.get(path);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean)) {
      throw new IllegalArgumentException(path + ": expected true or false");
    }
    return (Boolean) value;
  }

  private ReloadNotification parseReloadNotification(YamlConfiguration configuration) {
    String soundName = optionalString(configuration, "config_reloaded_sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
    float volume = finiteFloat(configuration, "config_reloaded_sound_volume", 1.0f, 0.0f, Float.MAX_VALUE);
    float pitch = finiteFloat(configuration, "config_reloaded_sound_pitch", 1.6f, 0.5f, 2.0f);
    Sound sound = SoundResolver.resolve(soundName, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
    return new ReloadNotification(sound, volume, pitch);
  }

  private String optionalString(YamlConfiguration configuration, String path, String defaultValue) {
    Object value = configuration.get(path);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof String) || ((String) value).isBlank()) {
      throw new IllegalArgumentException(path + ": expected a non-empty string");
    }
    return (String) value;
  }

  private float finiteFloat(YamlConfiguration configuration, String path, float defaultValue, float minimum, float maximum) {
    Object value = configuration.get(path);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Number)) {
      throw new IllegalArgumentException(path + ": expected a finite number");
    }
    double number = ((Number) value).doubleValue();
    if (!Double.isFinite(number) || number < minimum || number > maximum) {
      throw new IllegalArgumentException(path + ": expected a value between " + minimum + " and " + maximum);
    }
    return (float) number;
  }

  private void log(Level level, String message, Object... args) {
    Logger logger = getLogger();
    if (logger.isLoggable(level)) {
      logger.log(level, format(message, args));
    }
  }

  private long claimLog(String key) {
    LogThrottle throttle = logThrottles.computeIfAbsent(key, ignored -> new LogThrottle());
    return throttle.claim(System.nanoTime());
  }

  private static String format(String message, Object... args) {
    return args.length > 0 ? String.format(message, args) : message;
  }

  private static String withSuppressed(String message, long suppressed) {
    return suppressed > 0L ? message + " (" + suppressed + " similar failures suppressed.)" : message;
  }

  public record RuntimeState(MiningRuleManager ruleManager,
                             Messages messages,
                             SeededVeinGenerator veinGenerator,
                             GenerationRules.GenerationPolicy generationPolicy,
                             ReloadNotification reloadNotification,
                             boolean autoPickup,
                             boolean suppressBlockDrop,
                             boolean metrics) {
  }

  public record ReloadNotification(Sound sound, float volume, float pitch) {
  }

  private record AppliedConfigSnapshot(String configYaml, String languageYaml) {
  }

  private static final class LogThrottle {
    private final AtomicLong nextLogAtNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong suppressed = new AtomicLong();

    private long claim(long nowNanos) {
      while (true) {
        long next = nextLogAtNanos.get();
        if (next != Long.MIN_VALUE && nowNanos - next < 0L) {
          suppressed.incrementAndGet();
          return -1L;
        }
        if (nextLogAtNanos.compareAndSet(next, nowNanos + LOG_THROTTLE_NANOS)) {
          return suppressed.getAndSet(0L);
        }
      }
    }
  }
}
