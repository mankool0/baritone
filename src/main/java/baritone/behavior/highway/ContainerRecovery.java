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
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What to do about a container we can't see, reach, place or open, and about a box that went
 * missing. Each counts its attempts against the spot and gives the spot up once they run out.
 */
public class ContainerRecovery extends ContextComponent {
    private static final int SHULKER_OPEN_RETRIES = 3; // plain re-places to spend before treating an open as stuck
    private static final int SHULKER_OPEN_UNBLOCK_TRIES = 3; // digs at whatever the lid is pushing into, after those

    private final SpotAttempts shulkerOpenFails = new SpotAttempts(); // tries at a box or chest that won't open

    private static final int SHULKER_SIGHT_DIGS = 6; // a reach-length of blocks is the most that can be in the way
    private static final int SHULKER_SIGHT_WALKS = 3; // rounds of stepping back onto the standing spot
    private static final int SHULKER_SIGHT_DIG_SPINUP = 5; // ticks a dispatched clearArea takes to read as an active builder
    private static final int SHULKER_STAND_CLEAR_TRIES = 3; // walks off the spot to try before giving it up
    private static final int SHULKER_PLACE_REFUSALS = 40; // refused clicks to sit through before the spot is the problem
    private final SpotAttempts placeRefusals = new SpotAttempts();
    private final SpotAttempts standClearTries = new SpotAttempts(); // walks off a spot we stand in
    private boolean standClearWalking = false; // a step-off walk we sent and haven't seen finish yet

    // digs at what blocks the line to a placing spot, and walks back onto where we place it from
    private final SpotAttempts shulkerSightDigs = new SpotAttempts();
    private final SpotAttempts shulkerSightWalks = new SpotAttempts();
    private long shulkerSightLastDigTick = Long.MIN_VALUE / 2;
    // the same for a placed container we can't reach
    private final SpotAttempts reachDigs = new SpotAttempts();
    private final SpotAttempts reachWalks = new SpotAttempts();
    private long reachLastDigTick = Long.MIN_VALUE / 2;
    private boolean reachWalking = false; // a walk back onto the standing spot we sent and haven't seen finish yet
    private static final int SNEAK_TAP_TICKS = 4; // two down, two up: see runSneakTap
    private BlockPos refusedOpenSpot = null; // first spot since the last open where the server ignored a good click
    private BlockPos sneakTapSpot = null; // spot the one sneak tap below was spent on
    private int sneakTapTicks = 0; // ticks left in a running sneak tap, 0 when none
    private boolean refusedOpenRelogAttempted = false; // cleared only by an open, so a rejoin that didn't help can't loop

    private boolean lostShulkerRelogAttempted = false;

    ContainerRecovery(HighwayContext ctx) {
        super(ctx);
    }

    @Override
    void onBuildStart() {
        clearLostShulkerRelogAttempt();
    }

    public BlockPos findMisplacedShulkerBox(int distBack, int distAhead) {
        if (layout().schematic() == null) {
            return null;
        }
        // The scan steps the pattern's path a slice at a time, so its start and its length have to
        // be counted the same way: shifting our feet by the direction vector first would anchor the
        // box off the path, and a block count would stop the sweep short of distAhead.
        int backSlices = layout().slicesForBlocks(distBack);
        int aheadSlices = layout().slicesForBlocks(distAhead);
        Vec3 feetPos = new Vec3(playerContext.playerFeet().getX(), playerContext.playerFeet().getY(), playerContext.playerFeet().getZ());
        BlockPos startPos = layout().sliceAlongLine(layout().originVector(), feetPos, -backSlices, LocationType.HighwayBuild);
        int scanT0 = layout().pattern().isCustom() ? layout().sliceIndexOf(startPos, layout().originVector()) : 0;
        for (int i = 0; i < backSlices + aheadSlices; i++) {
            BlockPos curPos = layout().pattern().isCustom()
                    ? startPos.offset(layout().sliceDelta(scanT0, i))
                    : startPos.offset(i * layout().highwayDirection().getX(), 0, i * layout().highwayDirection().getZ());
            for (int y = 0; y < layout().schematic().heightY(); y++) {
                for (int z = 0; z < layout().schematic().lengthZ(); z++) {
                    for (int x = 0; x < layout().schematic().widthX(); x++) {
                        int blockX = x + curPos.getX();
                        int blockY = y + curPos.getY();
                        int blockZ = z + curPos.getZ();
                        if (!baritone.bsi.worldContainsLoadedChunk(blockX, blockZ)) {
                            continue;
                        }
                        if (layout().cellOverrides().kindAt(blockX, blockY, blockZ) != null) {
                            continue; // another road's storage, not our lost box
                        }
                        if (HighwayContext.shulkerBlockList.contains(playerContext.world().getBlockState(new BlockPos(blockX, blockY, blockZ)).getBlock())) {
                            return new BlockPos(blockX, blockY, blockZ);
                        }
                    }
                }
            }
        }
        return null;
    }

