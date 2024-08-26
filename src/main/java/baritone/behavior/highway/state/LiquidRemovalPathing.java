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

import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayBlockState;
import baritone.behavior.highway.enums.HighwayState;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Optional;

import static baritone.pathing.movement.Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP;

public class LiquidRemovalPathing extends State {
    public LiquidRemovalPathing(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.timer() < 10) {
            return;
        }

        if (!context.sourceBlocks().isEmpty() && context.getIssueType(context.sourceBlocks().getFirst()) == HighwayBlockState.Blocks) {
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.LiquidRemovalPrep);
            context.resetTimer();
            return;
        }

        MobEffectInstance fireRest = context.playerContext().player().getEffect(MobEffects.FIRE_RESISTANCE);
        if (fireRest != null && fireRest.getDuration() < context.settings().highwayFireRestMinDuration.value) {
            Helper.HELPER.logDirect("Running out of fire resistance. Restarting liquid clearing.");
            context.transitionTo(HighwayState.Nothing);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.baritone().getPathingBehavior().cancelEverything();
            return;
        }


        if (context.sourceBlocks().isEmpty()) {
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        boolean supportNeeded = false;
        if (context.getIssueType(context.sourceBlocks().getFirst().north()) != HighwayBlockState.Blocks &&
                context.getIssueType(context.sourceBlocks().getFirst().east()) != HighwayBlockState.Blocks &&
                context.getIssueType(context.sourceBlocks().getFirst().south()) != HighwayBlockState.Blocks &&
                context.getIssueType(context.sourceBlocks().getFirst().west()) != HighwayBlockState.Blocks &&
                context.getIssueType(context.sourceBlocks().getFirst().below()) != HighwayBlockState.Blocks) {
            // Location to place against are not blocks so lets find the closest surrounding block
            BlockPos tempSourcePos = context.closestAirBlockWithSideBlock(context.sourceBlocks().getFirst(), 5, true);
            if (tempSourcePos == null) {
                Helper.HELPER.logDirect("Error finding support block during lava removal. Restarting.");
                context.transitionTo(HighwayState.Nothing);
                return;
            }
            context.clearSourceBlocks();
            context.sourceBlocks().add(tempSourcePos);
            supportNeeded = true;
            Helper.HELPER.logDirect("Can't place around lava, placing support block at " + tempSourcePos);
            //if (sourceBlocks.isEmpty()) {
            //    currentState = State.Nothing;
            //    return;
            //}
        }
        Optional<Rotation> lavaReachable = Optional.empty();

        // From MovementHelper.attemptToPlaceABlock
        for (Direction side : Direction.values()) {//(int i = 0; i < 5; i++) {
            BlockPos against1 = context.sourceBlocks().getFirst().offset(side.getNormal()); //sourceBlocks.get(0).offset(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
            if (MovementHelper.canPlaceAgainst(context.playerContext(), against1)) {
                double faceX = (context.sourceBlocks().getFirst().getX() + against1.getX() + 1.0D) * 0.5D;
                double faceY = (context.sourceBlocks().getFirst().getY() + against1.getY() + 0.5D) * 0.5D;
                double faceZ = (context.sourceBlocks().getFirst().getZ() + against1.getZ() + 1.0D) * 0.5D;
                Rotation place = RotationUtils.calcRotationFromVec3d(context.playerContext().playerHead(), new Vec3(faceX, faceY, faceZ), context.playerContext().playerRotations());
                HitResult res = RayTraceUtils.rayTraceTowards(context.playerContext().player(), place, context.playerContext().playerController().getBlockReachDistance(), false);
                if (res.getType() == HitResult.Type.BLOCK && ((BlockHitResult) res).getBlockPos().equals(against1) && ((BlockHitResult) res).getBlockPos().relative(((BlockHitResult) res).getDirection()).equals(context.sourceBlocks().getFirst())) {
                    lavaReachable = Optional.ofNullable(place);
                    if (supportNeeded) {
                        context.setLiquidPathingCanMine(false);
                    }
                    break;
                }
            }
        }

        if (lavaReachable.isPresent()) {
            context.baritone().getLookBehavior().updateTarget(lavaReachable.get(), true);
            context.baritone().getInputOverrideHandler().clearAllKeys();

            int netherRackSlot = context.putItemHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
            if (netherRackSlot == -1) {
                Helper.HELPER.logDirect("Error getting netherrack slot");
                context.transitionTo(HighwayState.Nothing);
                return;
            }


            ItemStack stack = context.playerContext().player().getInventory().items.get(netherRackSlot);
            if (Item.getId(stack.getItem()) == Item.getId(Blocks.NETHERRACK.asItem())) {
                context.playerContext().player().getInventory().selected = netherRackSlot;
            }
            context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            //currentState = State.LiquidRemovalPrep;
        } else {
            int pickSlot = context.putPickaxeHotbar();
            if (pickSlot == -1) {
                Helper.HELPER.logDirect("Error getting pick slot");
                context.transitionTo(HighwayState.Nothing);
                return;
            }
            ItemStack stack = context.playerContext().player().getInventory().items.get(pickSlot);
            if (HighwayContext.validPicksList.contains(stack.getItem())) {
                context.playerContext().player().getInventory().selected = pickSlot;
            }

            Rotation lavaRot = RotationUtils.calcRotationFromVec3d(context.playerContext().playerHead(), new Vec3(context.sourceBlocks().getFirst().getX(), context.sourceBlocks().getFirst().getY(), context.sourceBlocks().getFirst().getZ()), context.playerContext().playerRotations());
            //RayTraceResult res = RayTraceUtils.rayTraceTowards(ctx.player(), lavaRot, ctx.playerController().getBlockReachDistance(), false);

            ArrayList<BlockPos> possibleIssuePosList = new ArrayList<>();
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos tempLoc = new BlockPos(context.playerContext().playerFeet().x + x, context.playerContext().playerFeet().y, context.playerContext().playerFeet().z + z);
                    for (int i = 0; i < context.settings().highwayHeight.value; i++) {
                        possibleIssuePosList.add(tempLoc.above(i));
                    }
                }
            }
            if (!context.liquidPathingCanMine()) {
                possibleIssuePosList.clear();
            }

            ArrayList<BlockPos> placeAtList = new ArrayList<>();
            //BlockPos placeAt = null;
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos tempLoc = new BlockPos(context.playerContext().playerFeet().x + x, context.playerContext().playerFeet().y - 1, context.playerContext().playerFeet().z + z);
                    if (context.getIssueType(tempLoc) != HighwayBlockState.Blocks) {
                        //placeAt = tempLoc;
                        placeAtList.add(tempLoc);
                    }
                }
            }




            //ArrayList<Rotation> belowFeetReachableList = new ArrayList<>();
            //Optional<Rotation> belowFeetReachable = Optional.empty();
            for (BlockPos placeAt : placeAtList) {
                //if (placeAt != null) {
                // From MovementHelper.attemptToPlaceABlock
                for (int i = 0; i < 5; i++) {
                    BlockPos against1 = placeAt.relative(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
                    if (MovementHelper.canPlaceAgainst(context.playerContext(), against1)) {
                        double faceX = (placeAt.getX() + against1.getX() + 1.0D) * 0.5D;
                        double faceY = (placeAt.getY() + against1.getY() + 0.5D) * 0.5D;
                        double faceZ = (placeAt.getZ() + against1.getZ() + 1.0D) * 0.5D;
                        Rotation place = RotationUtils.calcRotationFromVec3d(context.playerContext().playerHead(), new Vec3(faceX, faceY, faceZ), context.playerContext().playerRotations());
                        HitResult res = RayTraceUtils.rayTraceTowards(context.playerContext().player(), place, context.playerContext().playerController().getBlockReachDistance(), false);
                        if (res.getType() == HitResult.Type.BLOCK && ((BlockHitResult) res).getBlockPos().equals(against1) && ((BlockHitResult) res).getBlockPos().relative(((BlockHitResult) res).getDirection()).equals(placeAt)) {
                            context.baritone().getLookBehavior().updateTarget(place, true);
                            context.baritone().getInputOverrideHandler().clearAllKeys();
                            int netherRackSlot = context.putItemHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                            if (netherRackSlot == -1) {
                                Helper.HELPER.logDirect("Error getting netherrack slot");
                                context.transitionTo(HighwayState.Nothing);
                                return;
                            }
                            if (Item.getId(context.playerContext().player().getInventory().items.get(netherRackSlot).getItem()) == Item.getId(Blocks.NETHERRACK.asItem())) {
                                context.playerContext().player().getInventory().selected = netherRackSlot;
                            }

                            double lastX = context.playerContext().getPlayerEntity().getXLast();
                            double lastY = context.playerContext().getPlayerEntity().getYLast();
                            double lastZ = context.playerContext().getPlayerEntity().getZLast();
                            final Vec3 pos = new Vec3(lastX + (context.playerContext().player().getX() - lastX) * context.playerContext().minecraft().getTimer().getGameTimeDeltaPartialTick(true),
                                    lastY + (context.playerContext().player().getY() - lastY) * context.playerContext().minecraft().getTimer().getGameTimeDeltaPartialTick(true),
                                    lastZ + (context.playerContext().player().getZ() - lastZ) * context.playerContext().minecraft().getTimer().getGameTimeDeltaPartialTick(true));
                            BetterBlockPos originPos = new BetterBlockPos(pos.x, pos.y+0.5f, pos.z);
                            double l_Offset = pos.y - originPos.getY();
                            if (context.place(placeAt, (float) context.playerContext().playerController().getBlockReachDistance(), true, l_Offset == -0.5f, InteractionHand.MAIN_HAND) == HighwayContext.PlaceResult.Placed) {
                                context.resetTimer();
                                context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                                return;
                            }

                            //belowFeetReachable = Optional.ofNullable(place);
                            //belowFeetReachableList.add(place);
                            //break;
                        }
                    }
                }
            }

                    /*
                    if (!belowFeetReachableList.isEmpty()) {
                        baritone.getLookBehavior().updateTarget(belowFeetReachableList.get(0), true);
                        baritone.getInputOverrideHandler().clearAllKeys();

                        int netherRackSlot = putItemHotbar(Item.getId(Blocks.NETHERRACK.asItem()));
                        if (netherRackSlot == -1) {
                            Helper.HELPER.logDirect("Error getting netherrack slot");
                            currentState = State.Nothing;
                            return;
                        }
                        if (Item.getId(ctx.player().getInventory().items.get(netherRackSlot).getItem()) == Item.getId(Blocks.NETHERRACK.asItem())) {
                            ctx.player().getInventory().selected = netherRackSlot;
                        }

                        //TODO: Change this placing to use place method
                        //Helper.HELPER.logDirect("Placing " + placeAt);
                        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                        return;
                    }*/

            ArrayList<Rotation> possibleIssueReachableList = new ArrayList<>();
            for (BlockPos curIssuePos : possibleIssuePosList) {
                Optional<Rotation> curIssuePosReachable = RotationUtils.reachable(context.playerContext(), curIssuePos, context.playerContext().playerController().getBlockReachDistance());
                BlockState state = context.playerContext().world().getBlockState(curIssuePos);
                Block block = state.getBlock();
                if (block != Blocks.BEDROCK && !(block instanceof LiquidBlock) && !(block instanceof AirBlock) && curIssuePosReachable.isPresent() && curIssuePos.getY() >= context.settings().highwayMainY.value) {
                    possibleIssueReachableList.add(curIssuePosReachable.get());
                }
            }
            if (!possibleIssueReachableList.isEmpty()) {
                context.baritone().getLookBehavior().updateTarget(possibleIssueReachableList.get(0), true);
                context.baritone().getInputOverrideHandler().clearAllKeys();
                context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                return;
            }

            context.baritone().getLookBehavior().updateTarget(lavaRot, true);
            context.baritone().getInputOverrideHandler().clearAllKeys();

            outerLoop:
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos tempLoc = new BlockPos(context.playerContext().playerFeet().x + x, context.playerContext().playerFeet().y - 1, context.playerContext().playerFeet().z + z);
                    if (context.getIssueType(tempLoc) != HighwayBlockState.Blocks) {
                        context.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true); // No blocks under feet to walk to
                        break outerLoop;
                    }
                }
            }

            context.baritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        }

        //timer = 0;
    }
}
