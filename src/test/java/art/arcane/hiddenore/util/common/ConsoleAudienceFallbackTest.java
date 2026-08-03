package art.arcane.hiddenore.util.common;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleAudienceFallbackTest {

  private static <T extends CommandSender> T senderProxy(Class<T> type, List<String> lines) {
    InvocationHandler handler = (Object proxy, Method method, Object[] arguments) -> {
      if (method.getName().equals("sendMessage") && arguments != null && arguments.length == 1
          && arguments[0] instanceof String line) {
        lines.add(line);
        return null;
      }
      Class<?> returnType = method.getReturnType();
      if (returnType == boolean.class) {
        return false;
      }
      return null;
    };
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
  }

  @Test
  public void legacyTextSerializesSectionCodes() {
    Component component = Component.text("HiddenOre reloaded", NamedTextColor.GREEN);
    assertEquals("§aHiddenOre reloaded", ConsoleAudienceFallback.legacyText(component));
  }

  @Test
  public void deliverSendsLegacyStringToConsoleSender() {
    List<String> lines = new ArrayList<>();
    ConsoleCommandSender console = senderProxy(ConsoleCommandSender.class, lines);

    boolean delivered = ConsoleAudienceFallback.deliver(console,
        Component.text("reloaded", NamedTextColor.GOLD));

    assertTrue(delivered);
    assertEquals(1, lines.size());
    assertEquals("§6reloaded", lines.get(0));
  }

  @Test
  public void deliverSendsLegacyStringToRconSender() {
    List<String> lines = new ArrayList<>();
    RemoteConsoleCommandSender rcon = senderProxy(RemoteConsoleCommandSender.class, lines);

    assertTrue(ConsoleAudienceFallback.deliver(rcon, Component.text("debug on")));
    assertEquals(1, lines.size());
    assertEquals("debug on", lines.get(0));
  }

  @Test
  public void deliverSkipsNonConsoleSenders() {
    List<String> lines = new ArrayList<>();
    CommandSender plain = senderProxy(CommandSender.class, lines);

    assertFalse(ConsoleAudienceFallback.deliver(plain, Component.text("nope")));
    assertTrue(lines.isEmpty());
  }
}