    public boolean maybeMineMisplacedShulker(int distBack, int distAhead) {
        BlockPos misplaced = findMisplacedShulkerBox(distBack, distAhead);
        if (misplaced == null) {
            return false;
        }
        Helper.HELPER.logDirect("The missing shulker box got placed into the highway at (" + misplaced.toShortString() + "), mining it back.");
        ctx.setPlaceLoc(misplaced);
        baritone.getPathingBehavior().cancelEverything();
        ctx.transitionTo(HighwayState.MiningMisplacedShulker);
        ctx.resetTimer();
        return true;
    }

    public boolean maybeLostShulkerRelog() {
        if (!settings.highwayLostShulkerRelog.value || lostShulkerRelogAttempted) {
            return false;
        }
        lostShulkerRelogAttempted = true;
        Component dcMsg = Component.literal("Lost a shulker box that isn't visible anywhere nearby. Reconnect to resync");
        Helper.HELPER.logDirect(dcMsg);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getPathingBehavior().cancelEverything();
        ctx.transitionTo(HighwayState.Nothing);
        playerContext.player().connection.getConnection().disconnect(dcMsg);
        return true;
    }

    public void clearLostShulkerRelogAttempt() {
        lostShulkerRelogAttempted = false;
    }

    private BlockPos shulkerPlaceSightBlocker(BlockPos placeLoc) {
        Direction placeSide = ctx.shulkerPlacement().getBestPlaceSide(placeLoc, true);
        if (placeSide == null) {
            return null;
        }
        return rayBlockerShortOf(placeLoc.relative(placeSide));
    }

