package art.arcane.hiddenore.vein;

import art.arcane.hiddenore.rules.ItemDropRule;
import art.arcane.hiddenore.service.HiddenOreTelemetry;
import art.arcane.volmlib.util.bukkit.ChunkPositionSet;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class SeededVeinGenerator {
  private static final int CACHE_LIMIT = 4096;
  private static final int[][] WALK_DIRECTIONS = {
      {1, 0, 0}, {-1, 0, 0},
      {0, 1, 0}, {0, -1, 0},
      {0, 0, 1}, {0, 0, -1}
  };

  private final List<SeededRule> rules;
  private final Map<UUID, Map<Long, ChunkVeins>> cache = new HashMap<>();

  public SeededVeinGenerator(List<ItemDropRule> rules) {
    this.rules = prepareRules(rules);
  }

  public ChunkVeins get(World world, int chunkX, int chunkZ) {
    long chunkKey = (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    synchronized (cache) {
      Map<Long, ChunkVeins> worldCache = cache.computeIfAbsent(world.getUID(), ignored ->
          new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, ChunkVeins> eldest) {
              return size() > CACHE_LIMIT;
            }
          });
      ChunkVeins cached = worldCache.get(chunkKey);
      if (cached != null) {
        return cached;
      }
      HiddenOreTelemetry.countVeinChunkCompute();
      ChunkVeins computed = compute(world.getSeed(), world.getMinHeight(), world.getMaxHeight(), chunkX, chunkZ);
      worldCache.put(chunkKey, computed);
      return computed;
    }
  }

  public int cachedChunkCount() {
    synchronized (cache) {
      int total = 0;
      for (Map<Long, ChunkVeins> worldCache : cache.values()) {
        total += worldCache.size();
      }
      return total;
    }
  }

  public void clearWorld(UUID worldId) {
    synchronized (cache) {
      cache.remove(worldId);
    }
  }

  ChunkVeins compute(long worldSeed, int minHeight, int maxHeight, int chunkX, int chunkZ) {
    return computePrepared(worldSeed, minHeight, maxHeight, chunkX, chunkZ, rules);
  }

  public static ChunkVeins compute(long worldSeed, int minHeight, int maxHeight, int chunkX, int chunkZ, List<ItemDropRule> rules) {
    return computePrepared(worldSeed, minHeight, maxHeight, chunkX, chunkZ, prepareRules(rules));
  }

  private static ChunkVeins computePrepared(long worldSeed, int minHeight, int maxHeight, int chunkX, int chunkZ,
                                             List<SeededRule> rules) {
    Map<Integer, VeinBlock> blocksByPosition = new HashMap<>();
    Map<Integer, int[]> positionsByVein = new HashMap<>();
    int nextVeinId = 0;

    for (SeededRule seededRule : rules) {
      ItemDropRule rule = seededRule.rule();

      int yLow = Math.max(rule.minY, minHeight);
      int yHigh = Math.min(rule.maxY, maxHeight - 1);
      if (yLow > yHigh) {
        continue;
      }

      Random rng = new Random(mix(worldSeed, chunkX, chunkZ, seededRule.seedSalt()));
      int veinCount = (int) rule.veinsPerChunk;
      double fraction = rule.veinsPerChunk - veinCount;
      if (rng.nextDouble() < fraction) {
        veinCount++;
      }

      for (int vein = 0; vein < veinCount; vein++) {
        int size = rule.veinMinSize + (rule.veinMaxSize > rule.veinMinSize ? rng.nextInt(rule.veinMaxSize - rule.veinMinSize + 1) : 0);
        int x = rng.nextInt(16);
        int z = rng.nextInt(16);
        int y = yLow + rng.nextInt(yHigh - yLow + 1);

        int veinId = nextVeinId++;
        List<Integer> positions = new ArrayList<>(size);
        claim(blocksByPosition, positions, rule, veinId, x, y, z, minHeight);

        int attempts = size * 4;
        while (positions.size() < size && attempts-- > 0) {
          int[] direction = WALK_DIRECTIONS[rng.nextInt(WALK_DIRECTIONS.length)];
          int nx = x + direction[0];
          int ny = y + direction[1];
          int nz = z + direction[2];
          if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < yLow || ny > yHigh) {
            continue;
          }
          x = nx;
          y = ny;
          z = nz;
          claim(blocksByPosition, positions, rule, veinId, x, y, z, minHeight);
        }

        if (positions.isEmpty()) {
          continue;
        }
        int[] packed = new int[positions.size()];
        for (int i = 0; i < packed.length; i++) {
          packed[i] = positions.get(i);
        }
        positionsByVein.put(veinId, packed);
      }
    }

    if (blocksByPosition.isEmpty()) {
      return ChunkVeins.EMPTY;
    }
    return new ChunkVeins(blocksByPosition, positionsByVein);
  }

  private static void claim(Map<Integer, VeinBlock> blocksByPosition, List<Integer> positions, ItemDropRule rule, int veinId, int x, int y, int z, int minHeight) {
    int packed = ChunkPositionSet.pack(x, y, z, minHeight);
    if (blocksByPosition.containsKey(packed)) {
      return;
    }
    blocksByPosition.put(packed, new VeinBlock(packed, veinId, rule));
    positions.add(packed);
  }

  private static List<SeededRule> prepareRules(List<ItemDropRule> rules) {
    Map<String, Integer> duplicateCounts = new HashMap<>();
    List<SeededRule> prepared = new ArrayList<>(rules.size());
    for (ItemDropRule rule : rules) {
      if (rule.type != ItemDropRule.DropType.ITEM || rule.veinsPerChunk <= 0.0) {
        continue;
      }
      String identity = stableIdentity(rule);
      int duplicateIndex = duplicateCounts.getOrDefault(identity, 0);
      duplicateCounts.put(identity, duplicateIndex + 1);
      prepared.add(new SeededRule(rule, identity, duplicateIndex, identitySalt(identity, duplicateIndex)));
    }
    prepared.sort(Comparator.comparing(SeededRule::identity).thenComparingInt(SeededRule::duplicateIndex));
    return List.copyOf(prepared);
  }

  private static String stableIdentity(ItemDropRule rule) {
    StringBuilder identity = new StringBuilder(96);
    identity.append(rule.type.name()).append('|')
        .append(rule.material.name()).append('|')
        .append(Double.toHexString(rule.veinsPerChunk)).append('|')
        .append(rule.veinMinSize).append('|')
        .append(rule.veinMaxSize).append('|')
        .append(rule.minY).append('|')
        .append(rule.maxY);
    return identity.toString();
  }

  private static long identitySalt(String identity, int duplicateIndex) {
    long hash = 0xCBF29CE484222325L;
    for (int index = 0; index < identity.length(); index++) {
      hash ^= identity.charAt(index);
      hash *= 0x100000001B3L;
    }
    hash ^= duplicateIndex * 0xD6E8FEB86659FD93L;
    return splitmix(hash);
  }

  private static long mix(long worldSeed, int chunkX, int chunkZ, long ruleSeedSalt) {
    long hash = worldSeed;
    hash ^= chunkX * 0x9E3779B97F4A7C15L;
    hash = splitmix(hash);
    hash ^= chunkZ * 0xC2B2AE3D27D4EB4FL;
    hash = splitmix(hash);
    hash ^= ruleSeedSalt;
    return splitmix(hash);
  }

  private static long splitmix(long value) {
    long result = value + 0x9E3779B97F4A7C15L;
    result = (result ^ (result >>> 30)) * 0xBF58476D1CE4E5B9L;
    result = (result ^ (result >>> 27)) * 0x94D049BB133111EBL;
    return result ^ (result >>> 31);
  }

  private record SeededRule(ItemDropRule rule, String identity, int duplicateIndex, long seedSalt) {
  }
}
