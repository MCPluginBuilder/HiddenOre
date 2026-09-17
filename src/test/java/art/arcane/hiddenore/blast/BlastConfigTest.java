package art.arcane.hiddenore.blast;

import art.arcane.hiddenore.util.project.ToolTier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BlastConfigTest {
  private static final Gson JSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();

  @Test
  public void parse_missingSection_isDisabledWithSafeDefaults() {
    BlastConfig config = BlastConfig.parse(null);

    assertFalse(config.enabled);
    assertEquals(0.5, config.yieldChance, 0.0);
    assertEquals(ToolTier.IRON_PICKAXE, config.toolTier);
    assertEquals(Set.of(BlastSource.TNT, BlastSource.MINECART_TNT), config.sources);
  }

  @Test
  public void parse_emptySection_isDisabledWithSafeDefaults() {
    BlastConfig config = BlastConfig.parse(new JsonObject());

    assertFalse(config.enabled);
    assertEquals(0.5, config.yieldChance, 0.0);
    assertEquals(ToolTier.IRON_PICKAXE, config.toolTier);
    assertEquals(Set.of(BlastSource.TNT, BlastSource.MINECART_TNT), config.sources);
  }

  @Test
  public void parse_readsEveryKnob() {
    BlastConfig config = BlastConfig.parse(section(Map.of(
        "enabled", true,
        "yield", 0.25,
        "tool_tier", "netherite_pickaxe",
        "sources", List.of("TNT", "creeper"))));

    assertTrue(config.enabled);
    assertEquals(0.25, config.yieldChance, 0.0);
    assertEquals(ToolTier.NETHERITE_PICKAXE, config.toolTier);
    assertEquals(Set.of(BlastSource.TNT, BlastSource.CREEPER), config.sources);
  }

  @Test
  public void allows_requiresBothTheEnabledFlagAndAListedSource() {
    BlastConfig enabled = BlastConfig.parse(section(Map.of(
        "enabled", true, "sources", List.of("TNT"))));
    BlastConfig disabled = BlastConfig.parse(section(Map.of(
        "enabled", false, "sources", List.of("TNT"))));

    assertTrue(enabled.allows(BlastSource.TNT));
    assertFalse(enabled.allows(BlastSource.CREEPER));
    assertFalse(enabled.allows(BlastSource.OTHER));
    assertFalse(disabled.allows(BlastSource.TNT));
  }

  @Test
  public void parse_rejectsASectionThatIsNotATable() {
    assertInvalid("blast_mining: expected a table", JSON.toJsonTree(5));
    assertInvalid("blast_mining: expected a table", JSON.toJsonTree("on"));
    assertInvalid("blast_mining: expected a table", JSON.toJsonTree(List.of("TNT")));
  }

  @Test
  public void parse_rejectsANonBooleanEnabledFlag() {
    assertInvalid("blast_mining.enabled: expected true or false", section(Map.of("enabled", "yes")));
    assertInvalid("blast_mining.enabled: expected true or false", section(Map.of("enabled", 1)));
  }

  @Test
  public void parse_rejectsYieldOutsideTheUnitRange() {
    String expected = "blast_mining.yield: must be a finite number between 0 and 1 inclusive";
    assertInvalid(expected, section(Map.of("yield", -0.0001)));
    assertInvalid(expected, section(Map.of("yield", 1.0001)));
    assertInvalid(expected, section(Map.of("yield", Double.NaN)));
    assertInvalid(expected, section(Map.of("yield", Double.POSITIVE_INFINITY)));
    assertInvalid(expected, section(Map.of("yield", "half")));
    assertInvalid(expected, section(Map.of("yield", true)));
  }

  @Test
  public void parse_acceptsBothUnitRangeBounds() {
    assertEquals(0.0, BlastConfig.parse(section(Map.of("yield", 0))).yieldChance, 0.0);
    assertEquals(1.0, BlastConfig.parse(section(Map.of("yield", 1))).yieldChance, 0.0);
  }

  @Test
  public void parse_rejectsAToolTierThatIsNotAPickaxe() {
    assertInvalid("blast_mining.tool_tier: unknown tool tier 'wooden_shovel'; expected one of WOODEN_PICKAXE, "
            + "STONE_PICKAXE, COPPER_PICKAXE, IRON_PICKAXE, GOLDEN_PICKAXE, DIAMOND_PICKAXE, NETHERITE_PICKAXE",
        section(Map.of("tool_tier", "wooden_shovel")));
    assertInvalid("blast_mining.tool_tier: expected a pickaxe tier name",
        section(Map.of("tool_tier", 3)));
    assertInvalid("blast_mining.tool_tier: expected a pickaxe tier name",
        section(Map.of("tool_tier", " ")));
  }

  @Test
  public void parse_rejectsSourceListsThatCannotBeApplied() {
    assertInvalid("blast_mining.sources: expected a list of explosion source names",
        section(Map.of("sources", "TNT")));
    assertInvalid("blast_mining.sources[1]: unknown explosion source 'banana'; expected one of "
            + "TNT, MINECART_TNT, CREEPER, END_CRYSTAL, FIREBALL, WITHER, ENDER_DRAGON, BED, RESPAWN_ANCHOR",
        section(Map.of("sources", List.of("TNT", "banana"))));
    assertInvalid("blast_mining.sources[0]: expected an explosion source name",
        section(Map.of("sources", List.of(7))));
    assertInvalid("blast_mining.sources[0]: expected an explosion source name",
        section(Map.of("sources", List.of("  "))));
  }

  @Test
  public void parse_emptySourceList_isRejectedOnlyWhileBlastMiningIsEnabled() {
    LinkedHashMap<String, Object> enabled = new LinkedHashMap<>();
    enabled.put("enabled", true);
    enabled.put("sources", List.of());
    assertInvalid("blast_mining.sources: expected at least one explosion source while blast mining is enabled",
        section(enabled));

    LinkedHashMap<String, Object> disabled = new LinkedHashMap<>();
    disabled.put("enabled", false);
    disabled.put("sources", List.of());
    BlastConfig config = BlastConfig.parse(section(disabled));

    assertEquals(Set.of(), config.sources);
    assertFalse(config.allows(BlastSource.TNT));
  }

  @Test
  public void parse_ignoresDuplicateSources() {
    BlastConfig config = BlastConfig.parse(section(Map.of(
        "sources", List.of("TNT", "tnt", "MINECART_TNT"))));

    assertEquals(Set.of(BlastSource.TNT, BlastSource.MINECART_TNT), config.sources);
  }

  private static JsonObject section(Map<String, Object> values) {
    return JSON.toJsonTree(new LinkedHashMap<>(values)).getAsJsonObject();
  }

  private static void assertInvalid(String expectedMessage, JsonElement section) {
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> BlastConfig.parse(section));
    assertEquals(expectedMessage, failure.getMessage());
  }
}
