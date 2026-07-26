package art.arcane.hiddenore.service;

import art.arcane.hiddenore.HiddenOre;
import art.arcane.hiddenore.vein.VeinConfig;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderKeyRegistry;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderValues;
import art.arcane.volmlib.util.bukkit.papi.VolmitPlaceholderExpansion;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class HiddenOrePlaceholderExpansion extends VolmitPlaceholderExpansion {
  private static final String IDENTIFIER = "hiddenore";
  private static final String REQUIRED_PLUGIN = "HiddenOre";
  private static final String AUTHOR = "Volmit Software";
  private static final String VERSION = "1.0.0";
  private static final String SEEDED = "seeded";
  private static final String DROP_RULES = "drop-rules";

  public HiddenOrePlaceholderExpansion(Supplier<HiddenOre.RuntimeState> runtimeState, Logger logger) {
    super(IDENTIFIER, AUTHOR, VERSION, REQUIRED_PLUGIN, registry(runtimeState), logger);
  }

  static PlaceholderKeyRegistry registry(Supplier<HiddenOre.RuntimeState> runtimeState) {
    Objects.requireNonNull(runtimeState, "runtimeState");

    return PlaceholderKeyRegistry.builder()
        .key(PlaceholderKeyRegistry.AVAILABLE, playerId -> PlaceholderValues.bool(runtimeState.get() != null))
        .key(SEEDED, playerId -> seeded(runtimeState.get()))
        .key(DROP_RULES, playerId -> dropRules(runtimeState.get()))
        .build();
  }

  private static String seeded(HiddenOre.RuntimeState state) {
    if (state == null) {
      return PlaceholderValues.UNAVAILABLE;
    }

    return PlaceholderValues.bool(state.ruleManager().getVeinConfig().generation == VeinConfig.GenerationMode.SEEDED);
  }

  private static String dropRules(HiddenOre.RuntimeState state) {
    if (state == null) {
      return PlaceholderValues.UNAVAILABLE;
    }

    return PlaceholderValues.count(state.ruleManager().getAllDropRules().size());
  }
}
