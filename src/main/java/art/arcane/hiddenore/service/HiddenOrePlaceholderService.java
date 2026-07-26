package art.arcane.hiddenore.service;

import art.arcane.hiddenore.HiddenOre;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderRegistration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;

import java.util.Objects;

public final class HiddenOrePlaceholderService implements Listener {
  static final String PLACEHOLDER_API = "PlaceholderAPI";

  private final HiddenOre plugin;
  private final PlaceholderRegistration registration;

  public HiddenOrePlaceholderService(HiddenOre plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.registration = new PlaceholderRegistration(plugin.getLogger());
  }

  public void register() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    attemptRegistration();
  }

  public void unregister() {
    HandlerList.unregisterAll(this);
    registration.unregister();
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPluginEnable(PluginEnableEvent event) {
    if (!isPlaceholderApi(event.getPlugin().getName())) {
      return;
    }

    attemptRegistration();
  }

  static boolean isPlaceholderApi(String pluginName) {
    return PLACEHOLDER_API.equals(pluginName);
  }

  private void attemptRegistration() {
    if (!PlaceholderRegistration.isPlaceholderApiEnabled()) {
      return;
    }

    HiddenOrePlaceholderInstaller.install(registration, plugin);
  }
}
