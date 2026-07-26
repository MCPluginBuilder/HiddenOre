package art.arcane.hiddenore.api;

import org.bukkit.Chunk;

public interface ChunkProvenance {
  Chunk chunk();

  boolean contains(int worldX, int worldY, int worldZ);

  int size();

  boolean isEmpty();
}
