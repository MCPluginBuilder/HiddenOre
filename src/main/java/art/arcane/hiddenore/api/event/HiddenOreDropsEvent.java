package art.arcane.hiddenore.api.event;

import art.arcane.hiddenore.api.BreakCause;
import art.arcane.hiddenore.api.HiddenVein;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class HiddenOreDropsEvent extends Event {
  private static final HandlerList HANDLER_LIST = new HandlerList();

  private final Player player;
  private final Block block;
  private final Material brokenType;
  private final ItemStack tool;
  private final HiddenVein vein;
  private final List<ItemStack> drops;
  private final BreakCause cause;
  private int experience;
  private boolean toInventory;

  public HiddenOreDropsEvent(Player player, Block block, Material brokenType, ItemStack tool, @Nullable HiddenVein vein,
                             List<ItemStack> drops, int experience, boolean toInventory, BreakCause cause) {
    super(false);
    this.player = player;
    this.block = block;
    this.brokenType = brokenType;
    this.tool = tool;
    this.vein = vein;
    this.drops = drops;
    this.experience = experience;
    this.toInventory = toInventory;
    this.cause = cause;
  }

  /**
   * The miner, or null when an unattributed explosion broke the block.
   */
  @Nullable
  public Player getPlayer() {
    return player;
  }

  public Block getBlock() {
    return block;
  }

  public Material getBrokenType() {
    return brokenType;
  }

  /**
   * The tool used, or null when an explosion broke the block.
   */
  @Nullable
  public ItemStack getTool() {
    return tool;
  }

  @Nullable
  public HiddenVein getVein() {
    return vein;
  }

  public List<ItemStack> getDrops() {
    return drops;
  }

  public BreakCause getCause() {
    return cause;
  }

  public int getExperience() {
    return experience;
  }

  public void setExperience(int experience) {
    this.experience = Math.max(0, experience);
  }

  /**
   * Whether the drops go to the player's inventory. Always false for an explosion reward, which
   * drops on the ground even when a listener asked for inventory delivery.
   */
  public boolean isToInventory() {
    return cause == BreakCause.MINED && toInventory;
  }

  /**
   * Requests inventory delivery. Ignored for an explosion reward.
   */
  public void setToInventory(boolean toInventory) {
    this.toInventory = toInventory;
  }

  @NotNull
  @Override
  public HandlerList getHandlers() {
    return HANDLER_LIST;
  }

  public static HandlerList getHandlerList() {
    return HANDLER_LIST;
  }
}
