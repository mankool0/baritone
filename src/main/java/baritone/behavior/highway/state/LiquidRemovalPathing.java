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
        BlockPos firstSourceBlock = context.sourceBlocks().getFirst();
        if (context.getIssueType(firstSourceBlock.north()) != HighwayBlockState.Blocks &&
                context.getIssueType(firstSourceBlock.east()) != HighwayBlockState.Blocks &&
                context.getIssueType(firstSourceBlock.south()) != HighwayBlockState.Blocks &&
                context.getIssueType(firstSourceBlock.west()) != HighwayBlockState.Blocks &&
                context.getIssueType(firstSourceBlock.below()) != HighwayBlockState.Blocks) {
            BlockPos playerPos = context.playerContext().playerFeet();

            // Calculate direction from player to lava for non-blocking checks
            int dx = firstSourceBlock.getX() - playerPos.getX();
            int dz = firstSourceBlock.getZ() - playerPos.getZ();
            BlockPos supportPos = findSupportBlockRecursive(context, firstSourceBlock, playerPos, dx, dz, 0, 5);
            
            if (supportPos != null) {
                // Clear source blocks and add the support position to place
                context.clearSourceBlocks();
                context.sourceBlocks().add(supportPos);
                supportNeeded = true;
                Helper.HELPER.logDirect("Can't place around lava, placing support block at " + supportPos);
            } else {
                Helper.HELPER.logDirect("Cannot find valid support block position for lava removal");
                context.transitionTo(HighwayState.Nothing);
                return;
            }
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
                // From MovementHelper.attemptToPlaceABlock
                for (int i = 0; i < 5; i++) {
                    BlockPos against1 = placeAt.relative(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
                    if (MovementHelper.canPlaceAgainst(context.playerContext(), against1)) {
                        double faceX = (placeAt.getX() + against1.getX() + 1.0D) * 0.5D;
                        double faceY = (placeAt.getY() + against1.getY() + 0.5D) * 0.5D;
                        double faceZ = (placeAt.getZ() + against1.getZ() + 1.0D) * 0.5D;
                        Rotation place = RotationUtils.calcRotationFromVec3d(context.playerContext().playerHead(), new Vec3(faceX, faceY, faceZ), context.playerContext().playerRotations());
                        HitResult res = RayTraceUtils.rayTraceTowards(context.playerContext().player(), place, context.playerContext().playerController().getBlockReachDistance(), false);
                        
                        if (res.getType() == HitResult.Type.BLOCK &&
                            ((BlockHitResult) res).getBlockPos().equals(against1) && 
                            ((BlockHitResult) res).getBlockPos().relative(((BlockHitResult) res).getDirection()).equals(placeAt)) {
                            
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

            // Check if player is under any lava source block and can't reach
            BlockPos playerPos = context.playerContext().playerFeet();
            double reachDistance = context.playerContext().playerController().getBlockReachDistance();
            for (BlockPos lavaPos : context.sourceBlocks()) {
                int xDiff = Math.abs(playerPos.getX() - lavaPos.getX());
                int zDiff = Math.abs(playerPos.getZ() - lavaPos.getZ());
                int yDiff = lavaPos.getY() - playerPos.getY();
                
                if (xDiff <= 1 && zDiff <= 1 && yDiff > 0 && yDiff > reachDistance) {
                    Helper.HELPER.logDirect("Player under lava and can't reach, backing up");
                    context.baritone().getInputOverrideHandler().clearAllKeys();
                    context.transitionTo(HighwayState.LiquidRemovalPathingBack);
                    context.resetTimer();
                    return;
                }
            }

            context.baritone().getInputOverrideHandler().clearAllKeys();

            // Check for blocks that might be obstructing our path
            BlockPos feetPos = context.playerContext().playerFeet();
            Direction playerDirection = context.playerContext().player().getDirection();
            BlockPos frontPos = feetPos.relative(playerDirection);
            BlockPos frontHeadPos = frontPos.above(); // Block at head level
            BlockPos bridgePos = frontPos.below();
            
            // Also check left and right sides in case player is between blocks
            Direction leftDir = playerDirection.getCounterClockWise();
            Direction rightDir = playerDirection.getClockWise();
            BlockPos leftPos = feetPos.relative(leftDir);
            BlockPos leftHeadPos = leftPos.above();
            BlockPos rightPos = feetPos.relative(rightDir);
            BlockPos rightHeadPos = rightPos.above();
            
            // Check all potential obstructing positions
            BlockPos[] checkPositions = {frontPos, frontHeadPos, leftPos, leftHeadPos, rightPos, rightHeadPos};
            BlockPos blockToBreak = null;
            
            // Find the first obstructing block we can break
            for (BlockPos checkPos : checkPositions) {
                if (context.getIssueType(checkPos) == HighwayBlockState.Blocks) {
                    Optional<Rotation> breakRotation = RotationUtils.reachable(context.playerContext(), checkPos, context.playerContext().playerController().getBlockReachDistance());
                    if (breakRotation.isPresent()) {
                        blockToBreak = checkPos;
                        break;
                    }
                }
            }
            
            // If we found an obstructing block, break it
            if (blockToBreak != null) {
                Optional<Rotation> breakRotation = RotationUtils.reachable(context.playerContext(), blockToBreak, context.playerContext().playerController().getBlockReachDistance());
                if (breakRotation.isPresent()) {
                    // Make sure we have a pickaxe selected
                    int breakPickSlot = context.putPickaxeHotbar();
                    if (breakPickSlot != -1) {
                        ItemStack breakStack = context.playerContext().player().getInventory().items.get(breakPickSlot);
                        if (HighwayContext.validPicksList.contains(breakStack.getItem())) {
                            context.playerContext().player().getInventory().selected = breakPickSlot;
                        }
                        
                        // Look at the block and break it
                        context.baritone().getLookBehavior().updateTarget(breakRotation.get(), true);
                        context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                        Helper.HELPER.logDirect("Breaking obstructing block at " + blockToBreak);
                        return;
                    }
                }
            }
            
            // Check if we need to bridge (no block directly in front below feet)
            if (context.getIssueType(bridgePos) != HighwayBlockState.Blocks) {
                double edgeDistance = Math.max(
                    Math.abs(context.playerContext().player().getX() - (feetPos.getX() + 0.5)),
                    Math.abs(context.playerContext().player().getZ() - (feetPos.getZ() + 0.5))
                );
                
                if (edgeDistance < 0.1) {
                    // Look at the edge face between current position and bridge position
                    double edgeFaceX = (feetPos.getX() + bridgePos.getX() + 1.0D) * 0.5D;
                    double edgeFaceY = bridgePos.getY() + 0.5D;
                    double edgeFaceZ = (feetPos.getZ() + bridgePos.getZ() + 1.0D) * 0.5D;

                    Rotation edgeRotation = RotationUtils.calcRotationFromVec3d(
                            context.playerContext().playerHead(),
                            new Vec3(edgeFaceX, edgeFaceY, edgeFaceZ),
                            context.playerContext().playerRotations()
                    );
                    context.baritone().getLookBehavior().updateTarget(edgeRotation, true);
                }

                context.baritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                context.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            } else {
                // Safe to walk forward
                context.baritone().getLookBehavior().updateTarget(lavaRot, true);
                context.baritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            }
        }

        //timer = 0;
    }

    private BlockPos findSupportBlockRecursive(HighwayContext context, BlockPos targetPos, 
                                                BlockPos playerPos, int dx, int dz, 
                                                int depth, int maxDepth) {
        if (depth >= maxDepth) {
            return null; // Max recursion depth reached
        }
        
        BlockPos bestPos = null;
        double bestScore = Double.MAX_VALUE;
        
        // First check all directions around the target position
        // Prioritize horizontal directions over vertical to avoid building columns
        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN};
        for (Direction dir : directions) {
            BlockPos checkPos = targetPos.relative(dir);
            
            if (context.getIssueType(checkPos) == HighwayBlockState.Blocks) {
                continue;
            }
            
            // If this is below the target and at depth 0 (original lava position),
            // only accept it if it's directly adjacent to the lava
            if (dir == Direction.DOWN && depth == 0) {
                // Check if placing here would create a pillar situation
                // We only want to place below if we can immediately place against the lava from there
                boolean directlyUseful = false;
                for (Direction sideDir : Direction.Plane.HORIZONTAL) {
                    BlockPos sideOfBelow = checkPos.relative(sideDir);
                    if (sideOfBelow.equals(targetPos)) {
                        directlyUseful = true;
                        break;
                    }
                }
                if (!directlyUseful) {
                    continue; // Skip positions below lava that aren't directly useful
                }
            }
            
            // Check if we can place at this position
            boolean canPlace = false;
            for (Direction placeDir : Direction.values()) {
                BlockPos against = checkPos.relative(placeDir);
                // Don't count the target position itself as something to place against
                if (!against.equals(targetPos) && MovementHelper.canPlaceAgainst(context.playerContext(), against)) {
                    canPlace = true;
                    break;
                }
            }
            
            if (canPlace) {
                // Check if this position would block the player's path
                int checkDx = checkPos.getX() - playerPos.getX();
                int checkDz = checkPos.getZ() - playerPos.getZ();
                boolean notBlocking = (Math.abs(checkDx) >= Math.abs(dx) || Math.abs(checkDz) >= Math.abs(dz));
                
                // Calculate a score based on distance and blocking status
                // Lower score is better
                double distance = Math.sqrt(checkDx * checkDx + checkDz * checkDz);
                double score = getScore(dir, notBlocking, distance);

                // At depth 0, only consider non-blocking positions
                // At higher depths, accept any position but prefer non-blocking ones
                if ((notBlocking || depth > 0) && score < bestScore) {
                    bestPos = checkPos;
                    bestScore = score;
                }
            }
        }
        
        if (bestPos != null) {
            return bestPos;
        }
        
        // If we couldn't find a direct placement position, recurse to find positions
        // that we can build from to eventually reach the target
        for (Direction dir : directions) {
            BlockPos checkPos = targetPos.relative(dir);
            
            if (context.getIssueType(checkPos) == HighwayBlockState.Blocks) {
                continue;
            }
            
            BlockPos result = findSupportBlockRecursive(context, checkPos, playerPos, dx, dz, depth + 1, maxDepth);
            if (result != null) {
                return result;
            }
        }
        
        return null; // No valid position found
    }

    private static double getScore(Direction dir, boolean notBlocking, double distance) {
        double score = notBlocking ? distance : distance + 1000; // Penalize blocking positions

        // Penalize positions below that would require pillaring up
        // But placing from above is fine and even preferred
        if (dir == Direction.DOWN) {
            score += 500; // Make below positions less preferred as they require pillaring
        } else if (dir == Direction.UP) {
            score -= 50; // Slightly prefer above positions as they're easy to place from
        }
        return score;
    }
}
