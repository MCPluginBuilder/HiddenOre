package art.arcane.hiddenore.rules;

import art.arcane.hiddenore.util.project.MiningUtil;
import art.arcane.hiddenore.util.project.ToolTier;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.IntUnaryOperator;

/**
 * Turns a break into a reward, shared by pickaxe mining and blast mining. Blast mining passes a
 * null tool, which skips Fortune, and the tier configured for explosions.
 */
public final class RewardResolver {
  private RewardResolver() {
  }

  public static ItemDropRule selectPureRandomRule(List<ItemDropRule> candidates, DoubleSupplier rolls) {
    for (ItemDropRule rule : candidates) {
      if (rolls.getAsDouble() < rule.pureRandomChance()) {
        return rule;
      }
    }
    return null;
  }

  public static RewardOutcome grant(ItemDropRule rule, ToolTier tier, ItemStack tool, IntUnaryOperator experienceRoll) {
    if (rule == null) {
      return RewardOutcome.NONE;
    }
    if (tier == null || !rule.toolTiers.contains(tier)) {
      return new RewardOutcome(rule, 0, 0, true);
    }

    int amount = MiningUtil.applyFortune(tool, rule, 1);
    int experience = rule.expDrop > 0 ? experienceRoll.applyAsInt(rule.expDrop) : 0;
    return new RewardOutcome(rule, amount, experience, false);
  }

  public static RewardOutcome rollPureRandom(List<ItemDropRule> candidates, ToolTier tier, ItemStack tool) {
    ItemDropRule rule = selectPureRandomRule(candidates, () -> ThreadLocalRandom.current().nextDouble());
    return grant(rule, tier, tool, MiningUtil::rollInclusiveExperience);
  }
}
