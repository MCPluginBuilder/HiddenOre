package art.arcane.hiddenore.api.event;

import art.arcane.hiddenore.api.BlockOrigin;
import org.bukkit.Material;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.junit.Test;

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

  private static HiddenOreBreakEvent event(BlockOrigin origin) {
    return new HiddenOreBreakEvent(null, null, Material.DEEPSLATE_DIAMOND_ORE, null, origin);
  }
}
