package art.arcane.hiddenore.blast;

import art.arcane.hiddenore.util.project.ToolTier;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.EnumSet;
import java.util.Set;

/**
 * The {@code [blast_mining]} section: whether explosions pay hidden rewards, how often, which
 * pickaxe tier they count as, and which explosion kinds qualify.
 */
public final class BlastConfig {
  private static final double DEFAULT_YIELD = 0.5;
  private static final ToolTier DEFAULT_TOOL_TIER = ToolTier.IRON_PICKAXE;
  private static final Set<BlastSource> DEFAULT_SOURCES = Set.of(BlastSource.TNT, BlastSource.MINECART_TNT);

  public final boolean enabled;
  public final double yieldChance;
  public final ToolTier toolTier;
  public final Set<BlastSource> sources;

  private BlastConfig(boolean enabled, double yieldChance, ToolTier toolTier, Set<BlastSource> sources) {
    this.enabled = enabled;
    this.yieldChance = yieldChance;
    this.toolTier = toolTier;
    this.sources = sources;
  }

  public static BlastConfig parse(JsonElement raw) {
    if (raw == null || raw.isJsonNull()) {
      return new BlastConfig(false, DEFAULT_YIELD, DEFAULT_TOOL_TIER, DEFAULT_SOURCES);
    }
    if (!(raw instanceof JsonObject section)) {
      throw invalid("blast_mining", "expected a table");
    }
    boolean enabled = parseEnabled(section.get("enabled"));
    return new BlastConfig(enabled, parseYield(section.get("yield")),
        parseToolTier(section.get("tool_tier")), parseSources(section.get("sources"), enabled));
  }

  public boolean allows(BlastSource source) {
    return enabled && source != null && sources.contains(source);
  }

  private static boolean parseEnabled(JsonElement raw) {
    if (raw == null) {
      return false;
    }
    if (!(raw instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
      throw invalid("blast_mining.enabled", "expected true or false");
    }
    return primitive.getAsBoolean();
  }

  private static double parseYield(JsonElement raw) {
    if (raw == null) {
      return DEFAULT_YIELD;
    }
    if (!(raw instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
      throw invalidYield();
    }

    double value = primitive.getAsDouble();
    if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
      throw invalidYield();
    }
    return value;
  }

  private static ToolTier parseToolTier(JsonElement raw) {
    if (raw == null) {
      return DEFAULT_TOOL_TIER;
    }
    if (!(raw instanceof JsonPrimitive primitive) || !primitive.isString() || primitive.getAsString().isBlank()) {
      throw invalid("blast_mining.tool_tier", "expected a pickaxe tier name");
    }

    String name = primitive.getAsString().trim();
    ToolTier tier = ToolTier.fromName(name);
    if (tier == null) {
      throw invalid("blast_mining.tool_tier", "unknown tool tier '" + name + "'; expected one of "
          + ToolTier.names());
    }
    return tier;
  }

  private static Set<BlastSource> parseSources(JsonElement raw, boolean enabled) {
    if (raw == null) {
      return DEFAULT_SOURCES;
    }
    if (!(raw instanceof JsonArray configured)) {
      throw invalid("blast_mining.sources", "expected a list of explosion source names");
    }
    if (configured.isEmpty()) {
      if (!enabled) {
        return Set.of();
      }
      throw invalid("blast_mining.sources", "expected at least one explosion source while blast mining is enabled");
    }

    EnumSet<BlastSource> parsed = EnumSet.noneOf(BlastSource.class);
    for (int index = 0; index < configured.size(); index++) {
      String path = "blast_mining.sources[" + index + "]";
      JsonElement element = configured.get(index);
      if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()
          || primitive.getAsString().isBlank()) {
        throw invalid(path, "expected an explosion source name");
      }

      BlastSource source = BlastSource.fromConfigName(primitive.getAsString());
      if (source == null) {
        throw invalid(path, "unknown explosion source '" + primitive.getAsString().trim()
            + "'; expected one of " + BlastSource.configurableNames());
      }
      parsed.add(source);
    }
    return Set.copyOf(parsed);
  }

  private static IllegalArgumentException invalidYield() {
    return invalid("blast_mining.yield", "must be a finite number between 0 and 1 inclusive");
  }

  private static IllegalArgumentException invalid(String path, String message) {
    return new IllegalArgumentException(path + ": " + message);
  }
}
