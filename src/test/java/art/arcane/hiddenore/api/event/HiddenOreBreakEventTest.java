package art.arcane.hiddenore.api.event;

import art.arcane.hiddenore.api.BlockOrigin;
import art.arcane.hiddenore.api.BreakCause;
import org.bukkit.Material;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class HiddenOreBreakEventTest {
  @Test
  public void breakEvent_isCancellableUnlikeTheDropsEvent() {
    assertTrue(Cancellable.class.isAssignableFrom(HiddenOreBreakEvent.class));
    assertFalse(Cancellable.class.isAssignableFrom(HiddenOreDropsEvent.class));
  }

  @Test
  public void breakEvent_isSynchronousSoItCanBeVetoedOnTheRegionThread() {
    assertFalse(event(BlockOrigin.PRESUMED_GENERATED).isAsynchronous());
  }

  @Test
  public void cancellation_roundTripsAndDefaultsToAllowed() {
    HiddenOreBreakEvent event = event(BlockOrigin.PRESUMED_GENERATED);

    assertFalse(event.isCancelled());
    event.setCancelled(true);
    assertTrue(event.isCancelled());
    event.setCancelled(false);
    assertFalse(event.isCancelled());
  }

  @Test
  public void breakEvent_carriesTheOriginAndBrokenTypeItWasConstructedWith() {
    HiddenOreBreakEvent placed = event(BlockOrigin.PLAYER_PLACED);

    assertEquals(BlockOrigin.PLAYER_PLACED, placed.getOrigin());
    assertEquals(Material.DEEPSLATE_DIAMOND_ORE, placed.getBrokenType());
    assertNull(placed.getPlayer());
    assertNull(placed.getBlock());
    assertNull(placed.getTool());
  }

  @Test
  public void breakEvent_ownsAHandlerListSeparateFromTheDropsEvent() {
    HandlerList handlers = HiddenOreBreakEvent.getHandlerList();

    assertSame(handlers, event(BlockOrigin.PRESUMED_GENERATED).getHandlers());
    assertNotSame(handlers, HiddenOreDropsEvent.getHandlerList());
  }

  @Test
  public void breakEvent_reportsWhetherTheBlockWasMinedOrBlownUp() {
    assertEquals(BreakCause.MINED, event(BlockOrigin.PRESUMED_GENERATED).getCause());
    assertEquals(BreakCause.EXPLODED, new HiddenOreBreakEvent(null, null, Material.STONE, null,
        BlockOrigin.PRESUMED_GENERATED, BreakCause.EXPLODED).getCause());
  }

  @Test
  public void dropsEvent_reportsTheSameCauseAndToleratesAnUnattributedBlast() {
    HiddenOreDropsEvent drops = new HiddenOreDropsEvent(null, null, Material.STONE, null, null,
        new ArrayList<>(), 3, false, BreakCause.EXPLODED);

    assertEquals(BreakCause.EXPLODED, drops.getCause());
    assertNull(drops.getPlayer());
    assertNull(drops.getTool());
    assertNull(drops.getVein());
    assertEquals(3, drops.getExperience());
    assertFalse(drops.isToInventory());
  }

  private static HiddenOreBreakEvent event(BlockOrigin origin) {
    return new HiddenOreBreakEvent(null, null, Material.DEEPSLATE_DIAMOND_ORE, null, origin, BreakCause.MINED);
  }
}
