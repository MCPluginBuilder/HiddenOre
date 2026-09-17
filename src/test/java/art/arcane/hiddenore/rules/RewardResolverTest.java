package art.arcane.hiddenore.rules;

import art.arcane.hiddenore.util.project.ToolTier;
import org.bukkit.Material;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RewardResolverTest {
  @Test
  public void selectPureRandomRule_returnsTheFirstRuleWhoseRollLandsUnderItsChance() {
    ItemDropRule first = itemRule(Material.COAL, 2.2, 7);
    ItemDropRule second = itemRule(Material.DIAMOND, 0.5, 7);

    assertSame(first, RewardResolver.selectPureRandomRule(List.of(first, second),
        rolls(first.pureRandomChance() / 2.0)));
  }

  @Test
  public void selectPureRandomRule_skipsMissesAndStopsAtTheFirstHit() {
    ItemDropRule first = itemRule(Material.COAL, 2.2, 7);
    ItemDropRule second = itemRule(Material.DIAMOND, 0.5, 7);
    ItemDropRule third = itemRule(Material.EMERALD, 0.35, 7);

    assertSame(second, RewardResolver.selectPureRandomRule(List.of(first, second, third),
        rolls(first.pureRandomChance(), second.pureRandomChance() / 2.0)));
  }

  @Test
  public void selectPureRandomRule_withoutAnyHit_returnsNothing() {
    ItemDropRule first = itemRule(Material.COAL, 2.2, 7);
    ItemDropRule second = itemRule(Material.DIAMOND, 0.5, 7);

    assertNull(RewardResolver.selectPureRandomRule(List.of(first, second),
        rolls(first.pureRandomChance(), second.pureRandomChance())));
  }

  @Test
  public void selectPureRandomRule_neverSelectsAZeroChanceRule() {
    ItemDropRule disabled = itemRule(Material.DIAMOND, 0.0, 7);

    assertEquals(0.0, disabled.pureRandomChance(), 0.0);
    assertNull(RewardResolver.selectPureRandomRule(List.of(disabled), rolls(0.0)));
  }

  @Test
  public void selectPureRandomRule_withoutCandidates_returnsNothing() {
    assertNull(RewardResolver.selectPureRandomRule(List.of(), rolls(0.0)));
  }

  @Test
  public void grant_withAMatchingTier_awardsOneItemAndRollsExperience() {
    ItemDropRule rule = itemRule(Material.DIAMOND, 0.5, 7);

    RewardOutcome outcome = RewardResolver.grant(rule, ToolTier.IRON_PICKAXE, null, maximum -> maximum);

    assertTrue(outcome.granted());
    assertFalse(outcome.toolRejected());
    assertSame(rule, outcome.rule());
    assertEquals(1, outcome.amount());
    assertEquals(7, outcome.experience());
  }

  @Test
  public void grant_withAWrongOrMissingTier_rejectsTheToolAndAwardsNothing() {
    ItemDropRule rule = itemRule(Material.DIAMOND, 0.5, 7);

    for (ToolTier tier : new ToolTier[]{ToolTier.STONE_PICKAXE, null}) {
      RewardOutcome outcome = RewardResolver.grant(rule, tier, null, maximum -> maximum);

      assertFalse(outcome.granted());
      assertTrue(outcome.toolRejected());
      assertSame(rule, outcome.rule());
      assertEquals(0, outcome.amount());
      assertEquals(0, outcome.experience());
    }
  }

  @Test
  public void grant_withoutAnExperienceReward_skipsTheExperienceRoll() {
    ItemDropRule rule = new ItemDropRule(Material.RAW_IRON, 1.8, 4, 12, -64, 320, false,
        Set.of(ToolTier.IRON_PICKAXE), 0);
    AtomicInteger rolls = new AtomicInteger();

    RewardOutcome outcome = RewardResolver.grant(rule, ToolTier.IRON_PICKAXE, null, maximum -> {
      rolls.incrementAndGet();
      return maximum;
    });

    assertTrue(outcome.granted());
    assertEquals(0, outcome.experience());
    assertEquals(0, rolls.get());
  }

  @Test
  public void grant_withoutARule_awardsNothingAndRejectsNothing() {
    RewardOutcome outcome = RewardResolver.grant(null, ToolTier.NETHERITE_PICKAXE, null, maximum -> maximum);

    assertSame(RewardOutcome.NONE, outcome);
    assertFalse(outcome.granted());
    assertFalse(outcome.toolRejected());
    assertNull(outcome.rule());
  }

  private static ItemDropRule itemRule(Material material, double veinsPerChunk, int expDrop) {
    return new ItemDropRule(material, veinsPerChunk, 3, 8, -64, 320, true,
        Set.of(ToolTier.IRON_PICKAXE, ToolTier.DIAMOND_PICKAXE, ToolTier.NETHERITE_PICKAXE), expDrop);
  }

  private static DoubleSupplier rolls(double... values) {
    AtomicInteger index = new AtomicInteger();
    return () -> values[index.getAndIncrement()];
  }
}
