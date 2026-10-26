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

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.schematic.WhiteBlackSchematic;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import baritone.behavior.highway.enums.ShulkerType;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Picking a spot for a shulker box or ender chest on the interaction or side-storage lane, and
 * placing it there.
 */
public class ShulkerPlacement extends ContextComponent {
    private static final int SHULKER_PLACE_ANCHOR_BACK = 7; // where a box goes when the lane behind us is fine
    private static final int SHULKER_PLACE_MAX_BACK = 31; // how far back the scan walks looking for one that isn't
    private static final int SHULKER_PLACE_MAX_AHEAD = 16; // last resort, for a start with nothing usable behind it

    private static final int SHULKER_SPOT_MEMORY = 64; // how many hopeless spots to remember before starting over

    private final Set<BlockPos> badShulkerSpots = new HashSet<>();

    private static final int STAND_FLOOR_TRIES = 3; // builds of a missing side-storage stand floor before giving up on the spot
    private final SpotAttempts standFloorTries = new SpotAttempts(); // counted against the floor cell
    private static final int STAND_PATH_TRIES = 5; // paths to a side-storage stand that end short of it before giving up on the spot
    private final SpotAttempts standPathTries = new SpotAttempts(); // counted against the placing spot

    ShulkerPlacement(HighwayContext ctx) {
        super(ctx);
    }

    /**
     * The interaction-lane spot {@code back} blocks behind us (negative is ahead). The projection is
     * taken at our own position and stepped from there, because {@link HighwayLayout#getClosestPoint} clamps
     * every point to the build start: projecting an already shifted position would snap the whole
     * scan onto the first slice and never reach the pavement laid before this build began.
     *
     * <p>{@code back} counts slices, not blocks - see {@link HighwayLayout#alongLine}.
     */
    private BetterBlockPos shulkerPlaceLocAt(int back) {
        Vec3 origin = new Vec3(layout().backPathOriginVector().x, layout().backPathOriginVector().y, layout().backPathOriginVector().z);
        Vec3 direction = new Vec3(layout().highwayDirection().getX(), layout().highwayDirection().getY(), layout().highwayDirection().getZ());
        Vec3 feet = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        BetterBlockPos lane = layout().getClosestPoint(origin, direction, feet, LocationType.ShulkerEchestInteraction);
        return layout().liftOntoPavement(layout().alongLine(lane, LocationType.ShulkerEchestInteraction, -back));
    }

    /**
     * Where to set a shulker box (or the obsidian farm's ender chest) down: anchored
     * {@value #SHULKER_PLACE_ANCHOR_BACK} blocks back, walked further back along the lane when that
     * spot can't hold a box or piglins are standing by it, and only then tried ahead of us. Starting
     * a build at the end of an already paved stretch used to hand back the anchor no matter what,
     * which over the drop past the pavement is thin air: with no block left to prop the box up with,
     * placing there can never succeed and the place/support states just ping-pong forever.
     */
    public BetterBlockPos shulkerPlaceLocClearOfThieves() {
        double radius = settings.highwayShulkerTheftGuardRadius.value;
        int anchorBack = layout().slicesForBlocks(SHULKER_PLACE_ANCHOR_BACK);
        BetterBlockPos anchor = shulkerPlaceLocAt(anchorBack);
        boolean anchorUsable = isShulkerPlaceSpotUsable(anchor);
        BetterBlockPos thiefDirty = null;
        for (int back : shulkerPlaceScanOffsets()) {
            BetterBlockPos candidate = back == anchorBack ? anchor : shulkerPlaceLocAt(back);
            if (!isShulkerPlaceSpotUsable(candidate)) {
                continue;
            }
            if (ctx.threats().shulkerThiefNear(candidate, radius)) {
                if (thiefDirty == null) {
                    thiefDirty = candidate;
                }
                continue;
            }
            if (back != anchorBack) {
                String where = back >= 0
                        ? Math.round((back - anchorBack) * layout().stepLength()) + " blocks further back"
                        : Math.round(-back * layout().stepLength()) + " blocks ahead";
                Helper.HELPER.logDirect(anchorUsable
                        ? "Piglins that could steal the shulker are near the placing location, placing it " + where + "."
                        : "Nothing to set the shulker on at the placing location, placing it " + where + ".");
            }
            return candidate;
        }
        // every usable candidate is dirty; the mining guard and the thief hunt cover the rest
        return thiefDirty != null ? thiefDirty : anchor;
    }

