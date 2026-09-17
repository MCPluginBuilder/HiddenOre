package art.arcane.hiddenore.blast;

import org.bukkit.Material;
import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.minecart.ExplosiveMinecart;

import java.util.Locale;
import java.util.StringJoiner;

/**
 * Explosion kinds operators can enable for blast mining.
 *
 * <p>Sources are matched on Bukkit interfaces rather than {@code EntityType} names so that
 * renames in the server API cannot silently change what a configured source means.
 */
public enum BlastSource {
  TNT,
  MINECART_TNT,
  CREEPER,
  END_CRYSTAL,
  FIREBALL,
  WITHER,
  ENDER_DRAGON,
  BED,
  RESPAWN_ANCHOR,
  OTHER;

  private static final String BED_SUFFIX = "_BED";
  private static final String LEGACY_PREFIX = "LEGACY_";
  private static final String CONFIGURABLE_NAMES = joinConfigurableNames();

  /**
   * Wind charges extend {@code Fireball} but leave their listed blocks standing, so they are never
   * a blast-mining source.
   */
  public static BlastSource of(Entity entity) {
    if (entity instanceof TNTPrimed) {
      return TNT;
    }
    if (entity instanceof ExplosiveMinecart) {
      return MINECART_TNT;
    }
    if (entity instanceof Creeper) {
      return CREEPER;
    }
    if (entity instanceof EnderCrystal) {
      return END_CRYSTAL;
    }
    if (entity instanceof WitherSkull || entity instanceof Wither) {
      return WITHER;
    }
    if (entity instanceof EnderDragon) {
      return ENDER_DRAGON;
    }
    if (entity instanceof AbstractWindCharge) {
      return OTHER;
    }
    if (entity instanceof Fireball) {
      return FIREBALL;
    }
    return OTHER;
  }

  /**
   * Beds are matched on the material name because {@code Tag.BEDS} needs a running server.
   */
  public static BlastSource of(Material explodedBlock) {
    if (explodedBlock == null) {
      return OTHER;
    }
    if (explodedBlock == Material.RESPAWN_ANCHOR) {
      return RESPAWN_ANCHOR;
    }
    String name = explodedBlock.name();
    return name.endsWith(BED_SUFFIX) && !name.startsWith(LEGACY_PREFIX) ? BED : OTHER;
  }

  public static BlastSource fromConfigName(String name) {
    if (name == null) {
      return null;
    }
    String normalized = name.trim().toUpperCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return null;
    }
    for (BlastSource source : values()) {
      if (source != OTHER && source.name().equals(normalized)) {
        return source;
      }
    }
    return null;
  }

  public static String configurableNames() {
    return CONFIGURABLE_NAMES;
  }

  private static String joinConfigurableNames() {
    StringJoiner names = new StringJoiner(", ");
    for (BlastSource source : values()) {
      if (source != OTHER) {
        names.add(source.name());
      }
    }
    return names.toString();
  }
}
