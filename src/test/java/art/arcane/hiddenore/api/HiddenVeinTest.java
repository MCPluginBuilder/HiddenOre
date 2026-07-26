package art.arcane.hiddenore.api;

import org.bukkit.Material;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HiddenVeinTest {
  @Test
  public void seeded_separatesRealVeinsFromPureRandomPseudoVeins() {
    assertFalse(vein(-1).seeded());
    assertTrue(vein(0).seeded());
    assertTrue(vein(1).seeded());
    assertTrue(vein(Integer.MAX_VALUE).seeded());
  }

  private static HiddenVein vein(int veinId) {
    return new HiddenVein(0, 0, 0, veinId, Material.DIAMOND, Material.DIAMOND_ORE);
  }
}
