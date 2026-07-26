package art.arcane.hiddenore.api;

import art.arcane.volmlib.util.bukkit.ChunkPositionSet;
import org.bukkit.Chunk;

import java.util.Arrays;

final class PlacementProvenance implements ChunkProvenance {
  private static final int[] EMPTY = new int[0];

  private final Chunk chunk;
  private final int[] positions;
  private final int baseX;
  private final int baseZ;
  private final int minHeight;
  private final int maxHeight;

  PlacementProvenance(Chunk chunk, int chunkX, int chunkZ, int minHeight, int maxHeight, int[] positions) {
    this.chunk = chunk;
    this.baseX = chunkX << 4;
    this.baseZ = chunkZ << 4;
    this.minHeight = minHeight;
    this.maxHeight = maxHeight;
    this.positions = positions == null || positions.length == 0 ? EMPTY : positions.clone();
  }

  @Override
  public Chunk chunk() {
    return chunk;
  }

  @Override
  public boolean contains(int worldX, int worldY, int worldZ) {
    if (worldX < baseX || worldX > baseX + 15 || worldZ < baseZ || worldZ > baseZ + 15) {
      return false;
    }
    if (worldY < minHeight || worldY >= maxHeight) {
      return false;
    }
    if (positions.length == 0) {
      return false;
    }
    return Arrays.binarySearch(positions, ChunkPositionSet.pack(worldX & 15, worldY, worldZ & 15, minHeight)) >= 0;
  }

  @Override
  public int size() {
    return positions.length;
  }

  @Override
  public boolean isEmpty() {
    return positions.length == 0;
  }
}
