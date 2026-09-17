package art.arcane.hiddenore.listeners;

import art.arcane.hiddenore.HiddenOre;
import art.arcane.hiddenore.api.BlockOrigin;
import art.arcane.hiddenore.api.BreakCause;
import art.arcane.hiddenore.api.HiddenVein;
import art.arcane.hiddenore.api.event.HiddenOreDropsEvent;
import art.arcane.hiddenore.rules.ItemDropRule;
import art.arcane.hiddenore.rules.MiningRuleManager;
import art.arcane.hiddenore.rules.RewardOutcome;
import art.arcane.hiddenore.rules.RewardResolver;
import art.arcane.hiddenore.service.HiddenOreTelemetry;
import art.arcane.hiddenore.util.common.Messages;
import art.arcane.hiddenore.util.project.MiningUtil;
import art.arcane.hiddenore.util.project.SoundResolver;
import art.arcane.hiddenore.util.project.ToolTier;
import art.arcane.hiddenore.vein.ChunkVeins;
import art.arcane.hiddenore.vein.VeinBlock;
import art.arcane.hiddenore.vein.VeinConfig;
import art.arcane.volmlib.util.bukkit.ChunkPositionSet;
import art.arcane.volmlib.util.localization.MessageArgs;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MiningListener implements Listener {
  static final int MAX_DROP_STACKS = 256;

  private final HiddenOre plugin;
  private final IntegrationEventGuard eventGuard;
  private final CommandRewards commandRewards;
  private final Map<BreakKey, BreakPreparation> pendingBreaks = new ConcurrentHashMap<>();
  private final Map<BlockDropItemEvent, DropPreparation> pendingDrops = new ConcurrentHashMap<>();

  public MiningListener(HiddenOre plugin) {
    this.plugin = plugin;
    this.eventGuard = new IntegrationEventGuard(plugin.getLogger());
    this.commandRewards = new CommandRewards(plugin);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    if (plugin.isDraining()) {
      return;
    }

    Player player = event.getPlayer();
    if (player.getGameMode() == GameMode.CREATIVE) {
      return;
    }

    ItemStack tool = player.getInventory().getItemInMainHand();
    if (!MiningUtil.isPickaxe(tool.getType())) {
      return;
    }

    HiddenOre.RuntimeState runtime = plugin.getRuntimeState();
    MiningRuleManager rules = runtime.ruleManager();
    if (rules.getGuaranteedDrop(event.getBlock().getType()) == null) {
      return;
    }

    event.setExpToDrop(0);
    if (!event.isDropItems()) {
      return;
    }

    pendingBreaks.put(breakKey(player, event.getBlock()), new BreakPreparation(runtime, snapshotTool(tool)));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
  public void onBlockBreakFinal(BlockBreakEvent event) {
    if (!shouldDiscardBreakPreparation(event.isCancelled(), event.isDropItems(), event.getBlock().getType().isAir())) {
      return;
    }
    pendingBreaks.remove(breakKey(event.getPlayer(), event.getBlock()));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void prepareBlockDrop(BlockDropItemEvent event) {
    BreakKey key = breakKey(event.getPlayer(), event.getBlockState());
    BreakPreparation preparation = pendingBreaks.remove(key);
    if (plugin.isDraining() || preparation == null) {
      return;
    }

    event.getItems().clear();
    HiddenOreTelemetry.countPdcRead();
    boolean trackedPlacement = plugin.getPlacedBlocks().contains(event.getBlock());
    pendingDrops.put(event, new DropPreparation(preparation.runtime(), preparation.tool(), trackedPlacement));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
  public void commitBlockDrop(BlockDropItemEvent event) {
    BreakKey key = breakKey(event.getPlayer(), event.getBlockState());
    pendingBreaks.remove(key);
    DropPreparation preparation = pendingDrops.remove(event);
    Block block = event.getBlock();
    try {
      if (!event.isCancelled() && preparation != null) {
        processAcceptedDrop(event, preparation);
      }
    } finally {
      HiddenOreTelemetry.countPdcWrite();
      plugin.getPlacedBlocks().remove(block);
    }
  }

  private void processAcceptedDrop(BlockDropItemEvent event, DropPreparation preparation) {
    HiddenOreTelemetry.countBreak();
    Player player = event.getPlayer();
    BlockState blockState = event.getBlockState();
    Material blockType = blockState.getType();
    HiddenOre.RuntimeState runtime = preparation.runtime();
    MiningRuleManager rules = runtime.ruleManager();
    Messages messages = runtime.messages();
    Material guaranteedDrop = rules.getGuaranteedDrop(blockType);
    if (guaranteedDrop == null) {
      return;
    }

    ItemStack tool = preparation.tool();
    Block block = event.getBlock();
    boolean debug = plugin.isDebug(player.getUniqueId());
    World world = blockState.getWorld();
    Location blockLocation = blockState.getLocation();
    Location centerLoc = blockLocation.clone().add(0.5, 0.5, 0.5);
    int blockX = blockState.getX();
    int y = blockState.getY();
    int blockZ = blockState.getZ();

    VeinConfig veinConfig = rules.getVeinConfig();
    boolean placed = blocksHiddenRewards(veinConfig.allowPlacedBlocks, preparation.trackedPlacement());
    boolean vetoed = eventGuard.isBreakVetoed(player, block, blockType, tool,
        breakOrigin(preparation.trackedPlacement()), BreakCause.MINED);
    RewardPath rewardPath = rewardPath(vetoed, placed, veinConfig.generation);
    List<ItemStack> drops = new ArrayList<>();
    int experience = 0;
    HiddenVein vein = null;
    List<CommandRewards.CommandExec> commandsToExecute = List.of();

    if (placed && debug) {
      HiddenOre.sendMessage(player, messages.component(player,
          Messages.DEBUG_PLAYER_PLACED,
          MessageArgs.builder()
              .untrusted("block", blockType.name().toLowerCase(Locale.ROOT))
              .build()
      ));
    }

    if (rewardPath == RewardPath.PURE_RANDOM) {
      ToolTier tier = ToolTier.fromMaterial(tool.getType());
      RewardOutcome outcome = RewardResolver.rollPureRandom(rules.getItemRules(y), tier, tool);
      if (outcome.granted()) {
        Material rewarded = outcome.rule().material;
        drops.add(new ItemStack(rewarded, outcome.amount()));
        experience += outcome.experience();
        vein = new HiddenVein(blockX, y, blockZ, -1, rewarded, HiddenVein.oreDisplayFor(rewarded, y));
        playDiscoverySound(player, veinConfig);
        if (debug) {
          HiddenOre.sendMessage(player, messages.component(player,
              Messages.DEBUG_RANDOM_DROP,
              MessageArgs.builder()
                  .untrusted("material", rewarded.name().toLowerCase(Locale.ROOT))
                  .untrusted("amount", outcome.amount())
                  .build()
          ));
        }
      } else if (outcome.toolRejected() && debug) {
        HiddenOre.sendMessage(player, messages.component(player,
            Messages.DEBUG_RANDOM_DROP_LOST,
            MessageArgs.builder()
                .untrusted("material", outcome.rule().material.name().toLowerCase(Locale.ROOT))
                .build()
        ));
      }

      commandsToExecute = commandRewards.roll(player, rules, messages, y, debug);
    } else if (rewardPath == RewardPath.SEEDED) {
      ChunkVeins veins = runtime.veinGenerator().get(world, blockX >> 4, blockZ >> 4);
      int packed = ChunkPositionSet.pack(blockX & 15, y, blockZ & 15, world.getMinHeight());
      VeinBlock veinBlock = veins.get(packed);

      if (veinBlock != null && !consumedVeinsContains(block)) {
        boolean firstOfVein = isFirstOfVein(block, veins, veinBlock, packed);
        claimVein(rewardPath, block);
        ItemDropRule rule = veinBlock.rule();
        ToolTier tier = ToolTier.fromMaterial(tool.getType());
        RewardOutcome outcome = RewardResolver.grant(rule, tier, tool, MiningUtil::rollInclusiveExperience);

        if (outcome.granted()) {
          drops.add(new ItemStack(rule.material, outcome.amount()));
          experience += outcome.experience();
          vein = new HiddenVein(blockX, y, blockZ, veinBlock.veinId(), rule.material, HiddenVein.oreDisplayFor(rule.material, y));
          if (firstOfVein) {
            HiddenOreTelemetry.countVeinDiscovery();
            playDiscoverySound(player, veinConfig);
          }
          if (debug) {
            HiddenOre.sendMessage(player, messages.component(player,
                firstOfVein ? Messages.DEBUG_VEIN_DROP_DISCOVERED : Messages.DEBUG_VEIN_DROP,
                MessageArgs.builder()
                    .untrusted("vein", veinBlock.veinId())
                    .untrusted("material", rule.material.name().toLowerCase(Locale.ROOT))
                    .untrusted("amount", outcome.amount())
                    .build()
            ));
          }
        } else if (outcome.toolRejected() && debug) {
          HiddenOre.sendMessage(player, messages.component(player,
              Messages.DEBUG_VEIN_DROP_LOST,
              MessageArgs.builder()
                  .untrusted("vein", veinBlock.veinId())
                  .untrusted("material", rule.material.name().toLowerCase(Locale.ROOT))
                  .build()
          ));
        }
      }

      commandsToExecute = commandRewards.roll(player, rules, messages, y, debug);
    }

    boolean customDrop = !drops.isEmpty() || !commandsToExecute.isEmpty();
    if (!runtime.suppressBlockDrop() || !customDrop) {
      drops.add(new ItemStack(guaranteedDrop, 1));
    }

    HiddenOreDropsEvent dropsEvent = new HiddenOreDropsEvent(player, block, blockType, tool.clone(), vein, drops, experience,
        runtime.autoPickup(), BreakCause.MINED);
    Bukkit.getPluginManager().callEvent(dropsEvent);

    commandRewards.execute(player, blockLocation, commandsToExecute);

    List<ItemStack> resolvedDrops = dropsEvent.getDrops();
    int candidateStacks = resolvedDrops.size();
    int examinedStacks = dropExaminationBound(candidateStacks);
    int spawned = 0;
    for (int index = 0; index < examinedStacks; index++) {
      ItemStack stack = resolvedDrops.get(index);
      if (!eventGuard.isSpawnableDrop(stack)) {
        continue;
      }
      spawned++;
      HiddenOreTelemetry.countDrop();
      if (dropsEvent.isToInventory()) {
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        leftover.values().forEach(item -> world.dropItem(centerLoc, item));
      } else {
        world.dropItem(centerLoc, stack);
      }
    }

    if (candidateStacks > examinedStacks) {
      eventGuard.reportDropFlood(candidateStacks, spawned);
    }

    int totalExp = dropsEvent.getExperience();
    if (totalExp > 0) {
      world.spawn(centerLoc, ExperienceOrb.class, orb -> orb.setExperience(totalExp));
    }
  }

  static boolean blocksHiddenRewards(boolean allowPlacedBlocks, boolean trackedPlacement) {
    return !allowPlacedBlocks && trackedPlacement;
  }

  static BlockOrigin breakOrigin(boolean trackedPlacement) {
    return trackedPlacement ? BlockOrigin.PLAYER_PLACED : BlockOrigin.PRESUMED_GENERATED;
  }

  static RewardPath rewardPath(boolean vetoed, boolean placed, VeinConfig.GenerationMode generation) {
    if (vetoed || placed) {
      return RewardPath.NONE;
    }
    return switch (generation) {
      case PURE_RANDOM -> RewardPath.PURE_RANDOM;
      case SEEDED -> RewardPath.SEEDED;
    };
  }

  static boolean consumesVein(RewardPath rewardPath) {
    return rewardPath == RewardPath.SEEDED;
  }

  static int dropExaminationBound(int candidateStacks) {
    return Math.min(Math.max(candidateStacks, 0), MAX_DROP_STACKS);
  }

  private void claimVein(RewardPath rewardPath, Block block) {
    if (!consumesVein(rewardPath)) {
      return;
    }
    HiddenOreTelemetry.countPdcWrite();
    plugin.getConsumedVeins().add(block);
  }

  static boolean shouldDiscardBreakPreparation(boolean cancelled, boolean dropItems, boolean blockAir) {
    return cancelled || !dropItems || blockAir;
  }

  static ItemStack snapshotTool(ItemStack tool) {
    return tool.clone();
  }

  private boolean consumedVeinsContains(Block block) {
    HiddenOreTelemetry.countPdcRead();
    return plugin.getConsumedVeins().contains(block);
  }

  private boolean isFirstOfVein(Block block, ChunkVeins veins, VeinBlock veinBlock, int packed) {
    HiddenOreTelemetry.countPdcRead();
    return veins.isFirstOfVein(veinBlock.veinId(), packed, plugin.getConsumedVeins().snapshot(block.getChunk()));
  }

  private void playDiscoverySound(Player player, VeinConfig veinConfig) {
    Sound sound = SoundResolver.resolve(veinConfig.discoverySound, Sound.BLOCK_BEACON_POWER_SELECT);
    player.playSound(player.getLocation(), sound, veinConfig.discoveryVolume, veinConfig.discoveryPitch);
  }

  enum RewardPath {
    NONE,
    PURE_RANDOM,
    SEEDED
  }


  private BreakKey breakKey(Player player, Block block) {
    return new BreakKey(player.getUniqueId(), block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
  }

  private BreakKey breakKey(Player player, BlockState blockState) {
    return new BreakKey(player.getUniqueId(), blockState.getWorld().getUID(), blockState.getX(), blockState.getY(), blockState.getZ());
  }

  private record BreakKey(UUID playerId, UUID worldId, int x, int y, int z) {
  }

  private record BreakPreparation(HiddenOre.RuntimeState runtime, ItemStack tool) {
  }

  private record DropPreparation(HiddenOre.RuntimeState runtime, ItemStack tool, boolean trackedPlacement) {
  }

}
