package art.arcane.hiddenore.listeners;

import art.arcane.hiddenore.HiddenOre;
import art.arcane.hiddenore.api.BlockOrigin;
import art.arcane.hiddenore.api.BreakCause;
import art.arcane.hiddenore.api.HiddenVein;
import art.arcane.hiddenore.api.event.HiddenOreDropsEvent;
import art.arcane.hiddenore.blast.BlastConfig;
import art.arcane.hiddenore.blast.BlastSource;
import art.arcane.hiddenore.rules.ItemDropRule;
import art.arcane.hiddenore.rules.MiningRuleManager;
import art.arcane.hiddenore.rules.RewardOutcome;
import art.arcane.hiddenore.rules.RewardResolver;
import art.arcane.hiddenore.service.HiddenOreTelemetry;
import art.arcane.hiddenore.util.common.Messages;
import art.arcane.hiddenore.util.project.MiningUtil;
import art.arcane.hiddenore.vein.ChunkVeins;
import art.arcane.hiddenore.vein.VeinBlock;
import art.arcane.hiddenore.vein.VeinConfig;
import art.arcane.volmlib.util.bukkit.ChunkPositionSet;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Blast mining: hidden rewards for blocks destroyed by an explosion, plus the placement-tracking
 * cleanup that every explosion needs whether or not blast mining is switched on.
 *
 * <p>Runs at MONITOR so protection plugins have already trimmed the block list, and only for
 * explosions that actually destroy their blocks; a wind charge lists blocks it merely triggers.
 * The list itself is left intact for block loggers. A block that pays a reward while
 * {@code suppress_block_drop_on_custom_drop} is set is cleared here so the server drops nothing for
 * it and only the reward lands.
 */
public final class BlastMiningListener implements Listener {
  static final int MAX_EXAMINED_BLOCKS = 1024;
  static final int MAX_DEBUG_LINES = 8;

  private final HiddenOre plugin;
  private final IntegrationEventGuard eventGuard;
  private final CommandRewards commandRewards;

