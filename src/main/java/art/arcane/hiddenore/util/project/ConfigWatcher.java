package art.arcane.hiddenore.util.project;

import art.arcane.hiddenore.HiddenOre;
import art.arcane.hiddenore.util.common.Messages;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

public final class ConfigWatcher implements Runnable {
  private static final long RELOAD_DEBOUNCE_MILLIS = 250L;
  private static final long RELOAD_COOLDOWN_MILLIS = 3_000L;
  private static final long SIGNATURE_RECONCILIATION_MILLIS = 2_500L;
  private static final long POLL_TIMEOUT_MILLIS = 1_000L;
  private static final long WATCHER_RETRY_MILLIS = 1_000L;
  private static final long WATCHER_FAILURE_LOG_INTERVAL_MILLIS = 30_000L;
  private static final long MAX_CONFIG_BYTES = 8L * 1024L * 1024L;

  private final HiddenOre plugin;
  private final Set<String> watchedFiles;
  private final Path dir;
  private final Map<String, String> lastSignatures = new HashMap<>();
  private final Set<String> oversizedWarnings = new HashSet<>();
  private final Object reloadQueueLock = new Object();
  private final SignatureReconciliation signatureReconciliation = new SignatureReconciliation(
      TimeUnit.MILLISECONDS.toNanos(SIGNATURE_RECONCILIATION_MILLIS)
  );
  private volatile boolean running;
  private volatile Thread thread;
  private volatile WatchService watchService;
  private boolean reloadPending;
  private boolean reloadScheduled;
  private ReloadSnapshot pendingReload;
  private ReloadSnapshot scheduledReload;
  private boolean reloadCompleted;
  private long lastReloadCompletedAtNanos;
  private long lastWatcherFailureLogAtNanos;
  private volatile long reloadGeneration;
  private long scheduledReloadGeneration;

  public ConfigWatcher(HiddenOre plugin) {
    this.plugin = plugin;
    this.dir = plugin.getDataFolder().toPath();
    this.watchedFiles = Set.of("hiddenore.yml", "language.yml");
  }

  public synchronized void startWithAppliedSnapshot(String configYaml, String languageYaml) {
    if (thread != null && thread.isAlive()) {
      return;
    }

    Map<String, String> appliedSignatures = appliedSignatures(configYaml, languageYaml);
    running = true;
    signatureReconciliation.reset(System.nanoTime());
    synchronized (reloadQueueLock) {
      reloadPending = false;
      reloadScheduled = false;
      pendingReload = null;
      scheduledReload = null;
      reloadCompleted = false;
      lastReloadCompletedAtNanos = 0L;
      reloadGeneration++;
      scheduledReloadGeneration = reloadGeneration;
      lastSignatures.clear();
      lastSignatures.putAll(appliedSignatures);
    }
    Thread watcherThread = new Thread(this, "HiddenOre-ConfigWatcher");
    watcherThread.setDaemon(true);
    thread = watcherThread;
    watcherThread.start();
  }

  public synchronized void stop() {
    running = false;
    synchronized (reloadQueueLock) {
      reloadPending = false;
      pendingReload = null;
      scheduledReload = null;
      reloadGeneration++;
    }

    WatchService watcher = watchService;
    if (watcher != null) {
      try {
        watcher.close();
      } catch (IOException exception) {
        plugin.logException(Level.WARNING, exception, "Failed to close the HiddenOre config watcher.");
      }
    }

    Thread watcherThread = thread;
    if (watcherThread != null) {
      watcherThread.interrupt();
      if (watcherThread != Thread.currentThread()) {
        try {
          watcherThread.join(1000L);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          plugin.logException(Level.WARNING, exception,
              "Interrupted while stopping the HiddenOre config watcher.");
        }
        if (watcherThread.isAlive()) {
          plugin.warn("HiddenOre config watcher did not stop within one second.");
        }
      }
    }
  }

