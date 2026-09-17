package art.arcane.hiddenore.blast;

import org.bukkit.Material;
import org.bukkit.entity.BreezeWindCharge;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.WindCharge;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BlastSourceTest {
  @Test
  public void ofEntity_classifiesEverySupportedExplosive() {
    assertEquals(BlastSource.TNT, BlastSource.of(stub(TNTPrimed.class)));
    assertEquals(BlastSource.MINECART_TNT, BlastSource.of(stub(ExplosiveMinecart.class)));
    assertEquals(BlastSource.CREEPER, BlastSource.of(stub(Creeper.class)));
    assertEquals(BlastSource.END_CRYSTAL, BlastSource.of(stub(EnderCrystal.class)));
    assertEquals(BlastSource.FIREBALL, BlastSource.of(stub(Fireball.class)));
    assertEquals(BlastSource.WITHER, BlastSource.of(stub(Wither.class)));
    assertEquals(BlastSource.ENDER_DRAGON, BlastSource.of(stub(EnderDragon.class)));
  }

  @Test
  public void ofEntity_windChargesAreNeverASourceAlthoughTheyExtendFireball() {
    assertEquals(BlastSource.OTHER, BlastSource.of(stub(WindCharge.class)));
    assertEquals(BlastSource.OTHER, BlastSource.of(stub(BreezeWindCharge.class)));
  }

  @Test
  public void ofEntity_witherSkullWinsOverItsFireballSupertype() {
    assertEquals(BlastSource.WITHER, BlastSource.of(stub(WitherSkull.class)));
  }

  @Test
  public void ofEntity_unknownOrMissingEntity_isOther() {
    assertEquals(BlastSource.OTHER, BlastSource.of((Entity) null));
    assertEquals(BlastSource.OTHER, BlastSource.of(stub(Player.class)));
  }

  @Test
  public void ofBlock_classifiesEveryBedColourAndTheRespawnAnchor() {
    assertEquals(BlastSource.BED, BlastSource.of(Material.RED_BED));
    assertEquals(BlastSource.BED, BlastSource.of(Material.WHITE_BED));
    assertEquals(BlastSource.BED, BlastSource.of(Material.LIGHT_BLUE_BED));
    assertEquals(BlastSource.RESPAWN_ANCHOR, BlastSource.of(Material.RESPAWN_ANCHOR));
  }

  @Test
  public void ofBlock_unknownOrMissingBlock_isOther() {
    assertEquals(BlastSource.OTHER, BlastSource.of(Material.STONE));
    assertEquals(BlastSource.OTHER, BlastSource.of(Material.BEDROCK));
    assertEquals(BlastSource.OTHER, BlastSource.of((Material) null));
  }

  @Test
  public void ofBlock_matchesEveryLiveBedAndNoLegacyMaterial() {
    for (Material material : Material.values()) {
      BlastSource classified = BlastSource.of(material);
      if (material.isLegacy()) {
        assertEquals(material.name(), BlastSource.OTHER, classified);
      } else if (material.name().endsWith("_BED")) {
        assertEquals(material.name(), BlastSource.BED, classified);
      } else if (material != Material.RESPAWN_ANCHOR) {
        assertEquals(material.name(), BlastSource.OTHER, classified);
      }
    }
  }

  @Test
  public void fromConfigName_acceptsAnyCaseAndSurroundingSpace() {
    assertEquals(BlastSource.TNT, BlastSource.fromConfigName("tnt"));
    assertEquals(BlastSource.TNT, BlastSource.fromConfigName("TNT"));
    assertEquals(BlastSource.MINECART_TNT, BlastSource.fromConfigName(" Minecart_Tnt "));
    assertEquals(BlastSource.RESPAWN_ANCHOR, BlastSource.fromConfigName("respawn_anchor"));
  }

  @Test
  public void fromConfigName_rejectsUnknownNamesAndTheCatchAllBucket() {
    assertNull(BlastSource.fromConfigName("OTHER"));
    assertNull(BlastSource.fromConfigName("other"));
    assertNull(BlastSource.fromConfigName("dragon"));
    assertNull(BlastSource.fromConfigName(""));
    assertNull(BlastSource.fromConfigName("   "));
    assertNull(BlastSource.fromConfigName(null));
  }

  @Test
  public void configurableNames_listEverySourceExceptTheCatchAllBucket() {
    assertEquals("TNT, MINECART_TNT, CREEPER, END_CRYSTAL, FIREBALL, WITHER, ENDER_DRAGON, BED, RESPAWN_ANCHOR",
        BlastSource.configurableNames());
  }

  private static <T> T stub(Class<T> type) {
    InvocationHandler handler = (proxy, method, args) -> {
      if ("hashCode".equals(method.getName())) {
        return System.identityHashCode(proxy);
      }
      if ("equals".equals(method.getName())) {
        return proxy == args[0];
      }
      if ("toString".equals(method.getName())) {
        return type.getSimpleName() + "-stub";
      }
      throw new UnsupportedOperationException(method.getName());
    };
    return type.cast(Proxy.newProxyInstance(BlastSourceTest.class.getClassLoader(), new Class<?>[]{type}, handler));
  }
}
