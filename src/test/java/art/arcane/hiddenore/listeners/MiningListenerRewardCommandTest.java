package art.arcane.hiddenore.listeners;

import art.arcane.hiddenore.testing.PlaceholderApiSandbox;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class MiningListenerRewardCommandTest {
  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000cd02");

  @Test
  public void builtInPass_substitutesEveryDocumentedToken() {
    String resolved = MiningListener.applyBuiltInPlaceholders(
        "give %player% diamond %uuid% %world% %x% %y% %z%", "Psycho", PLAYER.toString(), -12, 64, 300, "world_nether");

    assertEquals("give Psycho diamond " + PLAYER + " world_nether -12 64 300", resolved);
  }

  @Test
  public void builtInPass_leavesForeignPlaceholdersIntactForPlaceholderApi() {
    String resolved = MiningListener.applyBuiltInPlaceholders(
        "eco give %player% %vault_eco_balance%", "Psycho", PLAYER.toString(), 0, 0, 0, "world");

    assertEquals("eco give Psycho %vault_eco_balance%", resolved);
  }

  @Test
  public void builtInPass_treatsMissingCommandAndMissingWorldAsEmptyText() {
    assertEquals("", MiningListener.applyBuiltInPlaceholders(null, "Psycho", PLAYER.toString(), 0, 0, 0, "world"));
    assertEquals("say ", MiningListener.applyBuiltInPlaceholders("say %world%", "Psycho", PLAYER.toString(), 0, 0, 0, ""));
  }

  @Test
  public void rewardCommandsAreResolvedByPlaceholderApiAfterTheBuiltInPass() throws Exception {
    try (PlaceholderApiSandbox sandbox = PlaceholderApiSandbox.open()) {
      sandbox.answer("%vault_eco_balance%", "1234.56");
      Object player = sandbox.player(PLAYER, "Psycho");
      Object location = sandbox.location("world_nether", -12, 64, 300);

      String resolved = applyCommandPlaceholders(sandbox, "eco give %player% %vault_eco_balance% %x% %y% %z% %world%",
          player, location);

      assertEquals("the value PlaceholderAPI returned must be the value the reward command dispatches",
          "eco give Psycho 1234.56 -12 64 300 world_nether", resolved);
      assertEquals("PlaceholderAPI must receive the text after the built-in pass, not the raw command",
          List.of("eco give Psycho %vault_eco_balance% -12 64 300 world_nether"),
          sandbox.textsHandedToPlaceholderApi());
      assertSame("the breaking player must be the player PlaceholderAPI resolves against",
          player, sandbox.playersHandedToPlaceholderApi().getFirst());
    }
  }

  @Test
  public void rewardCommandsWithoutForeignPlaceholdersStillSurviveTheBuiltInPass() throws Exception {
    try (PlaceholderApiSandbox sandbox = PlaceholderApiSandbox.open()) {
      sandbox.answer("%vault_eco_balance%", "1234.56");
      Object player = sandbox.player(PLAYER, "Psycho");
      Object location = sandbox.location("world", 1, 2, 3);

      assertEquals("give Psycho diamond", applyCommandPlaceholders(sandbox, "give %player% diamond", player, location));
    }
  }

  @Test
  public void aMissingWorldDoesNotStopThePlaceholderApiHandOff() throws Exception {
    try (PlaceholderApiSandbox sandbox = PlaceholderApiSandbox.open()) {
      sandbox.answer("%vault_eco_balance%", "0.00");
      Object player = sandbox.player(PLAYER, "Psycho");
      Object location = sandbox.location(null, 0, 0, 0);

      assertEquals("eco give Psycho 0.00 ",
          applyCommandPlaceholders(sandbox, "eco give %player% %vault_eco_balance% %world%", player, location));
    }
  }

  private static String applyCommandPlaceholders(PlaceholderApiSandbox sandbox, String raw, Object player,
                                                 Object location) throws Exception {
    Method apply = sandbox.load("art.arcane.hiddenore.listeners.MiningListener")
        .getDeclaredMethod("applyCommandPlaceholders", String.class, sandbox.load("org.bukkit.entity.Player"),
            sandbox.load("org.bukkit.Location"));
    apply.setAccessible(true);
    return (String) apply.invoke(null, raw, player, location);
  }
}
