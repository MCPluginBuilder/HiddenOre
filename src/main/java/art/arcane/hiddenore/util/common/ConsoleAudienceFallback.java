package art.arcane.hiddenore.util.common;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;

/**
 * Chat delivery for console/RCON senders on platforms without native Adventure (plain Spigot).
 * The adventure-platform-bukkit facade silently drops console chat there: its facets (built
 * against the adventure 4.x core) fail against the slimmed 5.x core and swallow their own
 * errors unless -Dnet.kyori.adventure.debug=true. Bypass the facade: serialize the component
 * to legacy section text and use Bukkit's plain sendMessage(String), which the Spigot console
 * always renders. Paper consoles implement Audience natively and never reach this path.
 */
public final class ConsoleAudienceFallback {

  private ConsoleAudienceFallback() {
  }

  public static boolean isConsoleLike(CommandSender sender) {
    return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
  }

  public static String legacyText(Component component) {
    return LegacyComponentSerializer.legacySection().serialize(component);
  }

  /**
   * Delivers to console-like senders via the legacy String path.
   *
   * @return true if the sender was console-like and the message was delivered
   */
  public static boolean deliver(CommandSender sender, Component component) {
    if (!isConsoleLike(sender)) {
      return false;
    }
    sender.sendMessage(legacyText(component));
    return true;
  }

  /**
   * Full delivery routing. Lives here — NOT in the plugin main class — because the
   * instanceof below makes the verifier resolve the slimjar-provided Audience class
   * at class-load time; in the main class that fires before ApplicationBuilder.build()
   * and fails the plugin load on Spigot.
   */
  public static void route(CommandSender sender, Component component, BukkitAudiences audiences) {
    if (sender instanceof Audience audience) {
      audience.sendMessage(component);
      return;
    }
    if (deliver(sender, component)) {
      return;
    }
    if (audiences != null) {
      audiences.sender(sender).sendMessage(component);
    }
  }
}
