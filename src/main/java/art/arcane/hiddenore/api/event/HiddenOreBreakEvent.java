package art.arcane.hiddenore.api.event;

import art.arcane.hiddenore.api.BlockOrigin;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

public final class HiddenOreBreakEvent extends Event implements Cancellable {
  private static final HandlerList HANDLER_LIST = new HandlerList();

  private final Player player;
  private final Block block;
  private final Material brokenType;
  private final ItemStack tool;
  private final BlockOrigin origin;
  private boolean cancelled;

  public HiddenOreBreakEvent(Player player, Block block, Material brokenType, ItemStack tool, BlockOrigin origin) {
    super(false);
    this.player = player;
    this.block = block;
    this.brokenType = brokenType;
    this.tool = tool;
    this.origin = origin;
  }

  public Player getPlayer() {
    return player;
  }

  public Block getBlock() {
    return block;
  }

  public Material getBrokenType() {
    return brokenType;
  }

  public ItemStack getTool() {
    return tool;
  }

  public BlockOrigin getOrigin() {
    return origin;
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public void setCancelled(boolean cancel) {
    cancelled = cancel;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLER_LIST;
  }

  public static HandlerList getHandlerList() {
    return HANDLER_LIST;
  }
}
