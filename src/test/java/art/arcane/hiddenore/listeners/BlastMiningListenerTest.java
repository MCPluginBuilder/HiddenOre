package art.arcane.hiddenore.listeners;

import org.bukkit.ExplosionResult;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BlastMiningListenerTest {
  @Test
  public void entityExplosionHandler_runsAtTheAcceptedMonitorPhase() throws Exception {
    Method handler = BlastMiningListener.class.getMethod("onEntityExplode", EntityExplodeEvent.class);
    EventHandler annotation = handler.getAnnotation(EventHandler.class);

    assertEquals(EventPriority.MONITOR, annotation.priority());
    assertTrue(annotation.ignoreCancelled());
  }

  @Test
  public void blockExplosionHandler_runsAtTheAcceptedMonitorPhase() throws Exception {
    Method handler = BlastMiningListener.class.getMethod("onBlockExplode", BlockExplodeEvent.class);
    EventHandler annotation = handler.getAnnotation(EventHandler.class);

    assertEquals(EventPriority.MONITOR, annotation.priority());
    assertTrue(annotation.ignoreCancelled());
  }

  @Test
  public void paysYield_treatsTheKnobAsAnExclusiveUpperBound() {
    assertTrue(BlastMiningListener.paysYield(0.5, 0.49));
    assertFalse(BlastMiningListener.paysYield(0.5, 0.5));
    assertFalse(BlastMiningListener.paysYield(0.5, 0.51));
  }

  @Test
  public void paysYield_zeroNeverPaysAndOneAlwaysPays() {
    assertFalse(BlastMiningListener.paysYield(0.0, 0.0));
    assertTrue(BlastMiningListener.paysYield(1.0, 0.0));
    assertTrue(BlastMiningListener.paysYield(1.0, 0.999999));
  }

  @Test
  public void examinationBound_capsHugeExplosionsAndFloorsAtZero() {
    assertEquals(0, BlastMiningListener.examinationBound(0));
    assertEquals(0, BlastMiningListener.examinationBound(-1));
    assertEquals(0, BlastMiningListener.examinationBound(Integer.MIN_VALUE));
    assertEquals(37, BlastMiningListener.examinationBound(37));
    assertEquals(BlastMiningListener.MAX_EXAMINED_BLOCKS,
        BlastMiningListener.examinationBound(BlastMiningListener.MAX_EXAMINED_BLOCKS));
    assertEquals(BlastMiningListener.MAX_EXAMINED_BLOCKS,
        BlastMiningListener.examinationBound(BlastMiningListener.MAX_EXAMINED_BLOCKS + 1));
    assertEquals(BlastMiningListener.MAX_EXAMINED_BLOCKS,
        BlastMiningListener.examinationBound(Integer.MAX_VALUE));
  }

  @Test
  public void destroysBlocks_acceptsOnlyTheTwoDestructiveResults() {
    assertTrue(BlastMiningListener.destroysBlocks(ExplosionResult.DESTROY));
    assertTrue(BlastMiningListener.destroysBlocks(ExplosionResult.DESTROY_WITH_DECAY));
    assertFalse(BlastMiningListener.destroysBlocks(ExplosionResult.TRIGGER_BLOCK));
    assertFalse(BlastMiningListener.destroysBlocks(ExplosionResult.KEEP));
    assertFalse(BlastMiningListener.destroysBlocks(null));
  }

  @Test
  public void eligibility_placedBlocksLoseTheirRewardUnlessTheyAreAllowed() {
    assertEquals(BlastMiningListener.BlastEligibility.PLACED_BLOCKED,
        BlastMiningListener.eligibility(true, false, false, 1.0, 0.0));
    assertEquals(BlastMiningListener.BlastEligibility.ELIGIBLE,
        BlastMiningListener.eligibility(true, true, false, 1.0, 0.0));
    assertEquals(BlastMiningListener.BlastEligibility.ELIGIBLE,
        BlastMiningListener.eligibility(false, false, false, 1.0, 0.0));
  }

  @Test
  public void eligibility_aPlacedBlockIsRejectedBeforeAVetoOrTheYieldRoll() {
    assertEquals(BlastMiningListener.BlastEligibility.PLACED_BLOCKED,
        BlastMiningListener.eligibility(true, false, true, 0.0, 0.9));
  }

  @Test
  public void eligibility_aVetoOutranksTheYieldRoll() {
    assertEquals(BlastMiningListener.BlastEligibility.VETOED,
        BlastMiningListener.eligibility(false, false, true, 1.0, 0.0));
  }

  @Test
  public void eligibility_aLostYieldRollStillCountsAsAnEligibleBlock() {
    BlastMiningListener.BlastEligibility missed =
        BlastMiningListener.eligibility(false, false, false, 0.5, 0.5);

    assertEquals(BlastMiningListener.BlastEligibility.YIELD_MISSED, missed);
    assertTrue(missed.eligibleForCommands());
  }

  @Test
  public void eligibility_blockedAndVetoedBlocksNeverRollCommandRewards() {
    assertFalse(BlastMiningListener.BlastEligibility.PLACED_BLOCKED.eligibleForCommands());
    assertFalse(BlastMiningListener.BlastEligibility.VETOED.eligibleForCommands());
    assertTrue(BlastMiningListener.BlastEligibility.ELIGIBLE.eligibleForCommands());
  }

  @Test
  public void attributedPlayer_readsTheIgniterOfPrimedTnt() {
    Player igniter = stub(Player.class, Map.of());

    assertSame(igniter, BlastMiningListener.attributedPlayer(
        stub(TNTPrimed.class, Map.of("getSource", igniter))));
  }

  @Test
  public void attributedPlayer_readsTheShooterOfAFireball() {
    Player shooter = stub(Player.class, Map.of());

    assertSame(shooter, BlastMiningListener.attributedPlayer(
        stub(Fireball.class, Map.of("getShooter", shooter))));
  }

  @Test
  public void attributedPlayer_ignoresNonPlayerIgnitersAndUntrackedSources() {
    Creeper creeper = stub(Creeper.class, Map.of());

    assertNull(BlastMiningListener.attributedPlayer(stub(TNTPrimed.class, nullSource("getSource"))));
    assertNull(BlastMiningListener.attributedPlayer(stub(TNTPrimed.class, Map.of("getSource", creeper))));
    assertNull(BlastMiningListener.attributedPlayer(stub(Fireball.class, nullSource("getShooter"))));
    assertNull(BlastMiningListener.attributedPlayer(creeper));
    assertNull(BlastMiningListener.attributedPlayer((Entity) null));
  }

  private static Map<String, Object> nullSource(String method) {
    Map<String, Object> answers = new java.util.HashMap<>();
    answers.put(method, null);
    return answers;
  }

  private static <T> T stub(Class<T> type, Map<String, Object> answers) {
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
      if (answers.containsKey(method.getName())) {
        return answers.get(method.getName());
      }
      throw new UnsupportedOperationException(method.getName());
    };
    return type.cast(Proxy.newProxyInstance(BlastMiningListenerTest.class.getClassLoader(),
        new Class<?>[]{type}, handler));
  }
}
