package art.arcane.hiddenore.listeners;

import art.arcane.hiddenore.HiddenOre;
import art.arcane.hiddenore.rules.ItemDropRule;
import art.arcane.hiddenore.rules.MiningRuleManager;
import art.arcane.hiddenore.util.common.Messages;
import art.arcane.volmlib.util.bukkit.Placeholders;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rolls and dispatches the configured command rewards for a break, shared by pickaxe mining and blast mining.
 */
final class CommandRewards {
  private final HiddenOre plugin;

  CommandRewards(HiddenOre plugin) {
    this.plugin = plugin;
  }

  List<CommandExec> roll(Player player, MiningRuleManager rules, Messages messages, int y, boolean debug) {
    List<CommandExec> commandsToExecute = new ArrayList<>();
    for (ItemDropRule rule : rules.getCommandRules(y)) {
      double roll = ThreadLocalRandom.current().nextDouble();
      boolean success = roll < rule.chance;
      if (success && rule.commands != null) {
        for (String raw : rule.commands) {
          if (raw == null) {
            continue;
          }
          String trimmed = raw.trim();
          ItemDropRule.ExecutionTarget target = rule.executionTarget;
          String cmdText = trimmed;
          int colon = trimmed.indexOf(':');
          if (colon > 0) {
            String prefix = trimmed.substring(0, colon).toLowerCase(Locale.ROOT);
            if ("player".equals(prefix) || "console".equals(prefix)) {
              cmdText = trimmed.substring(colon + 1).trim();
              target = "player".equals(prefix) ? ItemDropRule.ExecutionTarget.PLAYER : ItemDropRule.ExecutionTarget.CONSOLE;
            }
          }
          if (!cmdText.isEmpty()) {
            commandsToExecute.add(new CommandExec(cmdText, target));
          }
        }
      }
      if (debug) {
        HiddenOre.sendMessage(player, messages.component(player,
            success ? Messages.DEBUG_COMMAND_HIT : Messages.DEBUG_COMMAND_MISS,
            MessageArgs.builder()
                .untrusted("chance", rule.chance)
                .untrusted("roll", String.format(Locale.ROOT, "%.4f", roll))
                .build()
        ));
      }
    }

    return List.copyOf(commandsToExecute);
  }

  void execute(Player player, Location location, List<CommandExec> commands) {
    List<CommandExec> resolvedCommands = new ArrayList<>(commands.size());
    for (CommandExec execution : commands) {
      String resolved = applyCommandPlaceholders(execution.command, player, location);
      String command = resolved.startsWith("/") ? resolved.substring(1) : resolved;
      resolvedCommands.add(new CommandExec(command, execution.target));
    }

    scheduleCommandGroup(player, List.copyOf(resolvedCommands), 0);
  }

  private void scheduleCommandGroup(Player player, List<CommandExec> commands, int startIndex) {
    int groupStart = startIndex;
    while (groupStart < commands.size()) {
      int groupEnd = commandGroupEnd(commands, groupStart);
      int scheduledStart = groupStart;
      int scheduledEnd = groupEnd;
      int continuationIndex = groupEnd;
      ItemDropRule.ExecutionTarget target = commands.get(groupStart).target;
      Runnable task = () -> {
        CommandSender sender = target == ItemDropRule.ExecutionTarget.PLAYER ? player : Bukkit.getConsoleSender();
        try {
          dispatchCommands(sender, commands, scheduledStart, scheduledEnd);
        } finally {
          scheduleCommandGroup(player, commands, continuationIndex);
        }
      };

      Runnable retired = () -> salvageConsoleGroups(player, commands, scheduledStart);
      boolean scheduled = target == ItemDropRule.ExecutionTarget.PLAYER
          ? FoliaScheduler.runEntity(plugin, player, task, 0L, retired)
          : SchedulerUtils.runGlobal(plugin, task);
      if (scheduled) {
        return;
      }

      plugin.warnThrottled("command-reward-scheduling",
          "Failed to schedule %s command rewards for %s.",
          target.name().toLowerCase(Locale.ROOT), player.getName());
      groupStart = groupEnd;
    }
  }

  private void salvageConsoleGroups(Player player, List<CommandExec> commands, int startIndex) {
    ConsoleSalvage salvage = salvageConsoleCommands(commands, startIndex);
    List<CommandExec> consoleCommands = salvage.commands();
    if (consoleCommands.isEmpty()) {
      return;
    }
    Runnable task = () -> dispatchCommands(Bukkit.getConsoleSender(), consoleCommands, 0, consoleCommands.size());
    if (!SchedulerUtils.runGlobal(plugin, task)) {
      plugin.warnThrottled("salvaged-command-reward-scheduling",
          "Failed to schedule salvaged console command rewards for %s.", player.getName());
    }
  }

  static ConsoleSalvage salvageConsoleCommands(List<CommandExec> commands, int startIndex) {
    List<CommandExec> console = new ArrayList<>();
    int skippedPlayerGroups = 0;
    int index = startIndex;
    while (index < commands.size()) {
      int groupEnd = commandGroupEnd(commands, index);
      if (commands.get(index).target == ItemDropRule.ExecutionTarget.PLAYER) {
        skippedPlayerGroups++;
      } else {
        console.addAll(commands.subList(index, groupEnd));
      }
      index = groupEnd;
    }
    return new ConsoleSalvage(List.copyOf(console), skippedPlayerGroups);
  }

  record ConsoleSalvage(List<CommandExec> commands, int skippedPlayerGroups) {
  }

  static int commandGroupEnd(List<CommandExec> commands, int startIndex) {
    ItemDropRule.ExecutionTarget target = commands.get(startIndex).target;
    int endIndex = startIndex + 1;
    while (endIndex < commands.size() && commands.get(endIndex).target == target) {
      endIndex++;
    }
    return endIndex;
  }

  private void dispatchCommands(CommandSender sender, List<CommandExec> commands, int startIndex, int endIndex) {
    for (int index = startIndex; index < endIndex; index++) {
      Bukkit.dispatchCommand(sender, commands.get(index).command);
    }
  }

  static String applyCommandPlaceholders(String raw, Player player, Location loc) {
    World world = loc.getWorld();
    String builtIn = applyBuiltInPlaceholders(raw, player.getName(), player.getUniqueId().toString(),
        loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), world == null ? "" : world.getName());
    return Placeholders.setPlaceholders(player, builtIn);
  }

  static String applyBuiltInPlaceholders(String raw, String playerName, String playerId, int blockX, int blockY,
                                         int blockZ, String worldName) {
    if (raw == null) {
      return "";
    }
    String result = raw;
    result = result.replace("%player%", playerName);
    result = result.replace("%uuid%", playerId);
    result = result.replace("%x%", String.valueOf(blockX));
    result = result.replace("%y%", String.valueOf(blockY));
    result = result.replace("%z%", String.valueOf(blockZ));
    result = result.replace("%world%", worldName);
    return result;
  }

  static final class CommandExec {
    final String command;
    final ItemDropRule.ExecutionTarget target;

    CommandExec(String command, ItemDropRule.ExecutionTarget target) {
      this.command = command;
      this.target = target;
    }
  }
}
