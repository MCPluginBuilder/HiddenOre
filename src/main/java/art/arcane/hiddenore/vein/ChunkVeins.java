package art.arcane.hiddenore.vein;

import art.arcane.volmlib.util.bukkit.ChunkPositionSet;

import java.util.Collection;
import java.util.Map;

public final class ChunkVeins {
  public static final ChunkVeins EMPTY = new ChunkVeins(Map.of(), Map.of());

  private final Map<Integer, VeinBlock> blocksByPosition;
  private final Map<Integer, int[]> positionsByVein;

  public ChunkVeins(Map<Integer, VeinBlock> blocksByPosition, Map<Integer, int[]> positionsByVein) {
    this.blocksByPosition = blocksByPosition;
    this.positionsByVein = positionsByVein;
  }

  public VeinBlock get(int packedPosition) {
    return blocksByPosition.get(packedPosition);
  }

  public Collection<VeinBlock> blocks() {
    return blocksByPosition.values();
  }

  public int[] positionsOf(int veinId) {
    int[] positions = positionsByVein.get(veinId);
    return positions == null ? new int[0] : positions;
  }

  /**
   * Whether no other block of this vein has been claimed yet, given the chunk's claimed positions.
   */
  public boolean isFirstOfVein(int veinId, int packedPosition, int[] claimedPositions) {
    if (claimedPositions.length == 0) {
      return true;
    }
    for (int position : positionsOf(veinId)) {
      if (position != packedPosition && ChunkPositionSet.contains(claimedPositions, position)) {
        return false;
      }
    }
    return true;
  }

  public int size() {
    return blocksByPosition.size();
  }

  public boolean isEmpty() {
    return blocksByPosition.isEmpty();
  }
}
