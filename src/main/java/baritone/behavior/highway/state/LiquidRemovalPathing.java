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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class LiquidRemovalPathing extends State {
    private static final int AIM_WAIT_MAX = 10;
    private static final int AIM_RECOVERY_TICKS = 30;

    private static final int FILL_NONE = -1;
    private static final int FILL_PENDING = -2;

    /** Ticks of neither moving, placing nor starting on a new block before we call the approach stuck. */
    private static final int STALL_TICKS = 300;

    private int aimWaitTicks = 0;
    private BetterBlockPos stallAnchor = null;
    private BlockPos breakTarget = null;
    private int stallTicks = 0;

    public LiquidRemovalPathing(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        boolean throughWalls = context.liquidThroughWalls();
        if (context.timer() < (throughWalls ? 1 : 10)) {
            return;
        }

        context.tickThroughWallVerify();
        if (throughWalls && context.throughWallPlaceStreak() >= 60) {
            Helper.HELPER.logDirect("Server keeps reverting through-wall placements. Finishing this removal with line-of-sight placing.");
            context.suppressThroughWalls();
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.LiquidRemovalPrep);
            context.resetTimer();
            return;
        }

        if (!context.sourceBlocks().isEmpty() && context.getIssueType(context.sourceBlocks().getFirst()) == HighwayBlockState.Blocks) {
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.LiquidRemovalPrep);
            context.resetTimer();
            return;
        }

        MobEffectInstance fireRest = context.playerContext().player().getEffect(MobEffects.FIRE_RESISTANCE);
        if (fireRest != null && fireRest.getDuration() < context.fireRestMinDuration()) {
            Helper.HELPER.logDirect("Running out of fire resistance. Restarting liquid clearing.");
            context.transitionTo(HighwayState.Nothing);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.baritone().getPathingBehavior().cancelEverything();
            return;
        }

        // The sealed-pool route enters without fire resistance; if we still caught fire the
        // pool must have breached, so eat a gapple (that flow returns here when done)
        if (throughWalls && fireRest == null
                && (context.playerContext().player().isOnFire() || context.playerContext().player().isInLava())) {
            Helper.HELPER.logDirect("Caught fire while clearing liquids, eating a gapple.");
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.LiquidRemovalGapplePrep);
            context.resetTimer();
            return;
        }

        if (context.sourceBlocks().isEmpty()) {
            context.transitionTo(HighwayState.Nothing);
            return;
        }

        if (context.placeLoc() != null && context.playerContext().player().onGround()
                && context.playerContext().playerFeet().getY() < context.placeLoc().getY()
                && context.getIssueType(context.playerContext().playerFeet()) != HighwayBlockState.Liquids) {
            Helper.HELPER.logDirect("Fell below the liquid clearing level, pathing back");
            context.baritone().getInputOverrideHandler().clearAllKeys();
            context.transitionTo(HighwayState.LiquidRemovalPathingBack);
            context.resetTimer();
            return;
        }

        // Nothing else in this state times out: an approach that cannot make progress - pinned by a
        // block we refuse to mine, or a fill the server keeps refusing - used to hold W against it
        // until the fire resistance ran out minutes later. Standing in the same block without
        // placing anything is the signature of that, so treat it as stuck and re-plan.
        BetterBlockPos feet = context.playerContext().playerFeet();
        if (!feet.equals(stallAnchor)) {
            stallAnchor = feet;
            stallTicks = 0;
        } else if (++stallTicks > STALL_TICKS) {
            stallTicks = 0;
            logStallDiagnostics(context, throughWalls);
            context.baritone().getInputOverrideHandler().clearAllKeys();
            if (throughWalls) {
                // Through-wall filling is what forbids mining the cover in front of us, so drop it
                // for the rest of this removal and dig in with line of sight like the classic flow
                Helper.HELPER.logDirect("Stuck approaching the liquid. Finishing this removal with line-of-sight placing.");
                context.suppressThroughWalls();
                context.transitionTo(HighwayState.LiquidRemovalPrep);
                context.resetTimer();
            } else {
                Helper.HELPER.logDirect("Stuck approaching the liquid. Restarting liquid clearing.");
                context.baritone().getPathingBehavior().cancelEverything();
                context.transitionTo(HighwayState.Nothing);
            }
            return;
        }

        // Everything directional - the support-block test, the source we walk at, which source we
        // fill first - runs off the nearest one we haven't filled yet. The list comes out of a DFS
        // flood fill, so its head can sit on the far side of the lake; walking at that marched us
        // past lava within arm's reach and, now that the approach can dig, would tunnel us there.
        BlockPos approachTarget = nearestUnfilled(context);
        if (approachTarget == null) {
            context.transitionTo(HighwayState.LiquidRemovalPrep); // all filled, let Prep re-scan
            context.resetTimer();
            return;
        }

        boolean supportNeeded = false;
        if (context.getIssueType(approachTarget.north()) != HighwayBlockState.Blocks &&
                context.getIssueType(approachTarget.east()) != HighwayBlockState.Blocks &&
                context.getIssueType(approachTarget.south()) != HighwayBlockState.Blocks &&
                context.getIssueType(approachTarget.west()) != HighwayBlockState.Blocks &&
                context.getIssueType(approachTarget.below()) != HighwayBlockState.Blocks &&
                context.getIssueType(approachTarget.above()) != HighwayBlockState.Blocks) {
            BlockPos playerPos = context.playerContext().playerFeet();

            // Calculate direction from player to lava for non-blocking checks
            int dx = approachTarget.getX() - playerPos.getX();
            int dz = approachTarget.getZ() - playerPos.getZ();
            BlockPos supportPos = findSupportBlockRecursive(context, approachTarget, playerPos, dx, dz, 0, 5);
            
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
        // Pause the placement attempts after too many ticks of waiting for a requested rotation
        // to produce a click (e.g. only a sliver of the face is visible past an edge), so the
        // movement logic below can reposition the player for a better view of the face.
        if (aimWaitTicks > AIM_WAIT_MAX) {
            aimWaitTicks++;
            if (aimWaitTicks > AIM_WAIT_MAX + AIM_RECOVERY_TICKS) {
                aimWaitTicks = 0;
            }
        }
        boolean aimPaused = aimWaitTicks > AIM_WAIT_MAX;

        BlockPos fillTarget;
        if (throughWalls) {
            // No line of sight needed, so fill whichever target is already within reach while the
            // movement below keeps closing in on the rest. Sources first - filling one removes lava
            // for good - then the liquid that is in the way of the approach itself, which is how a
            // dig toward the lava gets to happen without ever opening a lava block.
            fillTarget = nearestReachableFill(context, context.sourceBlocks());
            if (fillTarget == null) {
                fillTarget = nearestReachableFill(context, sealTargets(context));
            }
        } else {
            fillTarget = context.sourceBlocks().getFirst();
            if (!context.placeAimable(fillTarget, (float) context.playerContext().playerController().getBlockReachDistance())) {
                fillTarget = null;
            }
        }
        boolean fillReachable = fillTarget != null;
        if (fillReachable && supportNeeded) {
            context.setLiquidPathingCanMine(false);
        }

        if (fillReachable && !aimPaused) {
            context.baritone().getInputOverrideHandler().clearAllKeys();
            // keep crouching through the aim/place ticks
            context.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);

            int fillSlot = selectFillBlock(context);
            if (fillSlot == FILL_NONE) {
                Helper.HELPER.logDirect("Error getting a block to fill liquids with");
                context.transitionTo(HighwayState.Nothing);
                return;
            }
            if (fillSlot == FILL_PENDING) {
                return; // hotbar swap hasn't landed yet, place on a later tick
            }

            if (context.place(fillTarget, (float) context.playerContext().playerController().getBlockReachDistance(), !throughWalls, false, InteractionHand.MAIN_HAND) == HighwayContext.PlaceResult.Placed) {
                if (throughWalls) {
                    context.noteThroughWallPlace(fillTarget);
                }
                aimWaitTicks = 0;
                stallTicks = 0;
                context.resetTimer();
            } else {
                aimWaitTicks++;
            }
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

            Rotation lavaRot = RotationUtils.calcRotationFromVec3d(context.playerContext().playerHead(), new Vec3(approachTarget.getX(), approachTarget.getY(), approachTarget.getZ()), context.playerContext().playerRotations());
            //RayTraceResult res = RayTraceUtils.rayTraceTowards(ctx.player(), lavaRot, ctx.playerController().getBlockReachDistance(), false);

            ArrayList<BlockPos> possibleIssuePosList = new ArrayList<>();
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos tempLoc = new BlockPos(context.playerContext().playerFeet().x + x, context.playerContext().playerFeet().y, context.playerContext().playerFeet().z + z);
                    for (int i = 0; i < context.settings().highwayHeight.value; i++) {
                        // Never open unfilled lava when filling through walls: the block stays
                        // as cover until the lava behind it is placed over, which frees it up
                        // for mining on a later pass
                        if (throughWalls && mustStayAsCover(context, tempLoc.above(i))) {
                            continue;
                        }
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


            if (!aimPaused) {
                for (BlockPos placeAt : placeAtList) {
                    boolean floorReachable = throughWalls
                            ? context.placeThroughWallsAimable(placeAt, (float) context.playerContext().playerController().getBlockReachDistance())
                            : context.placeAimable(placeAt, (float) context.playerContext().playerController().getBlockReachDistance());
                    if (!floorReachable) {
                        continue;
                    }

                    context.baritone().getInputOverrideHandler().clearAllKeys();
                    // placing floor below feet means there are holes around us, stay
                    // crouched for the whole aim/place sequence
                    context.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                    int fillSlot = selectFillBlock(context);
                    if (fillSlot == FILL_NONE) {
                        Helper.HELPER.logDirect("Error getting a block to fill liquids with");
                        context.transitionTo(HighwayState.Nothing);
                        return;
                    }
                    if (fillSlot == FILL_PENDING) {
                        return; // hotbar swap hasn't landed yet, place on a later tick
                    }

                    double lastX = context.playerContext().getPlayerEntity().getXLast();
                    double lastY = context.playerContext().getPlayerEntity().getYLast();
                    double lastZ = context.playerContext().getPlayerEntity().getZLast();
                    final Vec3 pos = new Vec3(lastX + (context.playerContext().player().getX() - lastX) * context.playerContext().minecraft().getDeltaTracker().getGameTimeDeltaPartialTick(true),
                            lastY + (context.playerContext().player().getY() - lastY) * context.playerContext().minecraft().getDeltaTracker().getGameTimeDeltaPartialTick(true),
                            lastZ + (context.playerContext().player().getZ() - lastZ) * context.playerContext().minecraft().getDeltaTracker().getGameTimeDeltaPartialTick(true));
                    BetterBlockPos originPos = new BetterBlockPos(pos.x, pos.y+0.5f, pos.z);
                    double l_Offset = pos.y - originPos.getY();
                    HighwayContext.PlaceResult placeResult = context.place(placeAt, (float) context.playerContext().playerController().getBlockReachDistance(), !throughWalls, l_Offset == -0.5f, InteractionHand.MAIN_HAND);
                    if (placeResult == HighwayContext.PlaceResult.Placed) {
                        if (throughWalls) {
                            context.noteThroughWallPlace(placeAt);
                        }
                        aimWaitTicks = 0;
                        stallTicks = 0;
                        context.resetTimer();
                        return;
                    }
                    if (placeResult == HighwayContext.PlaceResult.Aiming) {
                        aimWaitTicks++;
                        // rotation was sent this tick, click on a following tick
                        return;
                    }
                }
            }

            ArrayList<Rotation> possibleIssueReachableList = new ArrayList<>();
            BlockPos firstIssuePos = null;
            for (BlockPos curIssuePos : possibleIssuePosList) {
                Optional<Rotation> curIssuePosReachable = RotationUtils.reachable(context.playerContext(), curIssuePos, context.playerContext().playerController().getBlockReachDistance());
                BlockState state = context.playerContext().world().getBlockState(curIssuePos);
                Block block = state.getBlock();
                if (block != Blocks.BEDROCK && !(block instanceof LiquidBlock) && !(block instanceof AirBlock) && curIssuePosReachable.isPresent() && curIssuePos.getY() >= context.settings().highwayMainY.value) {
                    if (possibleIssueReachableList.isEmpty()) {
                        firstIssuePos = curIssuePos;
                    }
                    possibleIssueReachableList.add(curIssuePosReachable.get());
                }
            }
            if (!possibleIssueReachableList.isEmpty()) {
                noteBreaking(firstIssuePos);
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
                    if (throughWalls && mustStayAsCover(context, checkPos)) {
                        continue; // still cover for unfilled lava, fill through it instead
                    }
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
                        noteBreaking(blockToBreak);
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

    private static int selectFillBlock(HighwayContext context) {
        int slot = context.putAcceptableThrowawayHotbar();
        if (slot == -1) {
            return FILL_NONE;
        }
        if (slot >= 9) {
            return FILL_PENDING;
        }
        context.playerContext().player().getInventory().selected = slot;
        return slot;
    }

    /**
     * Whether pos still has to stay where it is as cover over liquid instead of being mined out of
     * the way. Mining it would open lava we are not protected from, so we place into that lava
     * first ({@link #sealTargets}) and the block frees itself a tick or two later.
     */
    private static boolean mustStayAsCover(HighwayContext context, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (context.getIssueType(pos.relative(dir)) == HighwayBlockState.Liquids) {
                return true;
            }
        }
        return false;
    }

    /**
     * Liquid that is in the way of the approach: the neighbors of every block the mining scan wants
     * to break - they are what holds it back as cover - plus the cells our own body could walk into.
     * <p>
     * Filling these is what lets a through-wall removal dig toward the lava without ever standing
     * next to an open lava block: seal, break the block that was covering it, seal again from one
     * step further in. Every fill is permanent and every step consumes at least one lava block, so
     * the tunnel always terminates. Reach is never the problem either - the block being held back
     * is itself solid and adjacent to us, so the face that points into its liquid neighbor is about
     * two blocks from our eyes, and the same is true of the seal we just placed on the next step.
     * <p>
     * Sources are handled ahead of these: filling a source removes lava for good, while filling
     * flowing lava only dams it (it dries up on its own once the supply is gone).
     */
    private static ArrayList<BlockPos> sealTargets(HighwayContext context) {
        BetterBlockPos feet = context.playerContext().playerFeet();
        int mainY = context.settings().highwayMainY.value;
        ArrayList<BlockPos> targets = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int i = 0; i < context.settings().highwayHeight.value; i++) {
                    BlockPos pos = new BlockPos(feet.getX() + x, feet.getY() + i, feet.getZ() + z);
                    if (pos.getY() < mainY) {
                        continue;
                    }
                    HighwayBlockState issue = context.getIssueType(pos);
                    if (issue == HighwayBlockState.Liquids) {
                        // Lava our body could walk into. The pocket we stand and move in has to be
                        // solid or air, never lava - that is the other half of not needing a gapple.
                        if (i < 2 && !(x == 0 && z == 0) && !targets.contains(pos)) {
                            targets.add(pos);
                        }
                        continue;
                    }
                    // Cover only holds us back if we were going to dig at all
                    if (!context.liquidPathingCanMine()
                            || issue != HighwayBlockState.Blocks
                            || context.playerContext().world().getBlockState(pos).getBlock() == Blocks.BEDROCK
                            || !mustStayAsCover(context, pos)) {
                        continue;
                    }
                    for (Direction dir : Direction.values()) {
                        BlockPos neighbor = pos.relative(dir);
                        if (context.getIssueType(neighbor) == HighwayBlockState.Liquids && !targets.contains(neighbor)) {
                            targets.add(neighbor);
                        }
                    }
                }
            }
        }
        return targets;
    }

    /** The closest of candidates we can still place into and can reach through walls, or null. */
    private static BlockPos nearestReachableFill(HighwayContext context, List<BlockPos> candidates) {
        float reach = (float) context.playerContext().playerController().getBlockReachDistance();
        Vec3 head = context.playerContext().playerHead();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos candidate : candidates) {
            if (context.getIssueType(candidate) == HighwayBlockState.Blocks) {
                continue; // already filled
            }
            double dist = head.distanceToSqr(Vec3.atCenterOf(candidate));
            // reach is measured to a face of a neighbor, so anything a block past it is hopeless;
            // this keeps the neighbor scan off the far side of a big lake every tick
            if (dist >= bestDist || dist > (reach + 1) * (reach + 1)) {
                continue;
            }
            if (context.placeThroughWallsAimable(candidate, reach)) {
                best = candidate;
                bestDist = dist;
            }
        }
        return best;
    }

    /** The closest entry of {@link HighwayContext#sourceBlocks()} that isn't solid yet. */
    private static BlockPos nearestUnfilled(HighwayContext context) {
        Vec3 head = context.playerContext().playerHead();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos source : context.sourceBlocks()) {
            if (context.getIssueType(source) == HighwayBlockState.Blocks) {
                continue;
            }
            double dist = head.distanceToSqr(Vec3.atCenterOf(source));
            if (dist < bestDist) {
                bestDist = dist;
                best = source;
            }
        }
        return best;
    }

    /**
     * Breaking a block is progress even though we stand still for it, and a bare netherite pick
     * spends 167 ticks on one obsidian - but only while the target keeps changing. Hammering the
     * same block forever is the stall we are looking for, so that one still counts up.
     */
    private void noteBreaking(BlockPos pos) {
        if (!pos.equals(breakTarget)) {
            breakTarget = pos;
            stallTicks = 0;
        }
    }

    private static void logStallDiagnostics(HighwayContext context, boolean throughWalls) {
        BetterBlockPos feet = context.playerContext().playerFeet();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        int unfilled = 0;
        for (BlockPos source : context.sourceBlocks()) {
            if (context.getIssueType(source) == HighwayBlockState.Blocks) {
                continue;
            }
            unfilled++;
            double dist = context.playerContext().playerHead().distanceTo(Vec3.atCenterOf(source));
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = source;
            }
        }

        StringBuilder sides = new StringBuilder();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(dir);
            sides.append(dir.getName()).append('=').append(context.getIssueType(side));
            if (context.getIssueType(side) == HighwayBlockState.Blocks && mustStayAsCover(context, side)) {
                sides.append("(cover)");
            }
            sides.append(' ');
        }

        Helper.HELPER.logDirect("Liquid approach made no progress at " + feet + ": " + unfilled + " unfilled source(s), nearest "
                + nearest + " at " + String.format("%.1f", nearestDist) + " blocks, " + sealTargets(context).size()
                + " seal target(s), throughWalls=" + throughWalls + ", canMine=" + context.liquidPathingCanMine()
                + ", sides: " + sides.toString().trim());
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
