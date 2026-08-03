package art.arcane.hiddenore;

import art.arcane.hiddenore.generation.GenerationRules;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns every bStats type so the main class never references them. bStats is slimjar-provided:
 * any chart code in HiddenOre.class makes the verifier resolve the relocated CustomChart during
 * main-class linking, before ApplicationBuilder.build() has injected the library (boot NCDFE).
 */
public final class HiddenOreMetrics {
  private final Logger logger;
  private Metrics metrics;

  private HiddenOreMetrics(Logger logger, Metrics metrics) {
    this.logger = logger;
    this.metrics = metrics;
  }

  public static HiddenOreMetrics start(HiddenOre plugin, int pluginId) {
    Metrics metrics = new Metrics(plugin, pluginId);
    HiddenOreMetrics runtime = new HiddenOreMetrics(plugin.getLogger(), metrics);
    runtime.registerCharts(plugin);
    return runtime;
  }

  // Chart callables run on the bStats daemon thread, never main. Every accessor here reads
  // immutable runtime state or a synchronized cache; returning null skips that submission.
  private void registerCharts(HiddenOre plugin) {
    metrics.addCustomChart(new SingleLineChart("drop_rules", () -> {
      HiddenOre.RuntimeState state = plugin.runtimeStateOrNull();
      if (state == null) {
        return null;
      }
      return state.ruleManager().getAllDropRules().size();
    }));
    metrics.addCustomChart(new SimplePie("vein_generation_mode", () -> {
      HiddenOre.RuntimeState state = plugin.runtimeStateOrNull();
      if (state == null) {
        return null;
      }
      return state.ruleManager().getVeinConfig().generation.name();
    }));
    metrics.addCustomChart(new SimplePie("ore_removal", () -> {
      GenerationRules rules = plugin.getGenerationRules();
      if (rules == null || plugin.runtimeStateOrNull() == null) {
        return null;
      }
      return String.valueOf(rules.isEnabled());
    }));
    metrics.addCustomChart(new SingleLineChart("cached_vein_chunks", () -> {
      HiddenOre.RuntimeState state = plugin.runtimeStateOrNull();
      if (state == null) {
        return null;
      }
      return state.veinGenerator().cachedChunkCount();
    }));
  }

  public void shutdown() {
    Metrics activeMetrics = metrics;
    metrics = null;
    if (activeMetrics != null) {
      try {
        activeMetrics.shutdown();
      } catch (Throwable ex) {
        logger.log(Level.WARNING, "Error shutting down HiddenOre metrics", ex);
      }
    }
  }
}
