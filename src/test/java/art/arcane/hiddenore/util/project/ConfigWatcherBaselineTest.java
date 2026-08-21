package art.arcane.hiddenore.util.project;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class ConfigWatcherBaselineTest {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void startupEditAfterAppliedSnapshotRemainsDetectable() throws Exception {
    Path directory = temporaryFolder.newFolder("hiddenore-startup").toPath();
    String appliedConfig = "auto_pickup_drops: false\n";
    String appliedLanguage = "config_reloaded_message: old\n";
    Files.writeString(directory.resolve("config.yml"), appliedConfig, StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("language.yml"), appliedLanguage, StandardCharsets.UTF_8);
    Map<String, String> baseline = ConfigWatcher.appliedSignatures(appliedConfig, appliedLanguage);

    assertEquals(baseline, ConfigWatcher.diskSignatures(directory));
    Files.writeString(directory.resolve("config.yml"), "auto_pickup_drops: true\n", StandardCharsets.UTF_8);

    assertNotEquals(baseline, ConfigWatcher.diskSignatures(directory));
  }

  @Test
  public void manualResetUsesParsedLanguageInsteadOfNewerDiskState() throws Exception {
    Path directory = temporaryFolder.newFolder("hiddenore-manual").toPath();
    String appliedConfig = "auto_pickup_drops: false\n";
    String appliedLanguage = "config_reloaded_message: applied\n";
    Map<String, String> baseline = ConfigWatcher.appliedSignatures(appliedConfig, appliedLanguage);
    Files.writeString(directory.resolve("config.yml"), appliedConfig, StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("language.yml"), "config_reloaded_message: newer\n", StandardCharsets.UTF_8);

    assertNotEquals(baseline, ConfigWatcher.diskSignatures(directory));
  }
}
