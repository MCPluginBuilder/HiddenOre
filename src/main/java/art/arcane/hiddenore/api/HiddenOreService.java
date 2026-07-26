package art.arcane.hiddenore.api;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.List;

public interface HiddenOreService {
  int MAX_NEARBY_RADIUS = 128;
  int MAX_NEARBY_RESULTS = 4096;

  boolean isSeeded();

  boolean isManagedBase(Material material);

  BlockOrigin originOf(Block block);

  ChunkProvenance provenanceOf(Chunk chunk);

  boolean isVeinConsumed(Block block);

  HiddenVein veinAt(Block block);

  List<HiddenVein> veinSiblings(Block block);

  List<HiddenVein> veinsNear(Location center, int radius);

  boolean ownsRegion(World world, int chunkX, int chunkZ);
}