  @Override
  public void run() {
    try {
      while (running && plugin.isEnabled() && !Thread.currentThread().isInterrupted()) {
        try {
          watchDirectory();
        } catch (ClosedWatchServiceException exception) {
          if (running) {
            reportWatcherFailure("HiddenOre config watcher closed unexpectedly; retrying", exception);
          }
        } catch (IOException exception) {
          if (running) {
            reportWatcherFailure("HiddenOre config watcher failed; retrying", exception);
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          if (running) {
            plugin.logException(Level.WARNING, exception,
                "HiddenOre config watcher was interrupted unexpectedly.");
          }
          break;
        } finally {
          watchService = null;
        }

        if (!running || !plugin.isEnabled() || Thread.currentThread().isInterrupted()) {
          break;
        }
        try {
          reconcileWithoutEvents();
        } catch (IOException exception) {
          reportWatcherFailure("HiddenOre config reconciliation failed; retrying", exception);
        }
        long retryNanos = signatureReconciliation.pollTimeoutNanos(
            System.nanoTime(),
            TimeUnit.MILLISECONDS.toNanos(WATCHER_RETRY_MILLIS)
        );
        TimeUnit.NANOSECONDS.sleep(retryNanos);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } finally {
      watchService = null;
      thread = null;
      running = false;
    }
  }

  private void watchDirectory() throws IOException, InterruptedException {
    try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
      watchService = watcher;
      dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
          StandardWatchEventKinds.ENTRY_DELETE);
      while (running && plugin.isEnabled()) {
        long pollNanos = signatureReconciliation.pollTimeoutNanos(
            System.nanoTime(),
            TimeUnit.MILLISECONDS.toNanos(POLL_TIMEOUT_MILLIS)
        );
        WatchKey key = watcher.poll(pollNanos, TimeUnit.NANOSECONDS);
        boolean shouldReload = key != null && containsWatchedChange(key);
        if (key != null && !key.reset()) {
          throw new IOException("HiddenOre config watcher key became invalid for " + dir);
        }
        long now = System.nanoTime();
        if (shouldReload) {
          signatureReconciliation.reset(now);
        } else {
          shouldReload = signatureReconciliation.reconcileIfDue(now, this::signaturesChanged);
        }
        if (shouldReload) {
          long generation = currentReloadGeneration();
          Map<String, String> stableSignatures = awaitQuietPeriod(watcher);
          if (running && plugin.isEnabled()) {
            ReloadSnapshot snapshot = captureReloadSnapshot(stableSignatures);
            if (snapshot != null) {
              acceptReloadSnapshot(stableSignatures, snapshot, generation);
            }
          }
        }
        schedulePendingReload();
      }
    }
  }

  private void reconcileWithoutEvents() throws InterruptedException, IOException {
    if (!signatureReconciliation.reconcileIfDue(System.nanoTime(), this::signaturesChanged)) {
      schedulePendingReload();
      return;
    }
    long generation = currentReloadGeneration();
    Map<String, String> stableSignatures = awaitQuietPeriod(null);
    ReloadSnapshot snapshot = captureReloadSnapshot(stableSignatures);
    if (snapshot == null) {
      return;
    }
    acceptReloadSnapshot(stableSignatures, snapshot, generation);
  }

  private void reloadAndNotifyOps() {
    ReloadSnapshot snapshot;
    long generation;
    synchronized (reloadQueueLock) {
      snapshot = scheduledReload;
      generation = scheduledReloadGeneration;
    }
    boolean applied = false;
    try {
      if (snapshot == null || generation != reloadGeneration || !running || plugin.isDraining()
          || !plugin.isEnabled()) {
        return;
      }

      synchronized (plugin) {
        if (generation != reloadGeneration || !running || plugin.isDraining() || !plugin.isEnabled()) {
          return;
        }
        try {
          plugin.reloadAll(snapshot.configYaml(), snapshot.languageYaml());
        } catch (RuntimeException exception) {
          plugin.logException(Level.SEVERE, exception,
              "Config reload failed; the previous runtime configuration remains active.");
          return;
        }
      }
      applied = true;

      HiddenOre.RuntimeState runtime = plugin.getRuntimeState();
      HiddenOre.ReloadNotification notification = runtime.reloadNotification();
      Component message = runtime.messages().component(Messages.CONFIG_RELOADED_MESSAGE);
      for (Player player : Bukkit.getOnlinePlayers()) {
        if (!SchedulerUtils.runEntity(plugin, player, () -> notifyOperator(player, message, notification.sound(),
            notification.volume(), notification.pitch()))) {
          plugin.warnThrottled("config-reload-notification-scheduling",
              "Failed to schedule a config reload notification for %s.", player.getName());
        }
      }
    } finally {
      synchronized (reloadQueueLock) {
        if (!applied && generation == reloadGeneration && pendingReload == null && snapshot != null) {
          pendingReload = snapshot;
          reloadPending = true;
        }
        scheduledReload = null;
        reloadScheduled = false;
        reloadCompleted = true;
        lastReloadCompletedAtNanos = System.nanoTime();
      }
      schedulePendingReload();
    }
  }

  private void acceptReloadSnapshot(Map<String, String> signatures, ReloadSnapshot snapshot, long generation) {
    synchronized (reloadQueueLock) {
      if (generation != reloadGeneration) {
        return;
      }
      lastSignatures.clear();
      lastSignatures.putAll(signatures);
      pendingReload = snapshot;
      reloadPending = true;
    }
    schedulePendingReload();
  }

