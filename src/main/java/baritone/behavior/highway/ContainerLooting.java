/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior.highway;

import baritone.behavior.highway.enums.ShulkerType;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnderChestBlock;

/**
 * Moves items between an open container and the inventory, a slot or a stack per call.
 */
public class ContainerLooting extends ContextComponent {
    ContainerLooting(HighwayContext ctx) {
        super(ctx);
    }

    private int getDepletedPickSlot() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.is(ItemTags.PICKAXES) && (stack.getDamageValue() + settings.itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                return i;
            }
        }

        return -1;
    }

    public int lootPickaxeChestSlot() {
        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            // Don't loot depleted picks if we're using item saver mode, or picks that wouldn't count
            if (ctx.inventory().isUsablePick(curContainer.getSlot(i).getItem())) {
                int swapSlot = settings.itemSaver.value ? getDepletedPickSlot() : -1;

                // No depleted picks
                if (swapSlot == -1) {
                    int airSlot = ctx.inventory().getItemSlot(Item.getId(Items.AIR));
                    if (airSlot != -1) {
                        playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, playerContext.player());
                        return 1;
                    }

                    swapSlot = ctx.inventory().getThrowawaySlotToToss();
                    if (swapSlot == -1) {
                        // Also didn't find any throwaway items
                        return 0;
                    }
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                    playerContext.playerController().windowClick(curContainer.containerId, swapSlot < 9 ? swapSlot + 54 : swapSlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                    playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player()); // Throw away throwaway item
                } else {
                    // Swap new pickaxe with depleted
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                    playerContext.playerController().windowClick(curContainer.containerId, swapSlot < 9 ? swapSlot + 54 : swapSlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player()); // Put depleted pick in looted slot
                }
                return 1;
            }
        }

        return 0;
    }

    /**
     * With an open supply shulker box, pull one slot's worth of the item into the inventory. A quick
     * move tops up a stack we already carry before it takes a free slot, so only with neither (totems
     * never stack) is a throwaway stack swapped out for it and dropped. Returns the size of the slot
     * taken from (0 when none left, or no room can be made).
     */
    public int lootSupplyChestSlot(Item item) {
        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getItem().is(item)) {
                int count = curContainer.getSlot(i).getItem().getCount();

                if (ctx.inventory().getItemSlot(Item.getId(Items.AIR)) == -1 && !hasRoomOnStack(item)) {
                    // No room for it, so throw out some throwaway items to make room
                    int throwawaySlot = ctx.inventory().getThrowawaySlotToToss();
                    if (throwawaySlot == -1) {
                        return 0;
                    }
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                    playerContext.playerController().windowClick(curContainer.containerId, throwawaySlot < 9 ? throwawaySlot + 54 : throwawaySlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                    playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
                } else {
                    playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, playerContext.player());
                }

                return count;
            }
        }

        return 0;
    }

    /** Whether a stack of this item in slots 0-35 has room for more. */
    private boolean hasRoomOnStack(Item item) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContext.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.is(item) && stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    public int lootEnderChestSlot() {
        int count = 0;
        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getItem().getItem() instanceof BlockItem &&
                    ((BlockItem) curContainer.getSlot(i).getItem().getItem()).getBlock() instanceof EnderChestBlock) {
                count += curContainer.getSlot(i).getItem().getCount();

                if (ctx.inventory().getItemSlot(Item.getId(Items.AIR)) == -1) {
                    // No empty slot: toss a throwaway now and quick-move on the next call. Swapping the
                    // stack straight into the throwaway's slot skips the merge into partial stacks and
                    // leaves the chests fragmented, which costs a slot when the kept ones are stashed.
                    int throwawaySlot = ctx.inventory().getThrowawaySlotToToss();
                    if (throwawaySlot == -1) {
                        return 0;
                    }
                    playerContext.playerController().windowClick(curContainer.containerId, throwawaySlot < 9 ? throwawaySlot + 54 : throwawaySlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                    playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
                    return -1;
                }
                // Quick move merges into partial stacks first, then takes the empty slot
                playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, playerContext.player());

                return count;
            }
        }

        return count;
    }

    public void pauseNoRoomFor(String shulker) {
        playerContext.player().closeContainer();
        ctx.pause("No room in the inventory for a " + shulker + " shulker, and nothing to throw out.");
    }

    /** Shulkers taken, 0 when storage holds none, -1 when one is there but cannot be taken. */
    public int lootShulkerChestSlot(ShulkerType shulkerType) {
        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            ItemStack stack = curContainer.getSlot(i).getItem();
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            if (HighwayContext.shulkerItemList.contains(stack.getItem())) {
                boolean doLoot = false;
                switch (shulkerType) {
                    case EnderChest:
                        if (ctx.inventory().isEnderChestShulker(stack) > 0) {
                            doLoot = true;
                        }
                        break;

                    case Empty:
                        if (ctx.inventory().isEmptyShulker(stack)) {
                            doLoot = true;
                        }
                        break;

                    case NonSilkPickaxe:
                        if (ctx.inventory().isNonSilkPickShulker(stack) > 0) {
                            doLoot = true;
                        }
                        break;

                    case AnyPickaxe:
                        if (ctx.inventory().isPickaxeShulker(stack) > 0) {
                            doLoot = true;
                        }
                        break;

                    default:
                        if (shulkerType.item() != null && ctx.inventory().singleItemShulkerCount(stack, shulkerType.item()) > 0) {
                            doLoot = true;
                        }
                }
                if (doLoot) {
                    int depletedSlot = ctx.inventory().getShulkerSlot(ShulkerType.DepletedPickaxe);
                    if (depletedSlot != -1) {
                        playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                        playerContext.playerController().windowClick(curContainer.containerId, depletedSlot < 9 ? depletedSlot + 54 : depletedSlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                        playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player()); // Put depleted shulker in looted slot
                    } else if (ctx.inventory().getItemSlot(Item.getId(Items.AIR)) == -1) {
                        int throwawaySlot = ctx.inventory().roomSlotToToss();
                        if (throwawaySlot == -1) {
                            return -1;
                        }
                        playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
                        playerContext.playerController().windowClick(curContainer.containerId, throwawaySlot < 9 ? throwawaySlot + 54 : throwawaySlot + 18, 0, ClickType.PICKUP, playerContext.player()); // Have to convert slot id to single chest slot id
                        playerContext.playerController().windowClick(curContainer.containerId, -999, 0, ClickType.PICKUP, playerContext.player());
                    } else {
                        // There's an air slot so we can just do a quick move
                        playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, playerContext.player());
                    }
                    return 1;
                }
            }
        }

        return 0;
    }

    public int depositDepletedShulkerChestSlot() {
        int depletedSlot = ctx.inventory().getShulkerSlot(ShulkerType.DepletedPickaxe);
        if (depletedSlot == -1) {
            return 0;
        }

        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getItem().isEmpty()) {
                playerContext.playerController().windowClick(curContainer.containerId, depletedSlot < 9 ? depletedSlot + 54 : depletedSlot + 18, 0, ClickType.QUICK_MOVE, playerContext.player()); // Have to convert slot id to single chest slot id
                return 1;
            }
        }

        return 0;
    }

    /**
     * With an open ender chest shulker box, top up our loose ender chest stack toward {@code target}
     * (capped at 64) by merging a partial amount out of a single box slot, so it never spills into a
     * second inventory slot. Returns the number of ender chests moved this call (0 when nothing moved).
     */
    public int topUpEnderChestSlotFromShulker(int target) {
        target = Math.min(target, 64);
        int have = ctx.inventory().getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem()));
        int need = target - have;
        if (need <= 0) {
            return 0;
        }

        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        int looseSlot = ctx.inventory().getMergeableEnderChestSlot();
        for (int i = 0; i < 27; i++) {
            ItemStack stack = curContainer.getSlot(i).getItem();
            if (!(stack.getItem() instanceof BlockItem) || !(((BlockItem) stack.getItem()).getBlock() instanceof EnderChestBlock)) {
                continue;
            }
            int boxCount = stack.getCount();
            if (looseSlot == -1) {
                // No partial loose stack to merge into. Only proceed if we have a free slot to hold them;
                // capped target keeps it to a single slot. Bail otherwise (shouldn't happen above 0 chests).
                if (ctx.inventory().getItemSlot(Item.getId(Items.AIR)) == -1) {
                    return 0;
                }
                playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, playerContext.player());
                return boxCount;
            }
            int looseCount = playerContext.player().getInventory().getNonEquipmentItems().get(looseSlot).getCount();
            int containerLooseSlot = ctx.inventory().invSlotToMenuSlot(looseSlot);
            // Pick up the box stack, deposit into our loose slot (fills to at most 64), put any remainder back
            playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player());
            playerContext.playerController().windowClick(curContainer.containerId, containerLooseSlot, 0, ClickType.PICKUP, playerContext.player());
            playerContext.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, playerContext.player()); // empty cursor -> no-op, remainder -> back to box slot
            return Math.min(boxCount, 64 - looseCount);
        }

        return 0;
    }

    /**
     * With an open ender storage (ender chest) container, deposit one shulker box of the given type
     * from inventory into a free storage slot. Returns 1 on success, 0 if none to deposit or no space.
     */
    public int depositShulkerChestSlot(ShulkerType shulkerType) {
        int slot = ctx.inventory().getShulkerSlot(shulkerType);
        if (slot == -1) {
            return 0;
        }

        AbstractContainerMenu curContainer = playerContext.player().containerMenu;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getItem().isEmpty()) {
                playerContext.playerController().windowClick(curContainer.containerId, ctx.inventory().invSlotToMenuSlot(slot), 0, ClickType.QUICK_MOVE, playerContext.player());
                return 1;
            }
        }

        return 0;
    }
}