    /**
     * Lane offsets to try, in order: back onto what's already paved, then closer in, then ahead of
     * us. In slices, so the distances the constants name stay the same length of road whatever the
     * heading - an angled pattern's slice is shorter than a block.
     */
    private int[] shulkerPlaceScanOffsets() {
        int anchorBack = layout().slicesForBlocks(SHULKER_PLACE_ANCHOR_BACK);
        int maxBack = layout().slicesForBlocks(SHULKER_PLACE_MAX_BACK);
        int maxAhead = layout().slicesForBlocks(SHULKER_PLACE_MAX_AHEAD);
        int[] offsets = new int[maxBack + maxAhead + 1];
        int i = 0;
        for (int back = anchorBack; back <= maxBack; back++) {
            offsets[i++] = back;
        }
        for (int back = anchorBack - 1; back >= -maxAhead; back--) {
            offsets[i++] = back;
        }
        return offsets;
    }

    /**
     * A lane spot we can actually place into and reach: lava-free, with something solid under the
     * box - or, while we still carry a block to build one with, a face a support block can go on -
     * and a floor under the spots the go-to states stand on (two back - or one further, past an
     * angled pattern's jog - for the ender chest flow, one ahead for the shulker ones). A spot
     * hanging over the end of the pavement has none of that.
     */
    public boolean isShulkerPlaceSpotUsable(BlockPos placeLoc) {
        if (!isSideStorageSpotSafe(placeLoc)) {
            return false;
        }

        if (isShulkerSpotKnownBad(placeLoc) || !shulkerColumnWorkable(placeLoc)) {
            return false;
        }
        if (!hasFloorUnder(placeLoc) && !(ctx.inventory().canBuildSupportBlock() && hasSturdyNeighbor(placeLoc.below()))) {
            return false;
        }
        // Every cell from the ender chest flow's stand spot up to the shulker flows' - which is one
        // slice further back than -2 wherever an angled pattern jogs there, see standOffSlices
        int backStand = layout().standOffSlices(placeLoc, LocationType.ShulkerEchestInteraction, -2);
        for (int step = backStand; step <= 1; step++) {
            if (step == 0) {
                continue;
            }
            if (!hasFloorUnder(layout().alongLine(placeLoc, LocationType.ShulkerEchestInteraction, step))) {
                return false;
            }
        }
        return true;
    }

    boolean hasFloorUnder(BlockPos pos) {
        return MovementHelper.canWalkOn(baritone.bsi, pos.getX(), pos.getY() - 1, pos.getZ());
    }

    public HighwayState placeShulkerBox(BlockPos shulkerPlaceLoc, HighwayState prevHighwayState, HighwayState currentHighwayState, HighwayState nextHighwayState, ShulkerType shulkerType) {
        // Debug logging to track BlockPos types
        Helper.HELPER.logDebug("placeShulkerBox called with: " + shulkerPlaceLoc + " (class: " + shulkerPlaceLoc.getClass().getSimpleName() + ")");

        BlockState currentState = playerContext.world().getBlockState(shulkerPlaceLoc);
        if (currentState.getBlock() instanceof ShulkerBoxBlock) {
            return currentHighwayState;
        }
        // Check if we can place at this location
        if (!canPlaceBlockAt(shulkerPlaceLoc)) {
            Helper.HELPER.logDirect("Cannot place shulker at " + shulkerPlaceLoc);
            return prevHighwayState;
        }

        // Get shulker slot and validate
        int shulkerSlot = ctx.inventory().putShulkerHotbar(shulkerType);
        if (shulkerSlot == -1) {
            // Returning the current state here means "click sent" to the caller, which then waits
            // out the confirm timeout for a box that never existed. Let BuildingHighway sort out
            // where it went instead.
            Helper.HELPER.logDirect("No " + shulkerType + " shulker in the inventory to place, restarting.");
            return HighwayState.Nothing;
        }
        if (shulkerSlot >= 9) {
            Helper.HELPER.logDirect("Couldn't put shulker to hotbar, waiting");
            return currentHighwayState;
        }

        // Validate we have the right item
        ItemStack stack = playerContext.player().getInventory().getNonEquipmentItems().get(shulkerSlot);
        if (!HighwayContext.shulkerItemList.contains(stack.getItem())) {
            Helper.HELPER.logDirect("Invalid shulker item in slot");
            return currentHighwayState;
        }

        // Determine placement direction and target position. The side matters twice over for a
        // shulker: it is what the box attaches to, and it decides which way the box faces.
        Direction placeSide = getBestPlaceSide(shulkerPlaceLoc, true);
        if (placeSide == null) {
            Helper.HELPER.logDirect("No side to place the shulker against that would leave it openable");
            return prevHighwayState;
        }

        // Last line of defence against placing through a wall: the caller clears the way first,
        // but a click we can't see the face for puts the box somewhere nothing can reach it again.
        Optional<Rotation> targetRotation = shulkerPlaceAim(shulkerPlaceLoc, placeSide);
        if (targetRotation.isEmpty()) {
            Helper.HELPER.logDirect("No clear line to the shulker spot at " + shulkerPlaceLoc.toShortString() + ", not placing");
            return prevHighwayState;
        }

        BlockPos neighbor = shulkerPlaceLoc.relative(placeSide);
        Vec3 hitPos = placeHitPos(shulkerPlaceLoc, placeSide);
        BlockHitResult blockHitResult = new BlockHitResult(hitPos, placeSide.getOpposite(), neighbor, false);

        // Use look behavior to rotate to target, then place
        baritone.getLookBehavior().updateTarget(targetRotation.get(), true);

        // Select the shulker slot
        playerContext.player().getInventory().setSelectedSlot(shulkerSlot);

        // Perform the placement using proper interaction
        InteractionResult result = playerContext.playerController().processRightClickBlock(
            playerContext.player(),
            playerContext.world(),
            InteractionHand.MAIN_HAND,
            blockHitResult
        );

        if (result.consumesAction()) {
            // Placement attempted successfully - don't check for immediate success
            Helper.HELPER.logDirect("Shulker placement attempted at " + shulkerPlaceLoc);
            return currentHighwayState; // Let the calling state handle waiting for confirmation
        } else {
            return ctx.containerRecovery().noteShulkerPlaceRefused(shulkerPlaceLoc, prevHighwayState);
        }
    }

