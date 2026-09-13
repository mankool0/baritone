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

import baritone.api.utils.Helper;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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
        // Last tick placed a chest, so it is standing server-side whatever the client shows. A
        // foreign block in view (anything but a chest) drops back to the legacy path below.
        boolean pipelining = instantMine && validPick && context.instantMineCalibrated()
                && context.lastEchestPlaceTick() == context.playerContext().player().tickCount - 1
                && (!chestVisible || state.is(Blocks.ENDER_CHEST));

        if (pipelining) {
            context.instantMineTick(context.placeLoc());
            context.setEchestPlacedServerSide(false);
        } else if (chestVisible) {
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

            context.instantMineTick(context.placeLoc());
            context.setEchestPlacedServerSide(false);
            return;
        } else {
            context.setEchestPlacedServerSide(false);
        }

        // placeLoc is clear (or gets cleared by this tick's break): decide whether to keep farming.
        Item origItem = context.playerContext().player().getOffhandItem().getItem();
        boolean outOfChests = (context.getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) + context.playerContext().player().getOffhandItem().getCount()) <= context.farmEnderChestsToKeep();
        boolean needsEchest = !(origItem instanceof BlockItem) || !(((BlockItem) origItem).getBlock().equals(Blocks.ENDER_CHEST));
        // The keep is a forecast; the measured room is the truth. Stop when one more chest's obsidian
        // would not fit, whatever the chest count says.
        if (!outOfChests && !needsEchest) {
            int roomLeft = context.farmRoomAfterOneMore();
            if (roomLeft < 0) {
                Helper.HELPER.logDirect("Stopping the farm: the next chest's obsidian wouldn't fit (short by " + (-roomLeft) + ") [" + context.obsidianRoomBreakdown() + "]");
                outOfChests = true;
            }
        }

        if (outOfChests || needsEchest) {
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
                // Only the client still sees a chest here: server side this tick's break (already
                // sent) clears it, so mirror that to get place() past canBeReplaced.
                context.playerContext().world().removeBlock(context.placeLoc(), false);
            }
        } else {
            // Nothing placed yet: face the support block and drop a fresh ender chest from the offhand.
            // A pipelining tick keeps its aim on the mine target instead (set by instantMineTick above).
            Optional<Rotation> support = RotationUtils.reachable(context.playerContext(), context.placeLoc().below(), context.playerContext().playerController().getBlockReachDistance());
            support.ifPresent(rotation -> context.baritone().getLookBehavior().updateTarget(context.farmAim(rotation), true));
        }

        // A place that falls through needs no recovery: a pipelining tick has already dug, so
        // nothing is left standing, and the stale place tick drops the next tick back to the
        // legacy path.
        if (context.place(context.placeLoc(), 5.0f, false, false, InteractionHand.OFF_HAND) == HighwayContext.PlaceResult.Placed) {
            context.setEchestPlacedServerSide(true);
            context.setLastEchestPlaceTick(context.playerContext().player().tickCount);
            context.resetTimer();
        }
    }
}