  private void schedulePendingReload() {
    long now = System.nanoTime();
    synchronized (reloadQueueLock) {
      if (!running || !reloadPending || pendingReload == null || reloadScheduled) {
        return;
      }
      if (reloadCompleted
          && now - lastReloadCompletedAtNanos < TimeUnit.MILLISECONDS.toNanos(RELOAD_COOLDOWN_MILLIS)) {
        return;
      }
      reloadPending = false;
      reloadScheduled = true;
      scheduledReload = pendingReload;
      scheduledReloadGeneration = reloadGeneration;
      pendingReload = null;
    }

    if (!SchedulerUtils.runGlobal(plugin, this::reloadAndNotifyOps)) {
      synchronized (reloadQueueLock) {
        if (pendingReload == null) {
          pendingReload = scheduledReload;
        }
        scheduledReload = null;
        reloadPending = pendingReload != null;
        reloadScheduled = false;
      }
      plugin.warnThrottled("config-reload-scheduling",
          "Failed to schedule config reload; the latest edit remains queued.");
    }
  }

  private boolean requiredFilesPresent() {
    boolean present = true;
    for (String name : watchedFiles) {
      Path file = dir.resolve(name);
      if (!Files.isRegularFile(file)) {
        present = false;
        continue;
      }
      try {
        if (Files.size(file) <= MAX_CONFIG_BYTES) {
          oversizedWarnings.remove(name);
          continue;
        }
        present = false;
        if (oversizedWarnings.add(name)) {
          plugin.warn("HiddenOre config hotload is waiting because %s exceeds %d bytes.",
              name, MAX_CONFIG_BYTES);
        }
      } catch (IOException exception) {
        present = false;
        reportWatcherFailure("Unable to inspect HiddenOre hotload target " + file, exception);
      }
    }
    return present;
  }

  private boolean signaturesChanged() {
    Map<String, String> previous;
    synchronized (reloadQueueLock) {
      previous = Map.copyOf(lastSignatures);
    }
    return !previous.equals(currentSignatures());
  }

  private Map<String, String> currentSignatures() {
    return diskSignatures(dir);
  }

  private ReloadSnapshot captureReloadSnapshot(Map<String, String> expectedSignatures) throws IOException {
    if (!requiredFilesPresent()) {
      return null;
    }
    String configYaml = readBoundedUtf8(dir.resolve("hiddenore.yml"));
    String languageYaml = readBoundedUtf8(dir.resolve("language.yml"));
    if (!expectedSignatures.equals(currentSignatures())) {
      return null;
    }
    return new ReloadSnapshot(configYaml, languageYaml, Map.copyOf(expectedSignatures));
  }

  public void resetAfterManualReload(String configYaml, String languageYaml) {
    Map<String, String> signatures = appliedSignatures(configYaml, languageYaml);
    signatureReconciliation.reset(System.nanoTime());
    synchronized (reloadQueueLock) {
      reloadGeneration++;
      reloadPending = false;
      reloadScheduled = false;
      pendingReload = null;
      scheduledReload = null;
      reloadCompleted = true;
      lastReloadCompletedAtNanos = System.nanoTime();
      lastSignatures.clear();
      lastSignatures.putAll(signatures);
    }
  }

  private String readBoundedUtf8(Path file) throws IOException {
    try (InputStream input = Files.newInputStream(file)) {
      byte[] content = input.readNBytes((int) MAX_CONFIG_BYTES + 1);
      if (content.length > MAX_CONFIG_BYTES) {
        throw new IOException(file.getFileName() + " exceeds " + MAX_CONFIG_BYTES + " bytes");
      }
      return new String(content, StandardCharsets.UTF_8);
    }
  }