    public boolean placeBlockAgainstNeighbor(BlockPos placeLoc, int itemSlot) {
        if (!canPlaceBlockAt(placeLoc)) {
            return false;
        }

        Direction placeSide = getBestPlaceSide(placeLoc);
        if (placeSide == null) {
            return false;
        }

        BlockPos neighbor = placeLoc.relative(placeSide);
        Vec3 hitPos = Vec3.atCenterOf(placeLoc).add(
                placeSide.getStepX() * 0.5,
                placeSide.getStepY() * 0.5,
                placeSide.getStepZ() * 0.5
        );
        BlockHitResult blockHitResult = new BlockHitResult(hitPos, placeSide.getOpposite(), neighbor, false);

        Rotation targetRotation = RotationUtils.calcRotationFromVec3d(
                RayTraceUtils.inferSneakingEyePosition(playerContext.player()),
                hitPos,
                playerContext.playerRotations()
        );
        baritone.getLookBehavior().updateTarget(targetRotation, true);
        playerContext.player().getInventory().setSelectedSlot(itemSlot);

        InteractionResult result = playerContext.playerController().processRightClickBlock(
                playerContext.player(),
                playerContext.world(),
                InteractionHand.MAIN_HAND,
                blockHitResult
        );
        return result.consumesAction();
    }

