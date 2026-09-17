package art.arcane.hiddenore.vein;

import art.arcane.hiddenore.rules.ItemDropRule;
import art.arcane.hiddenore.util.project.ToolTier;
import art.arcane.volmlib.util.bukkit.ChunkPositionSet;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeededVeinGeneratorTest {
  private static final int MIN_HEIGHT = -64;
  private static final int MAX_HEIGHT = 320;

  private static List<ItemDropRule> rules() {
    return List.of(
        new ItemDropRule(Material.COAL, 2.2, 5, 20, 0, 320, true, Set.of(ToolTier.WOODEN_PICKAXE), 2),
        new ItemDropRule(Material.RAW_IRON, 1.8, 4, 12, -64, 320, false, Set.of(ToolTier.STONE_PICKAXE), 0),
        new ItemDropRule(Material.DIAMOND, 0.5, 3, 8, -64, 16, true, Set.of(ToolTier.IRON_PICKAXE), 7)
    );
  }

  private static Map<Integer, Material> flatten(ChunkVeins veins) {
    Map<Integer, Material> result = new HashMap<>();
    for (VeinBlock block : veins.blocks()) {
      result.put(block.packedPosition(), block.rule().material);
    }
    return result;
  }

  @Test
  public void test_compute_sameInputs_produceIdenticalVeins() {
    for (int chunkX = -3; chunkX <= 3; chunkX++) {
      for (int chunkZ = -3; chunkZ <= 3; chunkZ++) {
        ChunkVeins first = SeededVeinGenerator.compute(1234567L, MIN_HEIGHT, MAX_HEIGHT, chunkX, chunkZ, rules());
        ChunkVeins second = SeededVeinGenerator.compute(1234567L, MIN_HEIGHT, MAX_HEIGHT, chunkX, chunkZ, rules());
        assertEquals(flatten(first), flatten(second));
      }
    }
  }

  @Test
  public void test_compute_blocksStayWithinChunkAndRuleYRange() {
    for (int chunkX = -5; chunkX <= 5; chunkX++) {
      for (int chunkZ = -5; chunkZ <= 5; chunkZ++) {
        ChunkVeins veins = SeededVeinGenerator.compute(987654321L, MIN_HEIGHT, MAX_HEIGHT, chunkX, chunkZ, rules());
        for (VeinBlock block : veins.blocks()) {
          int localX = ChunkPositionSet.unpackLocalX(block.packedPosition());
          int localZ = ChunkPositionSet.unpackLocalZ(block.packedPosition());
          int y = ChunkPositionSet.unpackY(block.packedPosition(), MIN_HEIGHT);
          assertTrue(localX >= 0 && localX <= 15);
          assertTrue(localZ >= 0 && localZ <= 15);
          assertTrue(y >= Math.max(block.rule().minY, MIN_HEIGHT));
          assertTrue(y <= Math.min(block.rule().maxY, MAX_HEIGHT - 1));
        }
      }
    }
  }

  @Test
  public void test_compute_veinSizesWithinConfiguredMaximum() {
    for (int chunkX = 0; chunkX < 10; chunkX++) {
      ChunkVeins veins = SeededVeinGenerator.compute(42L, MIN_HEIGHT, MAX_HEIGHT, chunkX, 0, rules());
      for (VeinBlock block : veins.blocks()) {
        int size = veins.positionsOf(block.veinId()).length;
        assertTrue(size >= 1);
        assertTrue(size <= block.rule().veinMaxSize);
      }
    }
  }

  @Test
  public void test_compute_differentSeeds_produceDifferentVeins() {
    boolean anyDifference = false;
    for (int chunkX = 0; chunkX < 20 && !anyDifference; chunkX++) {
      ChunkVeins first = SeededVeinGenerator.compute(1L, MIN_HEIGHT, MAX_HEIGHT, chunkX, 0, rules());
      ChunkVeins second = SeededVeinGenerator.compute(2L, MIN_HEIGHT, MAX_HEIGHT, chunkX, 0, rules());
      anyDifference = !flatten(first).equals(flatten(second));
    }
    assertTrue(anyDifference);
  }

  @Test
  public void test_compute_reorderedRules_preserveVeinPositions() {
    List<ItemDropRule> original = rules();
    List<ItemDropRule> reordered = List.of(original.get(2), original.get(0), original.get(1));

    for (int chunkX = -5; chunkX <= 5; chunkX++) {
      for (int chunkZ = -5; chunkZ <= 5; chunkZ++) {
        ChunkVeins first = SeededVeinGenerator.compute(843921L, MIN_HEIGHT, MAX_HEIGHT, chunkX, chunkZ, original);
        ChunkVeins second = SeededVeinGenerator.compute(843921L, MIN_HEIGHT, MAX_HEIGHT, chunkX, chunkZ, reordered);
        assertEquals(flatten(first), flatten(second));
      }
    }
  }

  @Test
  public void test_compute_insertedAndDeletedUnrelatedRule_preservesRetainedVeinPositions() {
    List<ItemDropRule> retained = stableRules();
    ItemDropRule unrelated = new ItemDropRule(Material.EMERALD, 3.0, 1, 1, 3, 3, false,
        Set.of(ToolTier.IRON_PICKAXE), 4);
    List<ItemDropRule> inserted = List.of(retained.get(0), unrelated, retained.get(1), retained.get(2));

    for (int chunkX = -5; chunkX <= 5; chunkX++) {
      for (int chunkZ = -5; chunkZ <= 5; chunkZ++) {
        ChunkVeins before = SeededVeinGenerator.compute(843921L, MIN_HEIGHT, MAX_HEIGHT, chunkX, chunkZ, retained);
        ChunkVeins afterInsertion = SeededVeinGenerator.compute(843921L, MIN_HEIGHT, MAX_HEIGHT, chunkX, chunkZ, inserted);
        assertEquals(flatten(before), withoutMaterial(afterInsertion, Material.EMERALD));
      }
    }
  }

  @Test
  public void test_compute_duplicateRule_addsStableIndependentVeins() {
    ItemDropRule rule = new ItemDropRule(Material.COAL, 4.0, 1, 1, 0, 0, false,
        Set.of(ToolTier.WOODEN_PICKAXE), 0);
    int singlePositions = 0;
    int duplicatePositions = 0;

    for (int chunkX = -10; chunkX <= 10; chunkX++) {
      ChunkVeins single = SeededVeinGenerator.compute(74123L, MIN_HEIGHT, MAX_HEIGHT, chunkX, 0, List.of(rule));
      ChunkVeins duplicate = SeededVeinGenerator.compute(74123L, MIN_HEIGHT, MAX_HEIGHT, chunkX, 0, List.of(rule, rule));
      Set<Integer> singleCoal = positionsFor(single, Material.COAL);
      Set<Integer> duplicateCoal = positionsFor(duplicate, Material.COAL);
      assertTrue(duplicateCoal.containsAll(singleCoal));
      singlePositions += singleCoal.size();
      duplicatePositions += duplicateCoal.size();
    }

    assertTrue(duplicatePositions > singlePositions);
  }

  @Test
  public void test_compute_nonSpatialRuleChanges_preserveVeinPositions() {
    ItemDropRule original = new ItemDropRule(Material.COAL, 3.5, 2, 5, -32, 48, false,
        Set.of(ToolTier.WOODEN_PICKAXE), 0);
    ItemDropRule changedPayout = new ItemDropRule(Material.COAL, 3.5, 2, 5, -32, 48, true,
        Set.of(ToolTier.NETHERITE_PICKAXE), 1000);

    for (int chunkX = -5; chunkX <= 5; chunkX++) {
      for (int chunkZ = -5; chunkZ <= 5; chunkZ++) {
        ChunkVeins before = SeededVeinGenerator.compute(918273L, MIN_HEIGHT, MAX_HEIGHT, chunkX, chunkZ,
            List.of(original));
        ChunkVeins after = SeededVeinGenerator.compute(918273L, MIN_HEIGHT, MAX_HEIGHT, chunkX, chunkZ,
            List.of(changedPayout));
        assertEquals(flatten(before), flatten(after));
      }
    }
  }

  @Test
  public void test_compute_emptyRules_returnsEmptyVeins() {
    ChunkVeins veins = SeededVeinGenerator.compute(7L, MIN_HEIGHT, MAX_HEIGHT, 0, 0, List.of());
    assertTrue(veins.isEmpty());
    assertFalse(veins.blocks().iterator().hasNext());
  }

  @Test
  public void test_constructor_mutatingCallerList_doesNotChangeGeneratedVeins() {
    List<ItemDropRule> mutableRules = new ArrayList<>(rules());
    SeededVeinGenerator generator = new SeededVeinGenerator(mutableRules);
    ChunkVeins expected = generator.compute(1234567L, MIN_HEIGHT, MAX_HEIGHT, 0, 0);

    mutableRules.clear();

    ChunkVeins actual = generator.compute(1234567L, MIN_HEIGHT, MAX_HEIGHT, 0, 0);
    assertFalse(expected.isEmpty());
    assertEquals(flatten(expected), flatten(actual));
  }

  private static List<ItemDropRule> stableRules() {
    return List.of(
        new ItemDropRule(Material.COAL, 3.0, 1, 1, 0, 0, true, Set.of(ToolTier.WOODEN_PICKAXE), 2),
        new ItemDropRule(Material.RAW_IRON, 3.0, 1, 1, 1, 1, false, Set.of(ToolTier.STONE_PICKAXE), 0),
        new ItemDropRule(Material.DIAMOND, 3.0, 1, 1, 2, 2, true, Set.of(ToolTier.IRON_PICKAXE), 7)
    );
  }

  private static Map<Integer, Material> withoutMaterial(ChunkVeins veins, Material material) {
    Map<Integer, Material> retained = flatten(veins);
    retained.values().removeIf(material::equals);
    return retained;
  }

  private static Set<Integer> positionsFor(ChunkVeins veins, Material material) {
    Set<Integer> positions = new HashSet<>();
    for (VeinBlock block : veins.blocks()) {
      if (block.rule().material == material) {
        positions.add(block.packedPosition());
      }
    }
    return positions;
  }

  @Test
  public void get_denseChunksAreEvictedOnTheirPositionCountNotJustTheirChunkCount() {
    List<ItemDropRule> dense = List.of(rule(Material.COAL, 64.0, 16, 16, 0, 320));
    SeededVeinGenerator generator = new SeededVeinGenerator(dense);
    World world = worldStub(UUID.randomUUID(), 4242L, -64, 320);

    int perChunk = generator.get(world, 0, 0).size();
    assertTrue("a dense rule should fill a chunk", perChunk > 500);

    for (int chunk = 1; chunk < 3000; chunk++) {
      generator.get(world, chunk % 60, chunk / 60);
    }

    assertTrue("cache must stay under the position budget", generator.cachedPositionCount() <= 1_000_000L);
    assertTrue("eviction must have kicked in before the chunk ceiling",
        generator.cachedChunkCount() < 3000);
  }

  @Test
  public void get_sparseChunksStillFillTheChunkCache() {
    List<ItemDropRule> sparse = List.of(rule(Material.EMERALD, 0.35, 1, 2, -16, 320));
    SeededVeinGenerator generator = new SeededVeinGenerator(sparse);
    World world = worldStub(UUID.randomUUID(), 99L, -64, 320);

    for (int chunk = 0; chunk < 600; chunk++) {
      generator.get(world, chunk % 30, chunk / 30);
    }

    assertEquals(600, generator.cachedChunkCount());
    assertTrue(generator.cachedPositionCount() < 1_000_000L);
  }

  private static ItemDropRule rule(Material material, double veinsPerChunk, int minSize, int maxSize, int minY,
                                   int maxY) {
    return new ItemDropRule(material, veinsPerChunk, minSize, maxSize, minY, maxY, false,
        Set.of(ToolTier.IRON_PICKAXE), 0);
  }

  private static World worldStub(UUID id, long seed, int minHeight, int maxHeight) {
    InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
      case "getUID" -> id;
      case "getSeed" -> seed;
      case "getMinHeight" -> minHeight;
      case "getMaxHeight" -> maxHeight;
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      case "toString" -> "world-stub";
      default -> throw new UnsupportedOperationException(method.getName());
    };
    return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, handler);
  }
}
