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
import baritone.api.utils.RotationUtils;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public class FarmingEnderChest extends State {
    public FarmingEnderChest(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        // Stuck too long on this spot: clear it and force a fresh calibration break.
        if (context.timer() > 120) {
            context.baritone().getBuilderProcess().clearArea(context.placeLoc(), context.placeLoc());
            context.setInstantMineCalibrated(false);
            context.resetTimer();
        }

        // Keep a non-silk-touch pickaxe selected so the eChest drops obsidian when broken.
        int pickSlot = context.putPickaxeHotbar(true);
        if (context.playerContext().player().getInventory().selected != pickSlot) {
            context.transitionTo(HighwayState.FarmingEnderChestPrepEchest);
            context.resetTimer();
            return;
        }

        BlockState state = context.playerContext().world().getBlockState(context.placeLoc());
        boolean chestVisible = !(state.getBlock() instanceof AirBlock);
        boolean validPick = HighwayContext.validPicksList.contains(context.playerContext().player().getItemInHand(InteractionHand.MAIN_HAND).getItem());
        boolean instantMine = context.settings().highwayEnderChestInstantMine.value;
        boolean pipelining = instantMine && validPick && context.instantMineCalibrated()
                && context.lastEchestPlaceTick() == context.playerContext().player().tickCount - 1;

        if (chestVisible) {
            if (!validPick) {
                return;
            }

            // First chest of the session so break it the legitimate way (real client mining) so
            // the server's destroy target is set. With instant mining off every chest goes this
            // way and the destroy target is never reused.
            if (!instantMine || !context.instantMineCalibrated()) {
                if (context.calibrationBreakTick(context.placeLoc())) {
                    if (instantMine) {
                        context.setInstantMineCalibrated(true);
                    }
                    context.setEchestPlacedServerSide(false);
                }
                return;
            }

            if (!pipelining || !state.is(Blocks.ENDER_CHEST)) {
                context.instantMineTick(context.placeLoc());
                context.setEchestPlacedServerSide(false);
                return;
            }
        } else {
            context.setEchestPlacedServerSide(false);
        }

        // placeLoc is clear (or gets cleared by this tick's break): decide whether to keep farming.
        Item origItem = context.playerContext().player().getOffhandItem().getItem();
        boolean outOfChests = (context.getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) + context.playerContext().player().getOffhandItem().getCount()) <= context.settings().highwayEnderChestsToKeep.value;
        boolean needsEchest = !(origItem instanceof BlockItem) || !(((BlockItem) origItem).getBlock().equals(Blocks.ENDER_CHEST));

        if (outOfChests || needsEchest) {
            if (chestVisible) {
                context.instantMineTick(context.placeLoc());
                context.setEchestPlacedServerSide(false);
            }

            if (outOfChests) {
                // Out of ender chests to farm, swap the offhand back and move on.
                context.baritone().getInputOverrideHandler().clearAllKeys();
                context.setInstantMineCalibrated(false);
                context.transitionTo(HighwayState.FarmingEnderChestSwapBack);
            } else {
                context.transitionTo(HighwayState.FarmingEnderChestPrepEchest);
            }
            context.resetTimer();
            return;
        }

        if (pipelining) {
            if (chestVisible) {
                // Only the client still sees a chest here: server side this tick's break clears it
                // (it is sent after this tick's place), so mirror that to get place() past
                // canBeReplaced.
                context.playerContext().world().removeBlock(context.placeLoc(), false);
            }
        } else {
            // Nothing placed yet: face the support block and drop a fresh ender chest from the offhand.
            // A pipelining tick keeps its aim on the mine target instead (set by instantMineTick below).
            Optional<Rotation> support = RotationUtils.reachable(context.playerContext(), context.placeLoc().below(), context.playerContext().playerController().getBlockReachDistance());
            support.ifPresent(rotation -> context.baritone().getLookBehavior().updateTarget(rotation, true));
        }

        boolean refused = context.echestPlacedServerSide();
        if (context.place(context.placeLoc(), 5.0f, false, false, InteractionHand.OFF_HAND) == HighwayContext.PlaceResult.Placed) {
            if (refused) {
                restoreOffhandEnderChest(context);
            }
            context.setEchestPlacedServerSide(true);
            context.setLastEchestPlaceTick(context.playerContext().player().tickCount);
            if (pipelining) {
                context.instantMineTick(context.placeLoc());
                context.setEchestPlacedServerSide(false);
            }
            context.resetTimer();
        } else if (pipelining && chestVisible) {
            // Placement fell through after the position was cleared locally: break whatever is still
            // standing there
            context.instantMineTick(context.placeLoc());
            context.setEchestPlacedServerSide(false);
        }
    }

    private void restoreOffhandEnderChest(HighwayContext context) {
        ItemStack offhand = context.playerContext().player().getOffhandItem();
        if (offhand.isEmpty()) {
            context.playerContext().player().setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Blocks.ENDER_CHEST.asItem()));
        } else if (offhand.getItem().equals(Blocks.ENDER_CHEST.asItem())) {
            offhand.grow(1);
        }
    }
}
