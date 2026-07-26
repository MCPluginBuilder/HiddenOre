package art.arcane.hiddenore.service;

import art.arcane.hiddenore.HiddenOre;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderRegistration;

final class HiddenOrePlaceholderInstaller {
  private HiddenOrePlaceholderInstaller() {
  }

  static void install(PlaceholderRegistration registration, HiddenOre plugin) {
    registration.register(() -> new HiddenOrePlaceholderExpansion(plugin::runtimeStateOrNull, plugin.getLogger()));
  }
}
