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

package baritone.behavior.highway.state;

import baritone.api.utils.Rotation;
import baritone.api.utils.VecUtils;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class InventoryCleaningObsidian extends State {
    public InventoryCleaningObsidian(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.timer() < 10) {
            return;
        }

        // Only free as many slots as the nearby drops actually need, so we keep throwaway
        // items we might still want (support blocks etc.) instead of dumping them all
        int stacksToThrow = slotsNeededForDrops(context) - context.getItemCountInventory(Item.getId(Items.AIR));
        context.baritone().getLookBehavior().updateTarget(new Rotation(45, 0), true);
        for (int i = 0; i < stacksToThrow; i++) {
            int throwawaySlot = context.getAcceptableThrowawaySlot();
            if (throwawaySlot == 8) {
                throwawaySlot = context.getAcceptableThrowawaySlotNoHotbar();
            }
            if (throwawaySlot == -1) {
                break;
            }
            context.playerContext().playerController().windowClick(context.playerContext().player().inventoryMenu.containerId, throwawaySlot < 9 ? throwawaySlot + 36 : throwawaySlot, 0, ClickType.PICKUP, context.playerContext().player());
            context.playerContext().playerController().windowClick(context.playerContext().player().inventoryMenu.containerId, -999, 0, ClickType.PICKUP, context.playerContext().player());
        }
        context.transitionTo(HighwayState.CollectingObsidian);
        context.resetTimer();
    }

    /**
     * Slots the obsidian drops in collection range will occupy beyond the room left in the
     * partial stacks we already carry. Obsidian and crying obsidian don't stack together,
     * so each type is tallied separately.
     */
    private static int slotsNeededForDrops(HighwayContext context) {
        int obsidianOnGround = 0;
        int cryingOnGround = 0;
        for (Entity entity : context.playerContext().entities()) {
            if (entity instanceof ItemEntity) {
                ItemStack stack = ((ItemEntity) entity).getItem();
                if (!(stack.getItem() instanceof BlockItem)) {
                    continue;
                }
                Block block = ((BlockItem) stack.getItem()).getBlock();
                if (block != Blocks.OBSIDIAN && block != Blocks.CRYING_OBSIDIAN) {
                    continue;
                }
                double obsidDistance = VecUtils.distanceToCenter(context.playerContext().playerFeet(), (int) entity.getX(), (int) entity.getY(), (int) entity.getZ());
                if (obsidDistance > context.settings().highwayObsidianMaxSearchDist.value) {
                    continue;
                }
                if (block == Blocks.OBSIDIAN) {
                    obsidianOnGround += stack.getCount();
                } else {
                    cryingOnGround += stack.getCount();
                }
            }
        }

        int obsidianRoom = 0;
        int cryingRoom = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = context.playerContext().player().getInventory().items.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            Block block = ((BlockItem) stack.getItem()).getBlock();
            if (block == Blocks.OBSIDIAN) {
                obsidianRoom += stack.getMaxStackSize() - stack.getCount();
            } else if (block == Blocks.CRYING_OBSIDIAN) {
                cryingRoom += stack.getMaxStackSize() - stack.getCount();
            }
        }

        return (Math.max(0, obsidianOnGround - obsidianRoom) + 63) / 64
                + (Math.max(0, cryingOnGround - cryingRoom) + 63) / 64;
    }
}
