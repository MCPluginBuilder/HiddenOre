package art.arcane.hiddenore.util.project;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ConfigWatcherBaselineTest {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void startupEditAfterAppliedSnapshotRemainsDetectable() throws Exception {
    Path directory = temporaryFolder.newFolder("hiddenore-startup").toPath();
    String appliedConfig = "auto_pickup_drops: false\n";
    String appliedLanguage = "config_reloaded_message: old\n";
    Files.writeString(directory.resolve("hiddenore.yml"), appliedConfig, StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("language.yml"), appliedLanguage, StandardCharsets.UTF_8);
    Map<String, String> baseline = ConfigWatcher.appliedSignatures(appliedConfig, appliedLanguage);

    assertEquals(baseline, ConfigWatcher.diskSignatures(directory));
    Files.writeString(directory.resolve("hiddenore.yml"), "auto_pickup_drops: true\n", StandardCharsets.UTF_8);

    assertNotEquals(baseline, ConfigWatcher.diskSignatures(directory));
  }

  @Test
  public void manualResetUsesParsedLanguageInsteadOfNewerDiskState() throws Exception {
    Path directory = temporaryFolder.newFolder("hiddenore-manual").toPath();
    String appliedConfig = "auto_pickup_drops: false\n";
    String appliedLanguage = "config_reloaded_message: applied\n";
    Map<String, String> baseline = ConfigWatcher.appliedSignatures(appliedConfig, appliedLanguage);
    Files.writeString(directory.resolve("hiddenore.yml"), appliedConfig, StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("language.yml"), "config_reloaded_message: newer\n", StandardCharsets.UTF_8);

    assertNotEquals(baseline, ConfigWatcher.diskSignatures(directory));
  }

  @Test
  public void sameMetadataEditChangesExactDiskSignature() throws Exception {
    Path directory = temporaryFolder.newFolder("hiddenore-same-metadata").toPath();
    Path configFile = directory.resolve("hiddenore.yml");
    String appliedConfig = "enabled: false\n";
    String changedConfig = "enabled: true \n";
    String language = "config_reloaded_message: stable\n";
    Files.writeString(configFile, appliedConfig, StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("language.yml"), language, StandardCharsets.UTF_8);
    FileTime originalModified = Files.getLastModifiedTime(configFile);
    Map<String, String> baseline = ConfigWatcher.diskSignatures(directory);

    assertEquals(appliedConfig.length(), changedConfig.length());
    Files.writeString(configFile, changedConfig, StandardCharsets.UTF_8);
    Files.setLastModifiedTime(configFile, originalModified);

    assertNotEquals(baseline, ConfigWatcher.diskSignatures(directory));
  }

  @Test
  public void idleReconciliationHashesOnlyAtTwoAndAHalfSecondDeadline() {
    ConfigWatcher.SignatureReconciliation reconciliation = new ConfigWatcher.SignatureReconciliation(
        TimeUnit.MILLISECONDS.toNanos(2_500L)
    );
    AtomicInteger hashPasses = new AtomicInteger();
    reconciliation.reset(0L);

    assertFalse(reconciliation.reconcileIfDue(TimeUnit.SECONDS.toNanos(1L), () -> {
      hashPasses.incrementAndGet();
      return true;
    }));
    assertFalse(reconciliation.reconcileIfDue(TimeUnit.MILLISECONDS.toNanos(2_499L), () -> {
      hashPasses.incrementAndGet();
      return true;
    }));
    assertEquals(0, hashPasses.get());

    assertTrue(reconciliation.reconcileIfDue(TimeUnit.MILLISECONDS.toNanos(2_500L), () -> {
      hashPasses.incrementAndGet();
      return true;
    }));
    assertEquals(1, hashPasses.get());
    assertEquals(
        TimeUnit.SECONDS.toNanos(1L),
        reconciliation.pollTimeoutNanos(TimeUnit.SECONDS.toNanos(3L), TimeUnit.SECONDS.toNanos(1L))
    );
  }

  @Test
  public void deadlineLockIsReleasedBeforeContentHashing() throws Exception {
    ConfigWatcher.SignatureReconciliation reconciliation = new ConfigWatcher.SignatureReconciliation(1L);
    CountDownLatch hashing = new CountDownLatch(1);
    CountDownLatch releaseHashing = new CountDownLatch(1);
    CountDownLatch resetFinished = new CountDownLatch(1);
    reconciliation.reset(0L);
    Thread hashingThread = new Thread(() -> reconciliation.reconcileIfDue(1L, () -> {
      hashing.countDown();
      try {
        releaseHashing.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      return false;
    }));
    hashingThread.start();
    assertTrue(hashing.await(1L, TimeUnit.SECONDS));

    Thread resetThread = new Thread(() -> {
      reconciliation.reset(2L);
      resetFinished.countDown();
    });
    resetThread.start();
    try {
      assertTrue(resetFinished.await(1L, TimeUnit.SECONDS));
    } finally {
      releaseHashing.countDown();
      hashingThread.join(1_000L);
      resetThread.join(1_000L);
    }
  }
}
