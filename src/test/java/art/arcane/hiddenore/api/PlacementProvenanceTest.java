package art.arcane.hiddenore.api;

import art.arcane.volmlib.util.bukkit.ChunkPositionSet;
import org.bukkit.Chunk;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PlacementProvenanceTest {
  private static final int MIN_HEIGHT = -64;
  private static final int MAX_HEIGHT = 320;
  private static final int CHUNK_X = 3;
  private static final int CHUNK_Z = -2;

  @Test
  public void contains_answersRecordedPlacementsWithoutTouchingTheChunk() {
    ChunkProvenance provenance = provenance(packed(4, 12, 7), packed(9, -30, 1));

    assertTrue(provenance.contains(worldX(4), 12, worldZ(7)));
    assertTrue(provenance.contains(worldX(9), -30, worldZ(1)));
    assertFalse(provenance.contains(worldX(5), 12, worldZ(7)));
    assertFalse(provenance.contains(worldX(4), 13, worldZ(7)));
  }

  @Test
  public void contains_answersFalseForColumnsOutsideItsOwnChunkInsteadOfThrowing() {
    ChunkProvenance provenance = provenance(packed(0, 0, 0));

    assertFalse(provenance.contains(worldX(0) - 1, 0, worldZ(0)));
    assertFalse(provenance.contains(worldX(15) + 1, 0, worldZ(0)));
    assertFalse(provenance.contains(worldX(0), 0, worldZ(0) - 1));
    assertFalse(provenance.contains(worldX(0), 0, worldZ(15) + 1));
  }

  @Test
  public void contains_answersFalseOutsideWorldHeightInsteadOfThrowing() {
    ChunkProvenance provenance = provenance(packed(0, 0, 0));

    assertFalse(provenance.contains(worldX(0), MIN_HEIGHT - 1, worldZ(0)));
    assertFalse(provenance.contains(worldX(0), MAX_HEIGHT, worldZ(0)));
    assertFalse(provenance.contains(worldX(0), Integer.MIN_VALUE, worldZ(0)));
    assertFalse(provenance.contains(worldX(0), Integer.MAX_VALUE, worldZ(0)));
    assertTrue(provenance.contains(worldX(0), 0, worldZ(0)));
  }

  @Test
  public void contains_survivesAThreeByThreeChunkWalkWithoutThrowing() {
    ChunkProvenance provenance = provenance(packed(0, 0, 0));
    int recorded = 0;

    for (int offsetX = -16; offsetX <= 31; offsetX++) {
      for (int offsetZ = -16; offsetZ <= 31; offsetZ++) {
        if (provenance.contains(worldX(offsetX), 0, worldZ(offsetZ))) {
          recorded++;
        }
      }
    }

    assertEquals(1, recorded);
  }

  @Test
  public void contains_neverThrowsForAnyCoordinateItIsAsked() {
    ChunkProvenance provenance = provenance(packed(0, 0, 0));

    assertFalse(provenance.contains(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE));
    assertFalse(provenance.contains(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    assertFalse(provenance.contains(Integer.MIN_VALUE, 0, Integer.MAX_VALUE));
  }

  @Test
  public void construction_copiesThePositionArrayItWasHanded() {
    int[] source = new int[]{packed(4, 12, 7)};
    ChunkProvenance provenance = new PlacementProvenance(null, CHUNK_X, CHUNK_Z, MIN_HEIGHT, MAX_HEIGHT, source);

    source[0] = packed(1, 1, 1);

    assertTrue(provenance.contains(worldX(4), 12, worldZ(7)));
    assertFalse(provenance.contains(worldX(1), 1, worldZ(1)));
  }

  @Test
  public void sizeAndEmptiness_reportTheSnapshotWithoutExposingIt() {
    assertTrue(provenance().isEmpty());
    assertEquals(0, provenance().size());
    assertFalse(provenance(packed(0, 0, 0), packed(1, 1, 1)).isEmpty());
    assertEquals(2, provenance(packed(0, 0, 0), packed(1, 1, 1)).size());
  }

  @Test
  public void emptyProvenance_answersFalseInsideAndOutsideItsOwnChunk() {
    assertFalse(provenance().contains(worldX(0), 0, worldZ(0)));
    assertFalse(provenance().contains(worldX(0) - 1, 0, worldZ(0)));
  }

  @Test
  public void chunk_returnsTheChunkTheSnapshotWasTakenFrom() {
    Chunk chunk = stubChunk();
    ChunkProvenance provenance = new PlacementProvenance(chunk, CHUNK_X, CHUNK_Z, MIN_HEIGHT, MAX_HEIGHT, new int[0]);

    assertSame(chunk, provenance.chunk());
  }

  private static ChunkProvenance provenance(int... positions) {
    int[] sorted = positions.clone();
    Arrays.sort(sorted);
    return new PlacementProvenance(null, CHUNK_X, CHUNK_Z, MIN_HEIGHT, MAX_HEIGHT, sorted);
  }

  private static int packed(int localX, int y, int localZ) {
    return ChunkPositionSet.pack(localX, y, localZ, MIN_HEIGHT);
  }

  private static int worldX(int localX) {
    return (CHUNK_X << 4) + localX;
  }

  private static int worldZ(int localZ) {
    return (CHUNK_Z << 4) + localZ;
  }

  private static Chunk stubChunk() {
    InvocationHandler handler = (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
      case "getX" -> CHUNK_X;
      case "getZ" -> CHUNK_Z;
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == arguments[0];
      case "toString" -> "stub-chunk";
      default -> null;
    };
    return (Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(), new Class<?>[]{Chunk.class}, handler);
  }
}