    /** What the ray from our eyes to the centre of {@code target} stops on before getting there. */
    private BlockPos rayBlockerShortOf(BlockPos target) {
        Rotation aim = RotationUtils.calcRotationFromVec3d(playerContext.player().getEyePosition(1.0F),
                VecUtils.calculateBlockCenter(playerContext.world(), target), playerContext.playerRotations());
        HitResult hit = RayTraceUtils.rayTraceTowards(playerContext.player(),
                baritone.getLookBehavior().getAimProcessor().peekRotation(aim),
                playerContext.playerController().getBlockReachDistance(), false);
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitAt = ((BlockHitResult) hit).getBlockPos();
            if (!hitAt.equals(target)) {
                return hitAt;
            }
        }
        return null; // the ray ran out rather than stopping on something: too far, not walled off
    }

    public boolean handleShulkerPlaceOutOfSight(BlockPos placeLoc, HighwayState walkCloserState, HighwayState relocateState) {
        BlockPos key = new BlockPos(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ());
        if (ctx.shulkerPlacement().canReachShulkerPlacement(key)) {
            return false;
        }
        shulkerSightDigs.at(key);
        shulkerSightWalks.at(key);

        BlockPos blocker = shulkerPlaceSightBlocker(key);
        if (blocker != null) {
            // A dispatched clearArea takes a couple of ticks to read as an active builder, and until
            // it does we land back here with the wall still standing. Re-sending it into that window
            // would spend the whole dig budget in six ticks without a single block being mined.
            if (ctx.contextTick() - shulkerSightLastDigTick < SHULKER_SIGHT_DIG_SPINUP) {
                ctx.resetTimer();
                return true;
            }
            if (shulkerSightDigs.next() <= SHULKER_SIGHT_DIGS) {
                Helper.HELPER.logDirect("No line to the shulker spot at " + key.toShortString() + ", digging out the "
                        + playerContext.world().getBlockState(blocker).getBlock().getName().getString()
                        + " at " + blocker.toShortString() + ".");
                baritone.getPathingBehavior().cancelEverything();
                settings.buildRepeat.value = new Vec3i(0, 0, 0);
                baritone.getBuilderProcess().clearArea(blocker, blocker);
                shulkerSightLastDigTick = ctx.contextTick();
                ctx.resetTimer();
                return true;
            }
        } else if (shulkerSightWalks.next() <= SHULKER_SIGHT_WALKS) {
            ctx.transitionTo(walkCloserState); // nothing in the way, we just aren't on the standing spot
            ctx.resetTimer();
            return true;
        }

        ctx.shulkerPlacement().rememberBadShulkerSpot(key);
        Helper.HELPER.logDirect("Still can't get a clear line to the shulker spot at " + key.toShortString()
                + ", picking a different one.");
        shulkerSightDigs.clear();
        shulkerSightWalks.clear();
        baritone.getPathingBehavior().cancelEverything();
        ctx.transitionTo(relocateState != null ? relocateState : HighwayState.Nothing);
        ctx.resetTimer();
        return true;
    }

    /**
     * A container that's down but can't be reached from where we stand. Walking back only helps
     * when we're off the standing spot: with a block between us and the box, the walk-closer state
     * found us already there, the placing state found the box already down and handed it straight
     * back, and that went round every five ticks for as long as nobody stepped in. So dig out what
     * the ray to it stops on, walk only when nothing's in the way and we're off the spot, and give
     * the spot up once neither gets it in reach.
     *
     * @param stand         where the go-to state stands to work on the spot
     * @param retryState    the placing state, which waits a dig or walk out and hands the container back
     * @param relocateState where to pick a new spot after giving up on an ender chest, which is left
     *                      standing since mining it only drops obsidian. A shulker box is always mined
     *                      back instead, whatever this is, so it's never left for someone else to take.
     */
    public void handleContainerOutOfReach(BlockPos spot, BlockPos stand, HighwayState retryState, HighwayState relocateState) {
        BlockPos key = new BlockPos(spot.getX(), spot.getY(), spot.getZ());
        // a click the last open tick forced would land on whatever the crosshair is on instead
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        reachDigs.at(key);
        reachWalks.at(key);
        if (!baritone.getBuilderProcess().isPaused() && baritone.getBuilderProcess().isActive()) {
            ctx.transitionTo(retryState);
            ctx.resetTimer();
            return;
        }
        // A dispatched clearArea takes a couple of ticks to read as an active builder, see handleShulkerPlaceOutOfSight
        if (ctx.contextTick() - reachLastDigTick < SHULKER_SIGHT_DIG_SPINUP) {
            ctx.resetTimer();
            return;
        }

        String what = playerContext.world().getBlockState(key).getBlock().getName().getString();
        BlockPos blocker = rayBlockerShortOf(key);
        if (blocker != null) {
            // a bare clearArea collects nothing, so a box in the way would just be lost
            if (!(playerContext.world().getBlockState(blocker).getBlock() instanceof ShulkerBoxBlock)
                    && reachDigs.next() <= SHULKER_SIGHT_DIGS) {
                Helper.HELPER.logDirect(what + " at " + key.toShortString() + " can't be reached, digging out the "
                        + playerContext.world().getBlockState(blocker).getBlock().getName().getString()
                        + " at " + blocker.toShortString() + ".");
                baritone.getPathingBehavior().cancelEverything();
                settings.buildRepeat.value = new Vec3i(0, 0, 0);
                baritone.getBuilderProcess().clearArea(blocker, blocker);
                reachLastDigTick = ctx.contextTick();
                ctx.transitionTo(retryState);
                ctx.resetTimer();
                return;
            }
        } else if (!playerContext.playerFeet().equals(stand) && reachWalks.next() <= SHULKER_SIGHT_WALKS) {
            // Straight there rather than through the go-to state: the supply, pickaxe and loot chest
            // ones give up once the inventory runs out of what they place, which this just did
            Helper.HELPER.logDirect(what + " at " + key.toShortString() + " can't be reached, walking back to "
                    + stand.toShortString() + ".");
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(stand));
            reachWalking = true;
            ctx.transitionTo(retryState);
            ctx.resetTimer();
            return;
        }

        ctx.shulkerPlacement().rememberBadShulkerSpot(key);
        reachDigs.clear();
        reachWalks.clear();
        baritone.getPathingBehavior().cancelEverything();
        if (playerContext.world().getBlockState(key).getBlock() instanceof EnderChestBlock) {
            Helper.HELPER.logDirect("Still can't reach the " + what + " at " + key.toShortString()
                    + ", leaving it and placing one somewhere else.");
            ctx.resupply().forgetEnderChestAccessLoc(key);
            ctx.transitionTo(relocateState != null ? relocateState : HighwayState.Nothing);
        } else {
            Helper.HELPER.logDirect("Still can't reach the " + what + " at " + key.toShortString()
                    + ", mining it back to place it somewhere else.");
            ctx.setPlaceLoc(key);
            ctx.transitionTo(HighwayState.MiningMisplacedShulker);
        }
        ctx.resetTimer();
    }

    /** Whether the walk {@link #handleContainerOutOfReach} sent is still going. The placing state holds the container until it ends. */
    public boolean outOfReachWalkRunning() {
        if (reachWalking && baritone.getCustomGoalProcess().isActive()) {
            return true;
        }
        reachWalking = false;
        return false;
    }

    /** A placement that went through is proof the spot works; drop the line-of-sight failures there. */
    public void noteShulkerPlaced() {
        shulkerSightDigs.clear();
        shulkerSightWalks.clear();
        standClearTries.clear();
        placeRefusals.clear();
    }

    HighwayState noteShulkerPlaceRefused(BlockPos spot, HighwayState prevHighwayState) {
        BlockPos key = new BlockPos(spot.getX(), spot.getY(), spot.getZ());
        int tries = placeRefusals.at(key).next();
        if (tries <= SHULKER_PLACE_REFUSALS) {
            if (tries == 1) {
                Helper.HELPER.logDirect("Failed to place shulker at " + key.toShortString() + ", retrying");
            }
            return prevHighwayState;
        }
        ctx.shulkerPlacement().rememberBadShulkerSpot(key);
        Helper.HELPER.logDirect("Placing a shulker at " + key.toShortString()
                + " keeps being refused, picking a different spot.");
        placeRefusals.clear();
        return HighwayState.Nothing;
    }

    public boolean playerInWayOfPlacement(BlockPos placeLoc) {
        AABB cell = new AABB(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ(),
                placeLoc.getX() + 1.0, placeLoc.getY() + 1.0, placeLoc.getZ() + 1.0);
        return playerContext.player().getBoundingBox().intersects(cell);
    }

    private BlockPos clearStandingSpotFor(BlockPos placeLoc) {
        BetterBlockPos feet = playerContext.playerFeet();
        // Step back on whichever side of the spot we already stand: the flows differ on that, and
        // crossing over the box to reach the far side is a walk we don't need to make
        int side = Integer.signum(layout().pattern().isCustom()
                ? layout().stepsAlongHighway(placeLoc, feet)
                : (feet.x - placeLoc.getX()) * layout().highwayDirection().getX()
                + (feet.z - placeLoc.getZ()) * layout().highwayDirection().getZ());
        if (side == 0) {
            side = 1;
        }
        for (int step : new int[]{2 * side, 3 * side, -2 * side, -3 * side}) {
            BlockPos candidate = layout().standOffAlongLine(placeLoc, ctx.placeLocLine(), step);
            if (candidate.getX() == feet.x && candidate.getZ() == feet.z) {
                continue; // where we already are, so pathing there would move us nowhere
            }
            if (ctx.shulkerPlacement().hasFloorUnder(candidate)
                    && ctx.recovery().passableForRecovery(candidate.getX(), candidate.getY(), candidate.getZ())
                    && ctx.recovery().passableForRecovery(candidate.getX(), candidate.getY() + 1, candidate.getZ())) {
                return candidate;
            }
        }
        return null;
    }

    public boolean handleStandingInPlaceSpot(BlockPos placeLoc, HighwayState relocateState) {
        BlockPos key = new BlockPos(placeLoc.getX(), placeLoc.getY(), placeLoc.getZ());
        // Let a step-off walk we sent finish rather than placing the moment the hitbox leaves the
        // cell: that put the box down with the walk still running, and that box never opened.
        if (standClearWalking) {
            if (baritone.getCustomGoalProcess().isActive()) {
                ctx.resetTimer();
                return true;
            }
            standClearWalking = false;
        }
        if (!playerInWayOfPlacement(key)) {
            standClearTries.clear();
            return false;
        }
        if (baritone.getCustomGoalProcess().isActive()) {
            ctx.resetTimer();
            return true; // already walking off it
        }
        standClearTries.at(key);

        BlockPos clear = clearStandingSpotFor(key);
        if (clear != null && standClearTries.next() <= SHULKER_STAND_CLEAR_TRIES) {
            Helper.HELPER.logDirect("Standing in the shulker spot at " + key.toShortString()
                    + ", stepping back to " + clear.toShortString() + ".");
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BetterBlockPos(clear)));
            standClearWalking = true;
            ctx.resetTimer();
            return true;
        }

        // Nowhere to step to, or the walk never took: the spot itself is the thing to give up on
        ctx.shulkerPlacement().rememberBadShulkerSpot(key);
        Helper.HELPER.logDirect("Can't get out of the shulker spot at " + key.toShortString() + ", picking a different one.");
        standClearTries.clear();
        baritone.getPathingBehavior().cancelEverything();
        ctx.transitionTo(relocateState != null ? relocateState : HighwayState.Nothing);
        ctx.resetTimer();
        return true;
    }

    /** A box or loot chest that opened is proof its spot is fine; don't carry earlier failures there forward. */
    public void noteContainerOpened() {
        shulkerOpenFails.clear();
        reachDigs.clear();
        reachWalks.clear();
        refusedOpenSpot = null;
        sneakTapSpot = null;
        sneakTapTicks = 0;
        refusedOpenRelogAttempted = false;
    }

    /**
     * An open that timed out with the container still standing. Logs what the client knew. When
     * the click did go out at a container in reach with room to open, the server ignored it, and
     * nothing on the client side explains that: re-placing can't fix it, but a sneak tap has (the
     * input packets reset whatever the server held for the player), and a rejoin resets the rest.
     * So the first such spot gets one tap, and a second one relogs.
     *
     * @return true when this disconnected and the caller has nothing left to do
     */
    public boolean handleOpenRefused(BlockPos spot) {
        BlockPos key = new BlockPos(spot.getX(), spot.getY(), spot.getZ());
        String what = playerContext.world().getBlockState(key).getBlock().getName().getString();
        Helper.HELPER.logDirect(what + " at " + key.toShortString() + " didn't open: " + openDiagnostics(key));
        boolean clicked = key.equals(ctx.sync().lastUseItemOnPos())
                && ctx.contextTick() - ctx.sync().lastUseItemOnTick() <= settings.highwayPlaceConfirmTimeout.value;
        if (!clicked || !containerHasRoomToOpen(key)) {
            return false; // the click never went out, or the lid is blocked: the callers' own retries cover those
        }

        if (refusedOpenSpot != null && !refusedOpenSpot.equals(key)) {
            if (!settings.highwayRelogOnRefusedOpen.value || refusedOpenRelogAttempted) {
                return false;
            }
            refusedOpenRelogAttempted = true;
            Component dcMsg = Component.literal("The server won't open containers in reach at two spots. Reconnect to reset");
            Helper.HELPER.logDirect(dcMsg);
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getPathingBehavior().cancelEverything();
            ctx.transitionTo(HighwayState.Nothing);
            playerContext.player().connection.getConnection().disconnect(dcMsg);
            return true;
        }
        refusedOpenSpot = key;
        if (!key.equals(sneakTapSpot)) {
            Helper.HELPER.logDirect("Tapping sneak once before the next try.");
            sneakTapSpot = key;
            sneakTapTicks = SNEAK_TAP_TICKS;
        }
        return false;
    }

    /**
     * Runs the sneak tap {@link #handleOpenRefused} scheduled for {@code spot}. While it returns
     * true the caller must not click: a click landing while the server thinks we're sneaking puts
     * the held item to use instead of opening the container.
     */
    public boolean runSneakTap(BlockPos spot) {
        if (sneakTapTicks <= 0) {
            return false;
        }
        if (!spot.equals(sneakTapSpot)) {
            sneakTapTicks = 0; // scheduled for a container that's gone since
            return false;
        }
        // The input override ticks ahead of us, so a key forced here reaches the server a tick
        // late: down for two ticks, then up for two so the release is out before the next click
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, sneakTapTicks > SNEAK_TAP_TICKS / 2);
        sneakTapTicks--;
        return true;
    }

    private boolean containerHasRoomToOpen(BlockPos spot) {
        BlockState state = playerContext.world().getBlockState(spot);
        if (state.getBlock() instanceof ShulkerBoxBlock) {
            return ctx.shulkerPlacement().canShulkerOpenToward(spot, state.getValue(ShulkerBoxBlock.FACING));
        }
        BlockPos above = spot.above();
        return !playerContext.world().getBlockState(above).isRedstoneConductor(playerContext.world(), above);
    }

    /** What the client knew when an open timed out, for telling a click that never went out from one the server ignored. */
    private String openDiagnostics(BlockPos spot) {
        LocalPlayer player = playerContext.player();
        String click = ctx.sync().lastUseItemOnPos() == null ? "no click sent"
                : (spot.equals(ctx.sync().lastUseItemOnPos()) ? "last click on it " : "last click at " + ctx.sync().lastUseItemOnPos().toShortString() + " ")
                + (ctx.contextTick() - ctx.sync().lastUseItemOnTick()) + "t ago";
        return click
                + ", aim " + (playerContext.isLookingAt(spot) ? "on it" : "off it")
                + ", sneak client=" + player.isShiftKeyDown() + " sent=" + player.getLastSentInput().shift()
                + ", main=" + stackName(player.getMainHandItem()) + " off=" + stackName(player.getOffhandItem())
                + ", pose=" + player.getPose() + (player.isFallFlying() ? " gliding" : "") + (player.onGround() ? "" : " airborne")
                + ", screen=" + (playerContext.minecraft().screen == null ? "none" : playerContext.minecraft().screen.getClass().getSimpleName());
    }

    private static String stackName(ItemStack stack) {
        return stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + "x" + stack.getCount();
    }

    public boolean handleShulkerWouldNotOpen(BlockPos spot) {
        BlockPos key = new BlockPos(spot.getX(), spot.getY(), spot.getZ());
        int tries = shulkerOpenFails.at(key).next();

        BlockState state = playerContext.world().getBlockState(key);
        if (!(state.getBlock() instanceof ShulkerBoxBlock)) {
            return false; // the box isn't there at all, which is the caller's re-place case
        }
        Direction facing = state.getValue(ShulkerBoxBlock.FACING);
        boolean lidHasRoom = ctx.shulkerPlacement().canShulkerOpenToward(key, facing);

        if (lidHasRoom && tries <= SHULKER_OPEN_RETRIES) {
            return false; // nothing in the way, so something transient ate the open - retry as before
        }

        // Something is in the lid's way. Digging it out beats a new spot
        if (!lidHasRoom && facing != Direction.DOWN
                && tries <= SHULKER_OPEN_RETRIES + SHULKER_OPEN_UNBLOCK_TRIES) {
            BlockPos blocked = key.relative(facing);
            Helper.HELPER.logDirect("Shulker at " + key.toShortString() + " is facing " + facing + " into "
                    + playerContext.world().getBlockState(blocked).getBlock().getName().getString()
                    + ", digging that out so it can open.");
            baritone.getPathingBehavior().cancelEverything();
            settings.buildRepeat.value = new Vec3i(0, 0, 0);
            baritone.getBuilderProcess().clearArea(blocked, blocked);
            return false; // the placing state waits the builder out, then hands the box back to us
        }

        ctx.shulkerPlacement().rememberBadShulkerSpot(key);
        Helper.HELPER.logDirect("Shulker at " + key.toShortString() + " still won't open after "
                + tries + " tries, mining it back to place it somewhere else.");
        shulkerOpenFails.clear();
        baritone.getPathingBehavior().cancelEverything();
        ctx.setPlaceLoc(key);
        ctx.transitionTo(HighwayState.MiningMisplacedShulker);
        ctx.resetTimer();
        return true;
    }

    /**
     * A loot ender chest in reach that still won't open. The placing state hands a standing chest
     * straight back, so without a count this re-clicked it every timeout forever. Past the plain
     * retries the chest is left standing, since mining it only drops obsidian.
     */
    public boolean handleLootEnderChestWouldNotOpen(BlockPos spot) {
        BlockPos key = new BlockPos(spot.getX(), spot.getY(), spot.getZ());
        int tries = shulkerOpenFails.at(key).next();
        if (tries <= SHULKER_OPEN_RETRIES) {
            return false;
        }

        ctx.shulkerPlacement().rememberBadShulkerSpot(key);
        Helper.HELPER.logDirect("Ender chest at " + key.toShortString() + " still won't open after "
                + tries + " tries, placing one somewhere else.");
        shulkerOpenFails.clear();
        ctx.resupply().forgetEnderChestAccessLoc(key);
        baritone.getPathingBehavior().cancelEverything();
        ctx.transitionTo(HighwayState.LootEnderChestPlaceLocPrep);
        ctx.resetTimer();
        return true;
    }
}
