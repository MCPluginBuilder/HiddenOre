package art.arcane.hiddenore.rules;

/**
 * What a single break earned: the rule that matched, how many items it pays, and the experience
 * rolled for it. {@code toolRejected} separates "no rule matched" from "a rule matched but the
 * tool was the wrong tier", which the two paths report differently in debug output.
 */
public record RewardOutcome(ItemDropRule rule, int amount, int experience, boolean toolRejected) {
  public static final RewardOutcome NONE = new RewardOutcome(null, 0, 0, false);

  public boolean granted() {
    return rule != null && !toolRejected && amount > 0;
  }
}
