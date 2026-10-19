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

import baritone.api.utils.BlockUtils;
import baritone.api.utils.Helper;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public class FarmingEnderChest extends State {

    /** Ticks to wait for the server to ack the places in flight before leaving anyway. */
    private static final int IN_FLIGHT_WAIT_TICKS = 100;
    /** Ticks to let the builder pick up a dispatched support build before checking on it. */
    private static final int BUILDER_SPINUP_TICKS = 5;
    /** Supports to put back in one farm session before giving up on the spot. */
    private static final int MAX_SUPPORT_REBUILDS = 3;

    // Decided to stop; held while the places in flight land so the stop isn't re-decided and re-logged.
    private boolean stopping;
    private int inFlightWaitTicks;
    // Ticks since the support build went out, -1 if none is under way.
    private int sinceSupportDispatch = -1;

    public FarmingEnderChest(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        // Putting back the block the chests go on. Wait it out first: the stuck-spot clear below
        // would take the builder over.
        if (sinceSupportDispatch >= 0) {
            if (!context.baritone().getBuilderProcess().isPaused() && context.baritone().getBuilderProcess().isActive()) {
                sinceSupportDispatch = BUILDER_SPINUP_TICKS;
                context.resetTimer();
                return;
            }
            if (sinceSupportDispatch < BUILDER_SPINUP_TICKS) {
                sinceSupportDispatch++;
                return;
            }
            // Done, or paused because it couldn't: either way the next fresh place checks again.
            // The pick check below swaps back from the block the builder held.
            sinceSupportDispatch = -1;
            context.baritone().getPathingBehavior().cancelEverything();
            context.resetTimer();
        }

        // Stuck too long on this spot: clear it and force a fresh calibration break.
        if (context.timer() > 120) {
            context.baritone().getBuilderProcess().clearArea(context.placeLoc(), context.placeLoc());
            context.setInstantMineCalibrated(false);
            context.resetTimer();
        }

        // Keep a non-silk-touch pickaxe selected so the eChest drops obsidian when broken.
        int pickSlot = context.putPickaxeHotbar(true);
        if (context.playerContext().player().getInventory().getSelectedSlot() != pickSlot) {
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

            // Not one we placed since the last dig, so it may be the chest that dig already broke,
            // still on screen until the server's update lands. Grim flags digging air (START and
            // FINISHED alike), so only dig what the server confirms is there.
            if (!context.echestPlacedServerSide() && !context.farmPlaceLocConfirmed()) {
                return;
            }

            // With instant mining off every chest is broken the legitimate way (real client mining).
            if (!instantMine) {
                if (context.calibrationBreakTick(context.placeLoc())) {
                    context.setEchestPlacedServerSide(false);
                }
                return;
            }

            // First chest of the session: make placeLoc the server's dig target, so a lone stop
            // breaks every chest after it.
            if (!context.instantMineCalibrated()) {
                if (context.calibrationTick(context.placeLoc())) {
                    context.setInstantMineCalibrated(true);
                    context.setEchestPlacedServerSide(false);
                }
                return;
            }

            context.instantMineTick(context.placeLoc());
            context.setEchestPlacedServerSide(false);
            return;
        } else {
            context.setEchestPlacedServerSide(false);
            context.farmChestCleared();
        }

        // placeLoc is clear (or gets cleared by this tick's break): decide whether to keep farming.
        // Both counts take in the places still in flight: the server's offhand count reaches us a
        // round trip after the places it covers.
        Item origItem = context.playerContext().player().getOffhandItem().getItem();
        boolean outOfChests = stopping || context.farmEnderChestsLeft() <= context.farmEnderChestsToKeep();
        // The server's offhand runs dry before ours shows it. A place it can't back is refused, and
        // the next tick's dig would hit air.
        boolean needsEchest = !(origItem instanceof BlockItem) || !(((BlockItem) origItem).getBlock().equals(Blocks.ENDER_CHEST))
                || context.farmOffhandChestsLeft() <= 0;
        // The keep is a forecast; the measured room is the truth. Stop when one more chest's obsidian
        // would not fit, whatever the chest count says.
        if (!outOfChests && !needsEchest) {
            int roomLeft = context.farmRoomAfterOneMore();
            if (roomLeft < 0) {
                Helper.HELPER.logDirect("Stopping the farm: the next chest's obsidian wouldn't fit (short by " + (-roomLeft) + ") [" + context.obsidianRoomBreakdown() + "]");
                outOfChests = true;
            }
        }
        stopping = outOfChests;

        if (outOfChests || needsEchest) {
            // Both exits click the offhand. Late offhand counts for the places in flight would land on
            // top of those clicks, so let the places land first. Stopping also waits out the chest
            // this tick dug, which is still on screen: the clear that follows would mine air.
            boolean waiting = context.farmPlacesInFlight() > 0 || (outOfChests && chestVisible);
            if (waiting && inFlightWaitTicks++ < IN_FLIGHT_WAIT_TICKS) {
                return;
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
                // Only the client still sees a chest here: server side this tick's break (already
                // sent) clears it, so mirror that to get place() past canBeReplaced.
                context.playerContext().world().removeBlock(context.placeLoc(), false);
            }
        } else {
            // Something dug out the block the chests go on. Out on the road there's usually nothing
            // else next to placeLoc to click, so every place would fall through. Only checked with
            // placeLoc clear on screen: the builder could take a stale chest there for a face to
            // place the support against.
            if (!context.farmSupportHolds()) {
                rebuildSupport(context);
                return;
            }
            // Nothing placed yet: face the support block and drop a fresh ender chest from the offhand.
            // A pipelining tick keeps its aim on the mine target instead (set by instantMineTick above).
            Optional<Rotation> support = RotationUtils.reachable(context.playerContext(), context.placeLoc().below(), context.playerContext().playerController().getBlockReachDistance());
            support.ifPresent(rotation -> context.baritone().getLookBehavior().updateTarget(context.farmAim(rotation), true));
        }

        // A place that falls through needs no recovery: a pipelining tick has already dug, so
        // nothing is left standing, and the stale place tick drops the next tick back to the
        // legacy path, which has the server confirm any chest still on screen before digging.
        if (context.placeFarmEnderChest() == HighwayContext.PlaceResult.Placed) {
            context.setEchestPlacedServerSide(true);
            context.setLastEchestPlaceTick(context.playerContext().player().tickCount);
            context.resetTimer();
        }
    }

    private void rebuildSupport(HighwayContext context) {
        BlockPos support = context.placeLoc().below();
        String found = BlockUtils.blockToString(context.playerContext().world().getBlockState(support).getBlock());
        Block block = supportBlock(context);
        if (block == null || context.farmSupportRebuilds() >= MAX_SUPPORT_REBUILDS) {
            // The next farm's spot picker passes over a known-bad spot and sets up somewhere else,
            // support included. The leftover chests stay in the inventory for it.
            context.rememberBadShulkerSpot(context.placeLoc());
            Helper.HELPER.logDirect("Ending the farm: the block under the ender chests at " + support.toShortString() + " is " + found
                    + (block == null ? " and there's no obsidian or netherrack to put back" : " again after putting one back " + context.farmSupportRebuilds() + " times")
                    + ", the next farm picks another spot");
            stopping = true;
            return;
        }
        Helper.HELPER.logDirect("The block under the ender chests at " + support.toShortString() + " is " + found + ", putting " + BlockUtils.blockToString(block) + " back");
        context.noteFarmSupportRebuilt();
        // the builder digs whatever is in the way, which moves the server's dig target
        context.setInstantMineCalibrated(false);
        context.buildSupportBlock(support, block);
        sinceSupportDispatch = 0;
        context.resetTimer();
    }

    /**
     * The support is usually a cell of the road surface, so obsidian, and netherrack only when
     * there's none. Null when we carry neither.
     */
    private static Block supportBlock(HighwayContext context) {
        for (Block block : new Block[]{Blocks.OBSIDIAN, Blocks.NETHERRACK}) {
            if (context.getItemCountInventory(Item.getId(block.asItem())) > 0) {
                return block;
            }
        }
        return null;
    }
}
