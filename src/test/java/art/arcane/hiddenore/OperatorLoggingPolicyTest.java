package art.arcane.hiddenore;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OperatorLoggingPolicyTest {
  private static final Path MAIN_SOURCE = Path.of("src/main/java");

  @Test
  public void productionSourcesDoNotBypassTheHiddenOreLogger() throws IOException {
    List<Path> sources = javaSources();
    int rawConsoleMessages = 0;
    for (Path source : sources) {
      String text = Files.readString(source);
      assertFalse(source.toString(), text.contains("System.out"));
      assertFalse(source.toString(), text.contains("System.err"));
      assertFalse(source.toString(), text.contains("printStackTrace("));
      assertFalse(source.toString(), text.contains("Bukkit.getLogger("));
      assertFalse(source.toString(), text.contains("getServer().getLogger("));
      assertFalse(source.toString(), text.contains("Logger.getLogger("));
      assertFalse(source.toString(), hasDirectPluginLogCall(text));
      int index = text.indexOf("Bukkit.getConsoleSender().sendMessage(");
      while (index >= 0) {
        rawConsoleMessages++;
        assertTrue(source.toString(),
            source.endsWith(Path.of("art/arcane/hiddenore/util/common/SplashScreen.java")));
        index = text.indexOf("Bukkit.getConsoleSender().sendMessage(", index + 1);
      }
    }
    assertEquals(1, rawConsoleMessages);
  }

  @Test
  public void routineMiningAndIntegrationMessagesStayOutOfInfoLogs() throws IOException {
    String mining = Files.readString(MAIN_SOURCE.resolve("art/arcane/hiddenore/listeners/MiningListener.java"));
    String integration = Files.readString(
        MAIN_SOURCE.resolve("art/arcane/hiddenore/service/HiddenOreIntegrationService.java"));

    assertFalse(mining.contains("skipped \" + salvage.skippedPlayerGroups()"));
    assertTrue(mining.contains("plugin.warnThrottled("));
    assertTrue(integration.contains("plugin.debug("));
    assertFalse(integration.contains("plugin.info("));
  }

  private static List<Path> javaSources() throws IOException {
    try (Stream<Path> paths = Files.walk(MAIN_SOURCE)) {
      return paths.filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".java"))
          .sorted()
          .toList();
    }
  }

  private static boolean hasDirectPluginLogCall(String source) {
    int index = source.indexOf("getLogger().");
    while (index >= 0) {
      int methodStart = index + "getLogger().".length();
      if (source.startsWith("info(", methodStart)
          || source.startsWith("warning(", methodStart)
          || source.startsWith("severe(", methodStart)
          || source.startsWith("fine(", methodStart)
          || source.startsWith("log(", methodStart)) {
        return true;
      }
      index = source.indexOf("getLogger().", methodStart);
    }
    return false;
  }
}