  private static String signature(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return "missing";
    }
    try {
      BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
      if (before.size() > MAX_CONFIG_BYTES) {
        return "oversized:" + attributesSignature(before);
      }
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      long size = 0L;
      try (InputStream input = Files.newInputStream(file)) {
        int read;
        while ((read = input.read(buffer)) >= 0) {
          size += read;
          if (size > MAX_CONFIG_BYTES) {
            return "oversized:" + attributesSignature(before);
          }
          digest.update(buffer, 0, read);
        }
      }
      BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
      if (!attributesSignature(before).equals(attributesSignature(after)) || size != after.size()) {
        return "changing:" + attributesSignature(after);
      }
      return contentSignature(size, HexFormat.of().formatHex(digest.digest()));
    } catch (IOException exception) {
      return "unreadable";
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String attributesSignature(BasicFileAttributes attributes) {
    return attributes.lastModifiedTime().toMillis() + ":" + attributes.size() + ":" + attributes.fileKey();
  }

  private long currentReloadGeneration() {
    synchronized (reloadQueueLock) {
      return reloadGeneration;
    }
  }

  static Map<String, String> appliedSignatures(String configYaml, String languageYaml) {
    Map<String, String> signatures = new HashMap<>();
    signatures.put("hiddenore.yml", contentSignature(configYaml));
    signatures.put("language.yml", contentSignature(languageYaml));
    return Map.copyOf(signatures);
  }

  static Map<String, String> diskSignatures(Path directory) {
    Map<String, String> signatures = new HashMap<>();
    signatures.put("hiddenore.yml", signature(directory.resolve("hiddenore.yml")));
    signatures.put("language.yml", signature(directory.resolve("language.yml")));
    return Map.copyOf(signatures);
  }

  private static String contentSignature(String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return contentSignature(bytes.length, HexFormat.of().formatHex(digest.digest(bytes)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String contentSignature(long size, String digest) {
    return "content:" + size + ":" + digest;
  }

  private boolean containsWatchedChange(WatchKey key) {
    boolean watched = false;
    for (WatchEvent<?> event : key.pollEvents()) {
      if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
        watched = true;
        continue;
      }
      if (!(event.context() instanceof Path changed)) {
        continue;
      }
      if (watchedFiles.contains(changed.getFileName().toString())) {
        watched = true;
      }
    }
    return watched;
  }

  private Map<String, String> awaitQuietPeriod(WatchService watcher) throws InterruptedException, IOException {
    Map<String, String> candidate = currentSignatures();
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RELOAD_DEBOUNCE_MILLIS);
    while (running) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0L) {
        return candidate;
      }
      long sampleWindow = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50L));
      WatchKey key = watcher == null ? null : watcher.poll(sampleWindow, TimeUnit.NANOSECONDS);
      if (watcher == null) {
        TimeUnit.NANOSECONDS.sleep(sampleWindow);
      }
      if (key != null && containsWatchedChange(key)) {
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RELOAD_DEBOUNCE_MILLIS);
      }
      if (key != null && !key.reset()) {
        throw new IOException("HiddenOre config watcher key became invalid for " + dir);
      }
      Map<String, String> sampled = currentSignatures();
      if (!sampled.equals(candidate)) {
        candidate = sampled;
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RELOAD_DEBOUNCE_MILLIS);
      }
    }
    return candidate;
  }

  private void reportWatcherFailure(String message, Throwable failure) {
    long now = System.nanoTime();
    if (lastWatcherFailureLogAtNanos != 0L
        && now - lastWatcherFailureLogAtNanos < TimeUnit.MILLISECONDS.toNanos(WATCHER_FAILURE_LOG_INTERVAL_MILLIS)) {
      return;
    }
    lastWatcherFailureLogAtNanos = now;
    plugin.logException(Level.WARNING, failure, "%s.", message);
  }

  private void notifyOperator(Player player, Component message, Sound sound, float volume, float pitch) {
    if (!player.isOp()) {
      return;
    }
    HiddenOre.sendMessage(player, message);
    player.playSound(player.getLocation(), sound, volume, pitch);
  }

  private record ReloadSnapshot(String configYaml, String languageYaml, Map<String, String> signatures) {
  }

  static final class SignatureReconciliation {
    private final long intervalNanos;
    private long nextReconciliationNanos = Long.MIN_VALUE;

    SignatureReconciliation(long intervalNanos) {
      this.intervalNanos = Math.max(1L, intervalNanos);
    }

    synchronized void reset(long nowNanos) {
      nextReconciliationNanos = saturatingAdd(nowNanos, intervalNanos);
    }

    boolean reconcileIfDue(long nowNanos, BooleanSupplier reconciliation) {
      synchronized (this) {
        if (nextReconciliationNanos == Long.MIN_VALUE) {
          nextReconciliationNanos = saturatingAdd(nowNanos, intervalNanos);
          return false;
        }
        if (nowNanos < nextReconciliationNanos) {
          return false;
        }
        nextReconciliationNanos = saturatingAdd(nowNanos, intervalNanos);
      }
      return reconciliation.getAsBoolean();
    }

    synchronized long pollTimeoutNanos(long nowNanos, long maximumNanos) {
      long safeMaximum = Math.max(1L, maximumNanos);
      if (nextReconciliationNanos == Long.MIN_VALUE) {
        return safeMaximum;
      }
      if (nowNanos >= nextReconciliationNanos) {
        return 1L;
      }
      long remaining = nextReconciliationNanos - nowNanos;
      if (remaining <= 0L) {
        return safeMaximum;
      }
      return Math.min(safeMaximum, remaining);
    }

    private static long saturatingAdd(long left, long right) {
      if (right > 0L && left > Long.MAX_VALUE - right) {
        return Long.MAX_VALUE;
      }
      return left + right;
    }
  }
}