  public BlastMiningListener(HiddenOre plugin) {
    this.plugin = plugin;
    this.eventGuard = new IntegrationEventGuard(plugin.getLogger());
    this.commandRewards = new CommandRewards(plugin);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onEntityExplode(EntityExplodeEvent event) {
    if (!destroysBlocks(event.getExplosionResult())) {
      return;
    }
    Entity exploder = event.getEntity();
    handle(BlastSource.of(exploder), attributedPlayer(exploder), event.blockList(), event.getLocation());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBlockExplode(BlockExplodeEvent event) {
    if (!destroysBlocks(event.getExplosionResult())) {
      return;
    }
    BlockState exploded = event.getExplodedBlockState();
    handle(BlastSource.of(exploded == null ? null : exploded.getType()), null, event.blockList(),
        event.getBlock().getLocation());
  }

  private void handle(BlastSource source, Player player, List<Block> blocks, Location explosionCenter) {
    if (blocks.isEmpty()) {
      return;
    }

    HiddenOre.RuntimeState runtime = plugin.isDraining() ? null : plugin.runtimeStateOrNull();
    MiningRuleManager rules = runtime == null ? null : runtime.ruleManager();
    boolean rewarding = rules != null && rules.getBlastConfig().allows(source);
    boolean debug = rewarding && player != null && plugin.isDebug(player.getUniqueId());
    BlastContext context = rewarding
        ? new BlastContext(runtime, rules, rules.getBlastConfig(), rules.getVeinConfig(), player, new ItemRuleCache())
        : null;

    ChunkScopes scopes = new ChunkScopes();
    int rewardBudget = examinationBound(blocks.size());
    int debugBudget = MAX_DEBUG_LINES;
    int experience = 0;
    boolean eligibleBlock = false;

    try {
      for (Block block : blocks) {
        ChunkScope scope = scopes.of(block);
        int packed = ChunkPositionSet.pack(block.getX() & 15, block.getY(), block.getZ() & 15, scope.minHeight());
        boolean placed = scope.forgetPlacement(plugin, packed);

        Material brokenType = block.getType();
        if (!rewarding || rewardBudget <= 0 || rules.getGuaranteedDrop(brokenType) == null) {
          continue;
        }
        rewardBudget--;

        try {
          BlastHit hit = rewardBlock(context, scope, block, brokenType, packed, placed);
          if (!hit.eligibility().eligibleForCommands()) {
            continue;
          }
          eligibleBlock = true;
          if (hit.eligibility() != BlastEligibility.ELIGIBLE) {
            continue;
          }
          if (hit.outcome().granted()) {
            experience += award(runtime, player, block, brokenType, hit);
          }
          if (debug && debugBudget > 0 && reportHit(context, hit)) {
            debugBudget--;
          }
        } catch (RuntimeException failure) {
          plugin.logException(Level.WARNING, failure, "Failed to award a blast mining reward at %s %s,%s,%s.",
              block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
      }
    } finally {
      scopes.flush(plugin);
    }

    if (experience > 0) {
      int totalExperience = experience;
      explosionCenter.getWorld().spawn(explosionCenter, ExperienceOrb.class,
          orb -> orb.setExperience(totalExperience));
    }
    if (eligibleBlock && player != null) {
      awardCommands(context, explosionCenter, debug);
    }
  }

  private BlastHit rewardBlock(BlastContext context, ChunkScope scope, Block block, Material brokenType, int packed,
                               boolean placed) {
    VeinConfig veinConfig = context.veinConfig();
    boolean vetoed = eventGuard.isBreakVetoed(context.player(), block, brokenType, null, origin(placed),
        BreakCause.EXPLODED);
    BlastEligibility eligibility = eligibility(placed, veinConfig.allowPlacedBlocks, vetoed,
        context.blast().yieldChance, ThreadLocalRandom.current().nextDouble());
    if (eligibility != BlastEligibility.ELIGIBLE) {
      return BlastHit.ineligible(eligibility);
    }

    int y = block.getY();
    if (veinConfig.generation == VeinConfig.GenerationMode.PURE_RANDOM) {
      RewardOutcome outcome = RewardResolver.rollPureRandom(context.itemRules().forY(context.rules(), y),
          context.blast().toolTier, null);
      return new BlastHit(eligibility, outcome, vein(outcome, block, -1), -1, false);
    }

    ChunkVeins veins = scope.veins(context.runtime(), block);
    VeinBlock veinBlock = veins.get(packed);
    if (veinBlock == null || scope.isConsumed(plugin, packed)) {
      return BlastHit.ineligible(BlastEligibility.ELIGIBLE);
    }

    boolean firstOfVein = veins.isFirstOfVein(veinBlock.veinId(), packed, scope.consumed(plugin));
    scope.claim(plugin, packed);
    RewardOutcome outcome = RewardResolver.grant(veinBlock.rule(), context.blast().toolTier, null,
        MiningUtil::rollInclusiveExperience);
    if (outcome.granted() && firstOfVein) {
      HiddenOreTelemetry.countVeinDiscovery();
    }
    return new BlastHit(eligibility, outcome, vein(outcome, block, veinBlock.veinId()), veinBlock.veinId(),
        firstOfVein);
  }

  private int award(HiddenOre.RuntimeState runtime, Player player, Block block, Material brokenType, BlastHit hit) {
    HiddenOreTelemetry.countBreak();
    RewardOutcome outcome = hit.outcome();
    World world = block.getWorld();
    Location center = block.getLocation().add(0.5, 0.5, 0.5);

    List<ItemStack> drops = new ArrayList<>(1);
    drops.add(new ItemStack(outcome.rule().material, outcome.amount()));

    HiddenOreDropsEvent dropsEvent = new HiddenOreDropsEvent(player, block, brokenType, null, hit.vein(), drops,
        outcome.experience(), false, BreakCause.EXPLODED);
    Bukkit.getPluginManager().callEvent(dropsEvent);

    if (runtime.suppressBlockDrop()) {
      block.setType(Material.AIR, false);
    }

    List<ItemStack> resolvedDrops = dropsEvent.getDrops();
    int candidateStacks = resolvedDrops.size();
    int examinedStacks = MiningListener.dropExaminationBound(candidateStacks);
    int spawned = 0;
    for (int index = 0; index < examinedStacks; index++) {
      ItemStack stack = resolvedDrops.get(index);
      if (!eventGuard.isSpawnableDrop(stack)) {
        continue;
      }
      spawned++;
      HiddenOreTelemetry.countDrop();
      world.dropItem(center, stack);
    }

    if (candidateStacks > examinedStacks) {
      eventGuard.reportDropFlood(candidateStacks, spawned);
    }
    return dropsEvent.getExperience();
  }

  private void awardCommands(BlastContext context, Location explosionCenter, boolean debug) {
    Player player = context.player();
    Runnable task = () -> {
      List<CommandRewards.CommandExec> commands = commandRewards.roll(player, context.rules(),
          context.runtime().messages(), explosionCenter.getBlockY(), debug);
      if (!commands.isEmpty()) {
        commandRewards.execute(player, explosionCenter, commands);
      }
    };

    if (!FoliaScheduler.runEntity(plugin, player, task, 0L, () -> {
    })) {
      plugin.warnThrottled("blast-command-reward-scheduling",
          "Failed to schedule blast mining command rewards for %s.", player.getName());
    }
  }

  private boolean reportHit(BlastContext context, BlastHit hit) {
    RewardOutcome outcome = hit.outcome();
    if (!outcome.granted() && !outcome.toolRejected()) {
      return false;
    }

    Player player = context.player();
    Messages messages = context.runtime().messages();
    ItemDropRule rule = outcome.rule();
    boolean seeded = hit.veinId() >= 0;
    MessageArgs.Builder args = MessageArgs.builder()
        .untrusted("material", rule.material.name().toLowerCase(Locale.ROOT));
    if (seeded) {
      args.untrusted("vein", hit.veinId());
    }
    if (outcome.granted()) {
      args.untrusted("amount", outcome.amount());
    }

    TextKey key;
    if (!outcome.granted()) {
      key = seeded ? Messages.DEBUG_VEIN_DROP_LOST : Messages.DEBUG_RANDOM_DROP_LOST;
    } else if (seeded) {
      key = hit.firstOfVein() ? Messages.DEBUG_VEIN_DROP_DISCOVERED : Messages.DEBUG_VEIN_DROP;
    } else {
      key = Messages.DEBUG_RANDOM_DROP;
    }

    HiddenOre.sendMessage(player, messages.component(player, key, args.build()));
    return true;
  }

  private static HiddenVein vein(RewardOutcome outcome, Block block, int veinId) {
    if (!outcome.granted()) {
      return null;
    }
    Material material = outcome.rule().material;
    int y = block.getY();
    return new HiddenVein(block.getX(), y, block.getZ(), veinId, material, HiddenVein.oreDisplayFor(material, y));
  }

  static Player attributedPlayer(Entity entity) {
    if (entity instanceof TNTPrimed primed) {
      return primed.getSource() instanceof Player igniter ? igniter : null;
    }
    if (entity instanceof Projectile projectile) {
      return projectile.getShooter() instanceof Player shooter ? shooter : null;
    }
    return null;
  }

  static boolean destroysBlocks(ExplosionResult result) {
    return result == ExplosionResult.DESTROY || result == ExplosionResult.DESTROY_WITH_DECAY;
  }

  static BlastEligibility eligibility(boolean placed, boolean allowPlacedBlocks, boolean vetoed, double yieldChance,
                                      double roll) {
    if (placed && !allowPlacedBlocks) {
      return BlastEligibility.PLACED_BLOCKED;
    }
    if (vetoed) {
      return BlastEligibility.VETOED;
    }
    return paysYield(yieldChance, roll) ? BlastEligibility.ELIGIBLE : BlastEligibility.YIELD_MISSED;
  }

  static BlockOrigin origin(boolean placed) {
    return placed ? BlockOrigin.PLAYER_PLACED : BlockOrigin.PRESUMED_GENERATED;
  }

  static boolean paysYield(double yieldChance, double roll) {
    return roll < yieldChance;
  }

  static int examinationBound(int candidateBlocks) {
    return Math.min(Math.max(candidateBlocks, 0), MAX_EXAMINED_BLOCKS);
  }

  /**
   * Why a destroyed block did or did not earn a reward. Command rewards roll once per explosion
   * that contained at least one block the miner would have been paid for.
   */
  enum BlastEligibility {
    PLACED_BLOCKED,
    VETOED,
    YIELD_MISSED,
    ELIGIBLE;

    boolean eligibleForCommands() {
      return this == YIELD_MISSED || this == ELIGIBLE;
    }
  }

  private record BlastContext(HiddenOre.RuntimeState runtime, MiningRuleManager rules, BlastConfig blast,
                              VeinConfig veinConfig, Player player, ItemRuleCache itemRules) {
  }

  private record BlastHit(BlastEligibility eligibility, RewardOutcome outcome, HiddenVein vein, int veinId,
                          boolean firstOfVein) {
    private static BlastHit ineligible(BlastEligibility eligibility) {
      return new BlastHit(eligibility, RewardOutcome.NONE, null, -1, false);
    }
  }

  /**
   * One explosion spans a handful of Y levels, so the Y-filtered rule list is worth holding on to.
   */
  private static final class ItemRuleCache {
    private int y = Integer.MIN_VALUE;
    private List<ItemDropRule> rules = List.of();

    private List<ItemDropRule> forY(MiningRuleManager ruleManager, int level) {
      if (level != y) {
        y = level;
        rules = ruleManager.getItemRules(level);
      }
      return rules;
    }
  }

  /**
   * The chunks one explosion touches. Positions are read and edited in memory and written back to
   * each chunk once, instead of re-reading and re-serializing the container per block.
   */
  private static final class ChunkScopes {
    private long[] keys = new long[4];
    private ChunkScope[] scopes = new ChunkScope[4];
    private int size;

    private ChunkScope of(Block block) {
      long key = ((long) (block.getX() >> 4) << 32) | ((block.getZ() >> 4) & 0xffffffffL);
      for (int index = 0; index < size; index++) {
        if (keys[index] == key) {
          return scopes[index];
        }
      }

      if (size == keys.length) {
        long[] grownKeys = new long[size * 2];
        ChunkScope[] grownScopes = new ChunkScope[size * 2];
        System.arraycopy(keys, 0, grownKeys, 0, size);
        System.arraycopy(scopes, 0, grownScopes, 0, size);
        keys = grownKeys;
        scopes = grownScopes;
      }

      ChunkScope scope = new ChunkScope(block.getChunk(), block.getWorld().getMinHeight());
      keys[size] = key;
      scopes[size] = scope;
      size++;
      return scope;
    }

    private void flush(HiddenOre plugin) {
      for (int index = 0; index < size; index++) {
        scopes[index].flush(plugin);
      }
    }
  }

  private static final class ChunkScope {
    private final Chunk chunk;
    private final int minHeight;
    private int[] placed;
    private int[] consumed;
    private boolean placedDirty;
    private boolean consumedDirty;
    private ChunkVeins veins;

    private ChunkScope(Chunk chunk, int minHeight) {
      this.chunk = chunk;
      this.minHeight = minHeight;
    }

    private int minHeight() {
      return minHeight;
    }

    private boolean forgetPlacement(HiddenOre plugin, int packed) {
      int[] tracked = placed(plugin);
      int[] updated = ChunkPositionSet.removeValue(tracked, packed);
      if (updated == tracked) {
        return false;
      }
      placed = updated;
      placedDirty = true;
      return true;
    }

    private boolean isConsumed(HiddenOre plugin, int packed) {
      return ChunkPositionSet.contains(consumed(plugin), packed);
    }

    private void claim(HiddenOre plugin, int packed) {
      consumed = ChunkPositionSet.insert(consumed(plugin), packed);
      consumedDirty = true;
    }

    private ChunkVeins veins(HiddenOre.RuntimeState runtime, Block block) {
      if (veins == null) {
        veins = runtime.veinGenerator().get(block.getWorld(), block.getX() >> 4, block.getZ() >> 4);
      }
      return veins;
    }

    private int[] placed(HiddenOre plugin) {
      if (placed == null) {
        HiddenOreTelemetry.countPdcRead();
        placed = plugin.getPlacedBlocks().snapshot(chunk);
      }
      return placed;
    }

    private int[] consumed(HiddenOre plugin) {
      if (consumed == null) {
        HiddenOreTelemetry.countPdcRead();
        consumed = plugin.getConsumedVeins().snapshot(chunk);
      }
      return consumed;
    }

    private void flush(HiddenOre plugin) {
      if (placedDirty) {
        write(plugin.getPlacedBlocksKey(), placed);
        placedDirty = false;
      }
      if (consumedDirty) {
        write(plugin.getConsumedVeinsKey(), consumed);
        consumedDirty = false;
      }
    }

    private void write(NamespacedKey key, int[] positions) {
      HiddenOreTelemetry.countPdcWrite();
      PersistentDataContainer container = chunk.getPersistentDataContainer();
      if (positions.length == 0) {
        container.remove(key);
      } else {
        container.set(key, PersistentDataType.INTEGER_ARRAY, positions);
      }
    }
  }
}