    public boolean isSideStorageSpotSafe(BlockPos placeLoc) {
        BlockPos[] column = {placeLoc, placeLoc.above(), placeLoc.below()};
        for (BlockPos p : column) {
            if (isLavaAt(p)) {
                return false;
            }
        }
        for (BlockPos p : new BlockPos[]{placeLoc, placeLoc.above()}) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (isLavaAt(p.relative(d))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isLavaAt(BlockPos pos) {
        return playerContext.world().getBlockState(pos).getFluidState().is(FluidTags.LAVA);
    }

    public BlockPos findSafeSideStorageSpot(int minBack, int maxBack) {
        BlockPos behind = scanSideStorageSpots(layout().eChestEmptyShulkOriginVector(), minBack, maxBack, -1);
        if (behind != null) {
            return behind;
        }
        // Nothing usable behind us. At a crossing with another highway the side lane is that
        // highway's corridor rather than our own rail: open air with nothing to set a box on and no
        // floor to stand on. Look the same distance ahead along our own highway instead.
        BlockPos ahead = scanSideStorageSpots(layout().eChestEmptyShulkOriginVector(), minBack, maxBack, 1);
        if (ahead != null || layout().pattern().isCustom()) {
            // an angled pattern's lane steps follow the low side's slices, which aren't the far side's
            return ahead;
        }
        // A road can run right alongside ours, its rail in our side-storage column the whole way,
        // like the road an area-clearing lane is cut next to. Try the same column on our other side.
        behind = scanSideStorageSpots(layout().farSideStorageOriginVector(), minBack, maxBack, -1);
        if (behind != null) {
            return behind;
        }
        return scanSideStorageSpots(layout().farSideStorageOriginVector(), minBack, maxBack, 1);
    }

    /**
     * Walks a side-storage line looking for a usable spot, {@code sign} -1 back along the highway
     * and +1 ahead of us.
     *
     * @param line a point on the line: this side's or the far side's
     */
    private BlockPos scanSideStorageSpots(Vec3 line, int minDist, int maxDist, int sign) {
        Vec3 direction = new Vec3(layout().highwayDirection().getX(), layout().highwayDirection().getY(), layout().highwayDirection().getZ());
        Vec3 origin = new Vec3(line.x, line.y, line.z);
        // Project our feet once and walk the line in slices from there. Projecting every candidate
        // instead ran each one through the build's start and end clamps: on a short run they all
        // collapsed onto the first and last slice, and the pavement behind the start never got
        // looked at. Slice steps also keep an angled pattern on its path, where shifting our feet
        // by the direction vector would step a 45 degree diagonal away from it.
        BetterBlockPos feetOnLine = layout().getClosestPoint(origin, direction,
                new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ()),
                LocationType.SideStorage);
        for (int slices = layout().slicesForBlocks(minDist); slices <= layout().slicesForBlocks(maxDist); slices++) {
            BetterBlockPos candidate = layout().alongLine(feetOnLine, LocationType.SideStorage, slices * sign);
            if (isSideStorageSpotUsable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * A side-storage spot we can actually work with: lava-free, with something under it to set the
     * box on, and a floor beside it to stand on while placing and opening it. The lava-only check
     * this wraps happily handed back spots hanging in the middle of a crossing highway's corridor,
     * where the support block has nothing to attach to and the standing spot is thin air. Missing
     * blocks under the box or the standing spots are fine while we can build them: the support
     * states put the box's down, {@link #buildSideStorageStandFloor} the standing spots'.
     */
    public boolean isSideStorageSpotUsable(BlockPos placeLoc) {
        if (!isSideStorageSpotSafe(placeLoc)) {
            return false;
        }

        if (isShulkerSpotKnownBad(placeLoc) || !shulkerColumnWorkable(placeLoc) || !sideStorageColumnFree(placeLoc)) {
            return false;
        }
        if (!hasFloorUnder(placeLoc) && !canBuildFloorAt(placeLoc.below())) {
            return false;
        }
        // We stand one step along the highway from the box to place it and to open it, and one
        // step further back when our own body turns out to be in the way of the placement
        for (int step : new int[]{1, 2}) {
            BlockPos stand = layout().standOffAlongLine(placeLoc, LocationType.SideStorage, step);
            if (!hasFloorUnder(stand) && !canBuildFloorAt(stand.below())) {
                return false;
            }
            if (!canDigOutStand(stand)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the pathfinder will clear {@code stand} for our body: each cell already open, or one
     * it is willing to dig. It won't dig a block with liquid beside or above it, so a stand cell
     * walling off lava outside the lane passed the box column's lava check and left the go-to
     * state asking for a path that never came.
     */
    private boolean canDigOutStand(BlockPos stand) {
        for (BlockPos cell : new BlockPos[]{stand, stand.above()}) {
            int x = cell.getX();
            int y = cell.getY();
            int z = cell.getZ();
            if (MovementHelper.canWalkThrough(baritone.bsi, x, y, z)) {
                continue;
            }
            BlockState state = baritone.bsi.get0(x, y, z);
            if (!state.getFluidState().isEmpty() || MovementHelper.avoidBreaking(baritone.bsi, x, y, z, state)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sends us to {@code stand} to work on the side-storage spot. The goal process drops out short
     * of the goal whenever no path is found, and the go-to states used to just ask again, for good:
     * a spot can pass the scan and still be one the pathfinder won't get us next to. Each ask that
     * doesn't end on a stand counts against the spot, and past the last one we move to
     * {@code relocateState} instead.
     */
    public void pathToSideStorageStand(BlockPos stand, HighwayState relocateState) {
        if (standPathTries.at(ctx.placeLoc()).next() > STAND_PATH_TRIES) {
            rememberBadShulkerSpot(ctx.placeLoc());
            Helper.HELPER.logDirect("Couldn't find a path to stand next to " + ctx.placeLoc().toShortString() + ", picking a different spot.");
            standPathTries.clear();
            baritone.getPathingBehavior().cancelEverything();
            ctx.transitionTo(relocateState);
            return;
        }
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(stand));
    }

    /** We made it onto a side-storage stand, so the path requests it took were no failures. */
    public void reachedSideStorageStand() {
        standPathTries.clear();
    }

    /**
     * Whether a support block can go in {@code floor}: we carry a block to build it out of, and it
     * has a face to be placed against.
     */
    private boolean canBuildFloorAt(BlockPos floor) {
        return ctx.inventory().canBuildSupportBlock() && !isLavaAt(floor) && hasSturdyNeighbor(floor);
    }

    /** Builds a support block at {@code pos}: netherrack, or obsidian when we carry none. */
    public void buildSupportBlock(BlockPos pos) {
        WhiteBlackSchematic supportSchem = new WhiteBlackSchematic(1, 1, 1, ctx.blackListBlocks(), Blocks.NETHERRACK.defaultBlockState(), false, false, true);
        supportSchem.setThrowawayFallback(Blocks.OBSIDIAN.defaultBlockState());
        baritone.getBuilderProcess().build("supportBlock", supportSchem, pos);
    }

    /** Builds a support out of this block only, rather than whatever throwaway block is at hand */
    public void buildSupportBlock(BlockPos pos, Block block) {
        WhiteBlackSchematic supportSchem = new WhiteBlackSchematic(1, 1, 1, ctx.blackListBlocks(), block.defaultBlockState(), false, false, false);
        baritone.getBuilderProcess().build("supportBlock", supportSchem, pos);
    }

    /**
     * Out over open ground, like an intersection, the side lane has nothing under the cells we
     * stand in to place and open the box. The go-to states walk to the exact cell above that floor
     * and the box's own support only goes down once we're there, so the floor has to be built first.
     *
     * @return true while a floor is going down, or after giving up on the spot and moving to
     *         {@code relocateState}; either way the caller is done for this tick
     */
    public boolean buildSideStorageStandFloor(HighwayState relocateState) {
        if (ctx.placeLocLine() != LocationType.SideStorage) {
            return false;
        }
        for (int step : new int[]{1, 2}) {
            BetterBlockPos stand = ctx.placeLocStand(step);
            if (hasFloorUnder(stand)) {
                continue;
            }
            BlockPos floor = new BlockPos(stand.getX(), stand.getY() - 1, stand.getZ());
            standFloorTries.at(floor);
            if (!canBuildFloorAt(floor) || standFloorTries.next() > STAND_FLOOR_TRIES) {
                rememberBadShulkerSpot(ctx.placeLoc());
                Helper.HELPER.logDirect("Couldn't build a floor to stand on next to " + ctx.placeLoc().toShortString() + ", picking a different spot.");
                standFloorTries.clear();
                ctx.transitionTo(relocateState);
                return true;
            }
            Helper.HELPER.logDirect("Nothing to stand on next to " + ctx.placeLoc().toShortString() + ", building a floor at " + floor.toShortString() + ".");
            baritone.getPathingBehavior().cancelEverything();
            settings.buildRepeat.value = new Vec3i(0, 0, 0);
            buildSupportBlock(floor);
            return true;
        }
        return false;
    }

    private boolean hasSturdyNeighbor(BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos neighbor = pos.relative(d);
            BlockState state = playerContext.world().getBlockState(neighbor);
            if (!state.isAir() && state.getFluidState().isEmpty()
                    && state.isFaceSturdy(playerContext.world(), neighbor, d.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    private boolean canPlaceBlockAt(BlockPos pos) {
        // Check if position is valid
        if (!playerContext.world().isInWorldBounds(pos)) return false;

        // Check if current block is replaceable
        BlockState currentState = playerContext.world().getBlockState(pos);

        return currentState.canBeReplaced() || currentState.getBlock() instanceof SnowLayerBlock;
    }

    /** Whether a block placed at {@code pos} has something to attach to on {@code side}. */
    private boolean canPlaceAgainst(BlockPos pos, Direction side) {
        BlockPos neighbor = pos.relative(side);
        BlockState neighborState = playerContext.world().getBlockState(neighbor);
        return !neighborState.isAir()
                && neighborState.getFluidState().isEmpty()
                && neighborState.isFaceSturdy(playerContext.world(), neighbor, side.getOpposite());
    }

    private Direction getBestPlaceSide(BlockPos pos) {
        return getBestPlaceSide(pos, false);
    }

    Direction getBestPlaceSide(BlockPos pos, boolean openableFacing) {
        // Set on the floor, a box faces up into the cell above - the one the place states clear and
        // the spot scan insists on. Any other support side leaves it facing sideways at whatever the
        // lane happens to hold, so take the floor whenever there is one.
        if (openableFacing && canPlaceAgainst(pos, Direction.DOWN) && canShulkerOpenToward(pos, Direction.UP)) {
            return Direction.DOWN;
        }

        Vec3 eyePos = playerContext.player().getEyePosition();
        Vec3 blockCenter = Vec3.atCenterOf(pos);
        Vec3 lookVec = blockCenter.subtract(eyePos);

        double bestRelevancy = -Double.MAX_VALUE;
        Direction bestSide = null;

        for (Direction side : Direction.values()) {
            // Check if neighbor can be placed against (and isn't a fluid)
            if (!canPlaceAgainst(pos, side)) {
                continue;
            }

            // The box would face the other way; no room for the lid there means no opening it, ever
            if (openableFacing && !canShulkerOpenToward(pos, side.getOpposite())) {
                continue;
            }

            double relevancy = side.getAxis().choose(lookVec.x, lookVec.y, lookVec.z) * side.getAxisDirection().getStep();
            if (relevancy > bestRelevancy) {
                bestRelevancy = relevancy;
                bestSide = side;
            }
        }

        return bestSide;
    }

    public boolean canShulkerOpenToward(BlockPos pos, Direction facing) {
        AABB lid = Shulker.getProgressDeltaAabb(1.0F, facing, 0.0F, 0.5F, pos.getBottomCenter()).deflate(1.0E-6);
        return playerContext.world().noBlockCollision(null, lid);
    }

    /**
     * A cell a box can sit in or open into: already clear, or something the builder can dig out,
     * and not one another road keeps (highwayCellOverrides).
     */
    private boolean clearOrClearable(BlockPos pos) {
        BlockState state = playerContext.world().getBlockState(pos);
        if (state.getFluidState().is(FluidTags.LAVA) || layout().cellOverrides().kindAt(pos.getX(), pos.getY(), pos.getZ()) != null) {
            return false;
        }
        return state.canBeReplaced() || state.getDestroySpeed(playerContext.world(), pos) >= 0;
    }

    /**
     * The side-storage lane runs outside our corridor, where no override names another road's
     * cells, and on a lane at rail height: obsidian or a box in the column is a crossing road's
     * rail or side storage, which placing there would dig.
     */
    private boolean sideStorageColumnFree(BlockPos placeLoc) {
        for (BlockPos pos : new BlockPos[]{placeLoc, placeLoc.above()}) {
            BlockState state = playerContext.world().getBlockState(pos);
            if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN) || state.is(Blocks.ENDER_CHEST)
                    || HighwayContext.shulkerBlockList.contains(state.getBlock())) {
                return false;
            }
        }
        return true;
    }

    private boolean shulkerColumnWorkable(BlockPos placeLoc) {
        return clearOrClearable(placeLoc) && clearOrClearable(placeLoc.above());
    }

    public boolean isShulkerSpotKnownBad(BlockPos pos) {
        return badShulkerSpots.contains(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
    }

    public void rememberBadShulkerSpot(BlockPos spot) {
        if (badShulkerSpots.size() >= SHULKER_SPOT_MEMORY) {
            badShulkerSpots.clear(); // the lane moves on, so old entries can never come up again
        }
        badShulkerSpots.add(spot);
    }

    private static Vec3 placeHitPos(BlockPos pos, Direction placeSide) {
        return Vec3.atCenterOf(pos).add(placeSide.getStepX() * 0.5, placeSide.getStepY() * 0.5, placeSide.getStepZ() * 0.5);
    }

    private Optional<Rotation> shulkerPlaceAim(BlockPos placeLoc, Direction placeSide) {
        return RotationUtils.reachable(playerContext, placeLoc.relative(placeSide),
                playerContext.playerController().getBlockReachDistance());
    }

    public boolean canReachShulkerPlacement(BlockPos placeLoc) {
        Direction placeSide = getBestPlaceSide(placeLoc, true);
        return placeSide == null || shulkerPlaceAim(placeLoc, placeSide).isPresent();
    }
}
