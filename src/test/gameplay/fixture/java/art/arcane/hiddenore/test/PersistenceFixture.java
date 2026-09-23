package art.arcane.hiddenore.test;

import art.arcane.hiddenore.api.HiddenOreService;
import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

public final class PersistenceFixture extends JavaPlugin {
    private static final String WORLD = "hiddenore_persistence_qa";
    private final String boot = UUID.randomUUID().toString();
    private final Gson gson = new Gson();
    private State state;
    private World world;
    private HiddenOreService service;

    @Override
    public void onEnable() {
        try {
            if (!Files.readAllLines(Path.of(".server-source")).contains("isolated=true")
                    || !Bukkit.getIp().equals("127.0.0.1") || Bukkit.getOnlineMode()) throw new IllegalStateException("Isolated offline loopback required");
            service = Bukkit.getServicesManager().load(HiddenOreService.class);
            if (service == null || !service.isSeeded()) throw new IllegalStateException("Seeded HiddenOre required");
            Path file = getDataFolder().toPath().resolve("fixture.json");
            if (Files.exists(file)) state = gson.fromJson(Files.readString(file), State.class);
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "Persistence fixture startup failed", failure);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private World world() {
        if (world == null) world = new WorldCreator(WORLD).seed(424242L).generator(new ChunkGenerator() {
            @Override
            public ChunkData generateChunkData(World target, Random random, int x, int z, BiomeGrid biome) { return createChunkData(target); }
        }).createWorld();
        if (world == null) throw new IllegalStateException("Fixture world unavailable");
        return world;
    }

    private Player actor() {
        Player player = state == null ? null : Bukkit.getPlayerExact(state.actor());
        if (player == null || player.isOp() || !player.getName().startsWith("HQA")) throw new IllegalStateException("Ordinary HQA player required");
        return player;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player admin) || !admin.isOp() || args.length < 1) return false;
        try {
            switch (args[0]) {
                case "setup" -> {
                    if (state != null || args.length != 2) throw new IllegalStateException("Use a fresh isolated instance for preparation");
                    world();
                    for (int x = -5; x <= 20; x++) for (int z = -5; z <= 20; z++) world.getBlockAt(x, 99, z).setType(Material.STONE, false);
                    List<Point> points = new ArrayList<>();
                    for (int x = 2; x <= 13; x++) for (int z = 2; z <= 13; z++) {
                        world.getBlockAt(x, 100, z).setType(Material.STONE, false);
                        if (points.size() < 3 && service.veinAt(world.getBlockAt(x, 100, z)) != null) points.add(new Point(x, z));
                    }
                    if (points.size() != 3) throw new IllegalStateException("Seed has fewer than three usable fixture veins");
                    for (int x = 2; x <= 13; x++) for (int z = 2; z <= 13; z++) world.getBlockAt(x, 100, z).setType(Material.AIR, false);
                    state = new State(args[1], world.getUID().toString(), points);
                    actor().setGameMode(GameMode.SURVIVAL);
                    actor().getInventory().clear();
                    actor().getInventory().setItem(0, new ItemStack(Material.IRON_PICKAXE));
                    actor().getInventory().setItem(1, new ItemStack(Material.STONE, 8));
                    for (int index : List.of(0, 2)) block(index).setType(Material.STONE, false);
                    Files.createDirectories(getDataFolder().toPath());
                    Files.writeString(getDataFolder().toPath().resolve("fixture.json"), gson.toJson(state));
                    admin.setGameMode(GameMode.SPECTATOR);
                    admin.teleport(new Location(world, 5, 105, 5));
                }
                case "target" -> {
                    Point point = state.points().get(Integer.parseInt(args[1]));
                    actor().teleport(new Location(world(), point.x() - 2.5, 100, point.z() + .5, -90, 0));
                }
                case "restore" -> block(0).setType(Material.STONE, false);
                case "overflow" -> {
                    actor().getInventory().clear();
                    for (int slot = 0; slot < 36; slot++) actor().getInventory().setItem(slot, new ItemStack(Material.COBBLESTONE, 64));
                    actor().getInventory().setItem(0, new ItemStack(Material.IRON_PICKAXE));
                }
                case "space" -> actor().getInventory().setItem(35, null);
                case "snapshot" -> { }
                default -> throw new IllegalArgumentException("Unknown fixture command");
            }
            sender.sendMessage("HIDDENORE_QA " + args[0] + " " + gson.toJson(snapshot()));
        } catch (Exception failure) {
            sender.sendMessage("HIDDENORE_QA ERROR " + failure.getMessage());
            getLogger().log(Level.WARNING, "Fixture command failed", failure);
        }
        return true;
    }

    private org.bukkit.block.Block block(int index) {
        Point point = state.points().get(index);
        return world().getBlockAt(point.x(), 100, point.z());
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("boot", boot);
        result.put("world", world().getUID().toString());
        result.put("actor", actor().getName());
        result.put("operator", actor().isOp());
        result.put("points", state.points());
        result.put("consumed", service.isVeinConsumed(block(0)));
        result.put("placed", service.provenanceOf(block(1).getChunk()).contains(block(1).getX(), 100, block(1).getZ()));
        result.put("diamonds", count(Material.DIAMOND));
        result.put("cobblestone", count(Material.COBBLESTONE));
        result.put("groundDiamonds", world.getEntitiesByClass(Item.class).stream().filter(item -> item.getItemStack().getType() == Material.DIAMOND).mapToInt(item -> item.getItemStack().getAmount()).sum());
        return result;
    }

    private int count(Material material) {
        int total = 0;
        for (ItemStack item : actor().getInventory().getContents()) if (item != null && item.getType() == material) total += item.getAmount();
        return total;
    }

    private record Point(int x, int z) { }
    private record State(String actor, String world, List<Point> points) { }
}
