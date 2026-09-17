package art.arcane.hiddenore.util.project;

import org.bukkit.Material;

import java.util.Locale;
import java.util.StringJoiner;

public enum ToolTier {
  WOODEN_PICKAXE,
  STONE_PICKAXE,
  COPPER_PICKAXE,
  IRON_PICKAXE,
  GOLDEN_PICKAXE,
  DIAMOND_PICKAXE,
  NETHERITE_PICKAXE;

  public static String names() {
    StringJoiner joined = new StringJoiner(", ");
    for (ToolTier tier : values()) {
      joined.add(tier.name());
    }
    return joined.toString();
  }

  public static ToolTier fromName(String name) {
    if (name == null) {
      return null;
    }
    String normalized = name.trim().toUpperCase(Locale.ROOT);
    for (ToolTier tier : values()) {
      if (tier.name().equals(normalized)) {
        return tier;
      }
    }
    return null;
  }

  public static ToolTier fromMaterial(Material mat) {
    switch (mat) {
      case WOODEN_PICKAXE:
        return WOODEN_PICKAXE;
      case STONE_PICKAXE:
        return STONE_PICKAXE;
      case COPPER_PICKAXE:
        return COPPER_PICKAXE;
      case IRON_PICKAXE:
        return IRON_PICKAXE;
      case GOLDEN_PICKAXE:
        return GOLDEN_PICKAXE;
      case DIAMOND_PICKAXE:
        return DIAMOND_PICKAXE;
      case NETHERITE_PICKAXE:
        return NETHERITE_PICKAXE;
      default:
        return null;
    }
  }
}