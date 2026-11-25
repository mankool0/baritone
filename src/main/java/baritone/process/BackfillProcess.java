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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.IMovement;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.BrokenBlocks;
import baritone.utils.PrinterClicks;
import baritone.utils.ToolSet;
import baritone.utils.accessor.IPlayerControllerMP;
import java.util.*;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fills in what the path digs out, to leave no trace: the block that was there, facing the same
 * way, once every drop in the hole is picked up. See {@link baritone.api.Settings#backfill}.
 */
public final class BackfillProcess extends BaritoneProcessHelper {

    /**
     * How long to stand still for a drop in pickup reach. Block drops can't be picked up for their
     * first 10 ticks, so this only runs out when something else stops the pickup.
     */
    private static final int DROP_WAIT_TICKS = 40;
    /** How long to walk back for a drop left behind before giving up on it */
    private static final int COLLECT_TICKS = 600;
    /** How far back to walk for one */
    private static final double COLLECT_RANGE = 32;
    /**
     * Xp orbs this close fly to us by themselves
     */
    private static final double ORB_FOLLOW_RANGE = 8;
    /** How many movements past the current one to dig out ahead, so their drops lie where we walk */
    private static final int DIG_AHEAD_MOVEMENTS = 6;
    /** With no path for this long after walking back, nothing comes back for the cells kept open */
    private static final int HELD_OPEN_TICKS = 40;
    /** With no path for this long after walking back, nothing else is taking us on to where we were going */
    private static final int RETURN_AFTER_TICKS = 5;
    /** How long to stand waiting for room in the inventory before digging on anyway */
    private static final int ROOM_WAIT_TICKS = 100;
    /** No-rotation places into one hole that the server keeps undoing before it's looked at instead */
    private static final int NO_ROTATE_TRIES = 3;
    /**
     * How long a hole has to stay filled before it's forgotten: a place the server turns down comes
     * back as air a round trip later
     */
    private static final int FILL_CONFIRM_TICKS = 40;
    /**
     * How long after it's dug a hole waits before it's filled. Its drop shows up a round trip
     * after the break, plus however far the server lags, and a hole filled before it does pushes
     * the drop out into the rock all the same.
     */
    private static final int HOLE_SETTLE_TICKS = 20;
    /** A drop showing up this close to a block we broke this recently came out of it */
    private static final double DROP_ORIGIN_RANGE = 2.5;
    private static final int DROP_ORIGIN_TICKS = 60;

    /**
     * What a filled block has to share with the broken one to count as facing the same way. The
     * rest, like a fence's connections, the world works out from the blocks around it.
     */
    private static final List<Property<?>> ORIENTATION = List.of(
            BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS, BlockStateProperties.FACING,
            BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.VERTICAL_DIRECTION, BlockStateProperties.ORIENTATION,
            BlockStateProperties.ATTACH_FACE, BlockStateProperties.ROTATION_16, BlockStateProperties.HALF,
            BlockStateProperties.SLAB_TYPE, BlockStateProperties.DOOR_HINGE
    );

    /** The holes the path dug that are still open, and the block that was there */
    public HashMap<BlockPos, BlockState> blocksToReplace = new HashMap<>();
    /** The block first broken at each cell: a hole we filled and the path dug again gets that back */
    private final HashMap<BlockPos, BlockState> firstBroken = new HashMap<>();
    private final Map<BlockPos, Integer> noRotatePlaces = new HashMap<>();
    /** Hole → the tick it stopped being air */
    private final Map<BlockPos, Integer> filledSince = new HashMap<>();
    /** Hole → the tick it was dug */
    private final Map<BlockPos, Integer> brokenAt = new HashMap<>();

    private record Break(BlockPos pos, int tick) {}

    /** Our last few breaks, oldest first, to tell our drops from anyone's */
    private final ArrayDeque<Break> recentBreaks = new ArrayDeque<>();
    /** Drop entity id → whether it came out of a block we broke */
    private final Map<Integer, Boolean> dropFromUs = new HashMap<>();

    private long brokenSeen;
    private int ticks;

    /** Dropped items and xp orbs around us this tick */
    private List<Entity> drops = List.of();
    /** Drops in our holes that we're standing still to pick up */
    private List<Entity> waitFor = List.of();
    /** Drop entity id → the tick it came into pickup reach */
    private final Map<Integer, Integer> waitingSince = new HashMap<>();
    private final Set<Integer> cantCollect = new HashSet<>();
    /** A drop left behind in one of our holes, to walk back for */
    private Entity behind;
    private Entity collecting;
    private Goal collectGoal;
    private int collectSince;

    /** Cells the path still needs open */
    private Set<BlockPos> keepOpen = Set.of();
    /** The cells of the path we walked back from, kept open until a path takes us on */
    private Set<BlockPos> heldOpen = Set.of();
    private int noPathSince = -1;
    /** Where the path we walked back from was going */
    private Goal tripGoal;
    /** Whether to head on to tripGoal ourselves after walking back, if nothing else does */
    private boolean returning;
    private boolean returnStarted;
    private int returnSince;

    /** This tick's holes in reach that we can fill, farthest first */
    private List<BlockPos> fills = List.of();
    /** This tick's blocks to dig out ahead of the path, nearest first */
    private List<BlockPos> digTargets = List.of();
    /** Holes in reach the no-rotation pass found no click for, to look at instead */
    private final List<BlockPos> noClick = new ArrayList<>();
    /** Open holes in reach this tick and why each is still open, to report the ones we walk off from */
    private Map<BlockPos, String> openInReach = Map.of();
    private final Set<BlockPos> reportedOpen = new HashSet<>();
    /** Whether a hole in reach waits out HOLE_SETTLE_TICKS, which we stand still for */
    private boolean youngInReach;
    private boolean staging;
    private boolean clickable;
    private int placeCooldown;
    private int breakCooldown;
    private int roomWaitSince = -1;

    public BackfillProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }
        BrokenBlocks broken = baritone.getInputOverrideHandler().getBlockBreakHelper().broken();
        if (!Baritone.settings().backfill.value) {
            brokenSeen = broken.count();
            reset();
            return false;
        }
        if (Baritone.settings().allowParkour.value) {
            logDirect("Backfill cannot be used with allowParkour true");
            Baritone.settings().backfill.value = false;
            return false;
        }
        ticks++;
        if (placeCooldown > 0) {
            placeCooldown--;
        }
        if (breakCooldown > 0) {
            breakCooldown--;
        }
        PathingBehavior pathing = baritone.getPathingBehavior();
        PathExecutor current = pathing.getCurrent();
        // what the path dug out, as it was before. The blocks actually broken, rather than the
        // crosshair's, which also rests on blocks the path walks past
        boolean onPath = current != null;
        brokenSeen = broken.forEachSince(brokenSeen, (pos, was) -> {
            if (onPath) {
                record(pos, was);
            }
        });
        blocksToReplace.keySet().removeIf(this::filledOrGone);
        if (ticks % 100 == 0) {
            firstBroken.keySet().removeIf(this::unloaded);
            noRotatePlaces.keySet().retainAll(blocksToReplace.keySet());
            brokenAt.keySet().retainAll(blocksToReplace.keySet());
            reportedOpen.retainAll(blocksToReplace.keySet());
        }
        while (!recentBreaks.isEmpty() && ticks - recentBreaks.peekFirst().tick() > DROP_ORIGIN_TICKS) {
            recentBreaks.removeFirst();
        }
        updateKeepOpen(pathing, current);

        drops = ctx.world().getEntitiesOfClass(Entity.class, ctx.player().getBoundingBox().inflate(COLLECT_RANGE),
                e -> e instanceof ItemEntity || e instanceof ExperienceOrb);
        Set<Integer> around = new HashSet<>();
        for (Entity drop : drops) {
            around.add(drop.getId());
        }
        waitingSince.keySet().retainAll(around);
        cantCollect.retainAll(around);
        dropFromUs.keySet().retainAll(around);
        for (Entity drop : drops) {
            dropFromUs.computeIfAbsent(drop.getId(), id -> cameFromOurBreak(drop));
        }
        if (collecting != null && !around.contains(collecting.getId())) {
            // picked up
            stopCollecting();
            startReturning();
        }
        if (returning && (tripGoal == null || tripGoal.isInGoal(ctx.playerFeet()) || ticks - returnSince > COLLECT_TICKS)) {
            stopReturning();
        }
        waitFor = dropsToWaitFor();
        behind = collecting == null ? dropToCollect() : null;
        Map<BlockPos, String> wasOpenInReach = openInReach;
        fills = holesToFill();
        reportLeftOpen(wasOpenInReach);
        digTargets = digAhead();

        baritone.getInputOverrideHandler().clearAllKeys();

        return collecting != null || behind != null || returning || !waitFor.isEmpty() || !fills.isEmpty() || !digTargets.isEmpty();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (collecting != null) {
            PathingCommand walk = keepCollecting(calcFailed);
            if (walk != null) {
                return walk;
            }
        }
        if (returnStarted && calcFailed) {
            stopReturning();
        }
        boolean noRotate = Baritone.settings().printerNoRotate.value;
        noClick.clear();
        clickable = false;
        if (noRotate) {
            // like the printer: through walls and without turning, so it goes on while we walk
            boolean broke = digAheadWithoutTurning();
            boolean placed = fillWithoutTurning();
            if (broke || placed) {
                // the movement's own click would come after our use
                baritone.getInputOverrideHandler().suppressClicksThisTick();
            }
        }
        if (!isSafeToCancel) {
            if (needsStop(noRotate)) {
                return pause(); // once this movement is done
            }
            return onward();
        }
        if (behind != null) {
            startCollecting(behind);
            return walkTo(collectGoal);
        }
        PathingCommand look = noRotate ? null : digAheadLooking();
        if (look == null) {
            look = fillLooking(noRotate ? noClick : fills);
        }
        if (look != null) {
            return look;
        }
        if (clickable || youngInReach || !waitFor.isEmpty() || waitForRoom()) {
            // holes we fill from here, now or once their drops have had time to show up, drops
            // about to be picked up, or room about to be made
            return pause();
        }
        return onward();
    }

    /**
     * Lets the path go on, or after walking back for a drop with nothing else taking us on, heads
     * back to where it was going ourselves
     */
    private PathingCommand onward() {
        if (returning && !returnStarted && noPathSince >= 0 && ticks - noPathSince > RETURN_AFTER_TICKS) {
            returnStarted = true;
            logDebug("Backfill heading on to where the path was going");
        }
        return returnStarted ? walkTo(tripGoal) : defer();
    }

    private void startReturning() {
        returning = tripGoal != null;
        returnStarted = false;
        returnSince = ticks;
    }

    private void stopReturning() {
        returning = false;
        returnStarted = false;
    }

    private boolean needsStop(boolean noRotate) {
        if (behind != null || !waitFor.isEmpty() || !fills.isEmpty() || youngInReach) {
            return true;
        }
        return !noRotate && !digTargets.isEmpty() || waitForRoom();
    }

    private PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand defer() {
        return new PathingCommand(null, PathingCommandType.DEFER); // cede to other process
    }

    private PathingCommand walkTo(Goal goal) {
        return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
    }

    private void record(BlockPos at, BlockState was) {
        BlockPos pos = plain(at);
        blocksToReplace.put(pos, firstBroken.computeIfAbsent(pos, p -> was));
        brokenAt.put(pos, ticks);
        recentBreaks.addLast(new Break(pos, ticks));
        reportedOpen.remove(pos);
    }

    private boolean unloaded(BlockPos pos) {
        return ctx.world().getChunk(pos) instanceof EmptyLevelChunk;
    }

    private boolean open(BlockPos pos) {
        return ctx.world().getBlockState(pos).getBlock() == Blocks.AIR;
    }

    private boolean filledOrGone(BlockPos pos) {
        if (unloaded(pos)) {
            filledSince.remove(pos);
            return true;
        }
        if (open(pos)) {
            filledSince.remove(pos);
            return false;
        }
        if (ticks - filledSince.computeIfAbsent(pos, p -> ticks) > FILL_CONFIRM_TICKS) {
            filledSince.remove(pos);
            return true;
        }
        return false;
    }

    /**
     * The cells the path still has to go through stay open: filling one makes the path dig it
     * straight back out, after walking back for a drop too.
     */
    private void updateKeepOpen(PathingBehavior pathing, PathExecutor current) {
        boolean ours = current != null && collectGoal != null && collectGoal.equals(current.getPath().getGoal());
        if (current != null && !ours) {
            keepOpen = pathCells(current);
            PathExecutor next = pathing.getNext();
            if (next != null) {
                // already planned on from where this one ends
                keepOpen.addAll(pathCells(next));
            }
            heldOpen = Set.of();
            noPathSince = -1;
            tripGoal = current.getPath().getGoal();
            if (!returnStarted) {
                returning = false; // something else is taking us on
            }
            return;
        }
        if (current == null && collecting == null && pathing.getInProgress().isEmpty()) {
            if (noPathSince < 0) {
                noPathSince = ticks;
            } else if (ticks - noPathSince > HELD_OPEN_TICKS && !returning) {
                heldOpen = Set.of();
            }
        } else {
            noPathSince = -1;
        }
        keepOpen = heldOpen;
    }

    private static Set<BlockPos> pathCells(PathExecutor exec) {
        Set<BlockPos> cells = new HashSet<>();
        List<IMovement> movements = exec.getPath().movements();
        for (int i = Math.max(0, exec.getPosition()); i < movements.size(); i++) {
            Movement movement = (Movement) movements.get(i);
            for (BlockPos pos : movement.toBreakAll()) {
                cells.add(plain(pos));
            }
            for (BetterBlockPos pos : List.of(movement.getSrc(), movement.getDest())) {
                cells.add(plain(pos));
                cells.add(plain(pos.above()));
            }
        }
        return cells;
    }

    /**
     * Our holes in reach that nothing stops us filling now, farthest first so the nearer ones
     * don't wall them off
     */
    private List<BlockPos> holesToFill() {
        openInReach = new HashMap<>();
        youngInReach = false;
        // the way to a drop stays open until it's picked up, one in reach too: walk off before
        // it's taken and the holes filled meanwhile have walled it in
        boolean dropToGet = collecting != null || behind != null || waitFor.stream().anyMatch(drop -> !onPathAhead(drop));
        Vec3 eye = ctx.playerHead();
        double range = ctx.playerController().getBlockReachDistance() + 1;
        List<BlockPos> holes = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> hole : blocksToReplace.entrySet()) {
            BlockPos pos = hole.getKey();
            if (eye.distanceToSqr(VecUtils.getBlockPosCenter(pos)) > range * range || !open(pos)) {
                continue;
            }
            String why = whyOpen(pos, hole.getValue(), dropToGet);
            // until it's placed, the passes below are what keep it open
            openInReach.put(pos, why != null ? why : "no face to place it against from here");
            if (why == null) {
                holes.add(pos);
            }
        }
        holes.sort(Comparator.<BlockPos>comparingDouble(pos -> eye.distanceToSqr(VecUtils.getBlockPosCenter(pos))).reversed());
        return holes;
    }

    /**
     * @return why a hole in reach can't be filled now, or null if it can
     */
    private String whyOpen(BlockPos pos, BlockState was, boolean dropToGet) {
        if (keepOpen.contains(pos)) {
            return "the path goes through it";
        }
        if (dropToGet) {
            return "a drop is still to be picked up";
        }
        Integer dug = brokenAt.get(pos);
        if (dug != null && ticks - dug < HOLE_SETTLE_TICKS) {
            youngInReach = true;
            return "just dug, its drop may not have shown up yet";
        }
        // vanilla pushes an item or xp orb out of a block placed on it, and with no open side
        // next to it that's straight up through the rock, onto the nether roof
        if (holdsDrop(pos)) {
            return "a drop is in it";
        }
        if (waitsForSupport(pos, was)) {
            return "it goes back as gravel once the hole below is filled";
        }
        if (!haveSomethingFor(pos, was)) {
            return "nothing to put back in it";
        }
        if (!baritone.getBuilderProcess().placementPlausible(pos, Blocks.DIRT.defaultBlockState())) {
            return "something is standing in it";
        }
        return null;
    }

    /**
     * Reports the holes that were open in reach last tick and aren't in reach any more, still open
     */
    private void reportLeftOpen(Map<BlockPos, String> wasOpenInReach) {
        for (Map.Entry<BlockPos, String> hole : wasOpenInReach.entrySet()) {
            BlockPos pos = hole.getKey();
            if (!openInReach.containsKey(pos) && blocksToReplace.containsKey(pos) && open(pos) && reportedOpen.add(pos)) {
                logDiagnostic("Backfill left the hole at " + pos.toShortString() + " open: " + hole.getValue());
            }
        }
    }

    /**
     * Whether a block of ours is in {@code pos} with nothing in it, so backfill puts it back
     */
    public boolean isOpenHole(BlockPos pos) {
        return blocksToReplace.containsKey(plain(pos)) && open(pos);
    }

    public List<BlockPos> toFillIn() {
        return fills;
    }

    // ---- digging ahead ----

    /**
     * The blocks the next few movements dig through, in reach. Dug out before we get there, their
     * drops lie where we walk, after their pickup delay is over. Dug as we step in, they'd still
     * be lying behind us when it was.
     */
    private List<BlockPos> digAhead() {
        PathExecutor exec = baritone.getPathingBehavior().getCurrent();
        if (exec == null || collecting != null || collectGoal != null && collectGoal.equals(exec.getPath().getGoal())) {
            return List.of();
        }
        List<IMovement> movements = exec.getPath().movements();
        List<BetterBlockPos> positions = exec.getPath().positions();
        int at = Math.max(0, exec.getPosition());
        int to = Math.min(movements.size(), at + 1 + DIG_AHEAD_MOVEMENTS);
        // never the floor we're about to stand on, a downward movement digs that itself
        Set<BlockPos> floors = new HashSet<>();
        for (int i = at; i <= to && i < positions.size(); i++) {
            floors.add(plain(positions.get(i).below()));
        }
        BetterBlockPos feet = ctx.playerFeet();
        Vec3 eye = ctx.playerHead();
        double range = ctx.playerController().getBlockReachDistance() + 1;
        BlockStateInterface bsi = bsi();
        ToolSet tools = null;
        List<BlockPos> targets = new ArrayList<>();
        // the current movement digs its own. What a movement breaks, rather than all it needs
        // clear: a diagonal only walks past its sides
        for (int i = at + 1; i < to; i++) {
            for (BlockPos raw : ((Movement) movements.get(i)).toBreak(bsi)) {
                BlockPos pos = plain(raw);
                if (floors.contains(pos) || targets.contains(pos)
                        || eye.distanceToSqr(VecUtils.getBlockPosCenter(pos)) > range * range) {
                    continue;
                }
                if (Math.abs(pos.getX() - feet.x) <= 1 && Math.abs(pos.getZ() - feet.z) <= 1
                        && pos.getY() < feet.y && pos.getY() >= feet.y - 3) {
                    continue; // what we stand on
                }
                BlockState state = ctx.world().getBlockState(pos);
                if (state.isAir() || !state.getFluidState().isEmpty() || MovementHelper.canWalkThrough(ctx, BetterBlockPos.from(pos))) {
                    continue;
                }
                if (tools == null) {
                    tools = new ToolSet(ctx.player());
                }
                // lava may have come up next to it since the path was planned
                if (MovementHelper.avoidBreaking(bsi, pos.getX(), pos.getY(), pos.getZ(), state)
                        || tools.getBestSlot(state.getBlock(), false) < 0) {
                    continue;
                }
                targets.add(pos);
            }
        }
        targets.sort(Comparator.comparingDouble(pos -> eye.distanceToSqr(VecUtils.getBlockPosCenter(pos))));
        return targets;
    }

    /**
     * An instant break ahead, like the printer's: no rotation, so the walk goes on
     *
     * @return whether something was broken
     */
    private boolean digAheadWithoutTurning() {
        // a dig here would move the server's dig target off the movement's own one under way
        if (!Baritone.settings().printerBreak.value || breakCooldown > 0 || !ctx.player().onGround()
                || baritone.getInputOverrideHandler().getBlockBreakHelper().digging()) {
            return false;
        }
        double reach = ctx.playerController().getBlockReachDistance();
        Vec3 eye = ctx.player().getEyePosition(1.0f);
        Vec3 predictedEye = eye.add(ctx.player().getDeltaMovement());
        Inventory inventory = ctx.player().getInventory();
        for (BlockPos pos : digTargets) {
            BlockState state = ctx.world().getBlockState(pos);
            if (!PrinterClicks.breakReaches(pos, eye, predictedEye, reach * reach) || !roomForDropOf(state)
                    || baritone.getServerDigSpeed().mustWait(state, pos)) {
                continue;
            }
            int packets = PrinterClicks.breakPackets(PrinterClicks.breakProgress(baritone, state, pos));
            if (packets == 0) {
                continue; // the movement digs it when it gets there
            }
            int previous = inventory.getSelectedSlot();
            MovementHelper.switchToBestToolFor(ctx, state);
            int slot = inventory.getSelectedSlot();
            // its dig packets, the swing and a switch to the tool
            int sending = packets + 1 + (slot != carriedSlot() ? 1 : 0);
            if (PrinterClicks.slotUnsettled(baritone, slot) || !PrinterClicks.packetsFit(baritone, sending, 0)) {
                inventory.setSelectedSlot(previous);
                return false;
            }
            ctx.playerController().syncHeldItem();
            Direction face = Direction.getApproximateNearest(eye.subtract(VecUtils.getBlockPosCenter(pos)));
            boolean dug = packets == 1
                    ? ctx.playerController().clickBlockInstant(pos, face)
                    : ctx.playerController().clickBlockFinishing(pos, face);
            if (!dug) {
                return false;
            }
            ctx.player().swing(InteractionHand.MAIN_HAND);
            ctx.playerController().setDestroyDelay(0);
            // predicted gone like the left-click path's breaks, for the stuck checks
            baritone.getInputOverrideHandler().getBlockBreakHelper().broken().add(pos, state);
            breakCooldown = Math.max(1, Baritone.settings().printerBreakDelay.value);
            return true;
        }
        return false;
    }

    /**
     * Stands and digs out the nearest block ahead it can see
     */
    private PathingCommand digAheadLooking() {
        for (BlockPos pos : digTargets) {
            BlockState state = ctx.world().getBlockState(pos);
            if (!roomForDropOf(state)) {
                continue;
            }
            Optional<Rotation> rotation = RotationUtils.reachable(ctx, pos);
            if (rotation.isEmpty()) {
                continue;
            }
            baritone.getLookBehavior().updateTarget(rotation.get(), true);
            if (ctx.isLookingAt(pos)) {
                MovementHelper.switchToBestToolFor(ctx, state);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
            return pause();
        }
        return null;
    }

    // ---- filling ----

    /** Something to put in a hole: a block, and whether it has to face the way the broken one did */
    private record Want(Block block, boolean sameFacing) {}

    private record Click(int slot, BlockHitResult hit) {}

    private record Plan(int slot, Want want, Rotation rotation, BlockPos against, Direction face) {}

    /**
     * What to fill {@code pos} with, best first: the block broken there facing the same way, then
     * facing any way, then the most common blocks around it, then throwaway blocks
     */
    private List<Want> wants(BlockPos pos, BlockState was) {
        List<Want> wants = new ArrayList<>();
        boolean full = isFullBlock(pos, was);
        if (canFillWith(pos, was)) {
            wants.add(new Want(was.getBlock(), true));
            if (full && ORIENTATION.stream().anyMatch(was::hasProperty)) {
                wants.add(new Want(was.getBlock(), false));
            }
        }
        if (!full) {
            // a torch, fence or slab gets itself back or nothing, see haveSomethingFor
            return wants;
        }
        // ores and glowstone drop something else, so match the rock around them instead
        Map<Block, Integer> around = new LinkedHashMap<>();
        for (Direction dir : Direction.values()) {
            BlockPos at = pos.relative(dir);
            BlockState state = ctx.world().getBlockState(at);
            if (state.getBlock() != was.getBlock() && isFullBlock(at, state) && canFillWith(pos, state)) {
                around.merge(state.getBlock(), 1, Integer::sum);
            }
        }
        around.entrySet().stream()
                .sorted(Map.Entry.<Block, Integer>comparingByValue().reversed())
                .forEach(e -> wants.add(new Want(e.getKey(), false)));
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (item instanceof BlockItem blockItem && wants.stream().noneMatch(w -> w.block() == blockItem.getBlock())) {
                wants.add(new Want(blockItem.getBlock(), false));
            }
        }
        return wants;
    }

    private static boolean matches(BlockState placed, Want want, BlockState was) {
        if (placed == null || placed.getBlock() != want.block()) {
            return false;
        }
        if (want.sameFacing()) {
            for (Property<?> prop : ORIENTATION) {
                if (was.hasProperty(prop) && !Objects.equals(placed.getValues().get(prop), was.getValues().get(prop))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Goes through what we'd put in {@code pos}, best first, for the first that {@code clickWith}
     * finds a click for with the hotbar slot holding it. Something better that's only in the
     * inventory gets swapped onto the hotbar instead, and nothing is placed meanwhile.
     */
    private <T> T firstClick(BlockPos pos, BlockState was, BiFunction<Want, Integer, T> clickWith) {
        staging = false;
        for (Want want : wants(pos, was)) {
            Item item = want.block().asItem();
            if (item == Items.AIR) {
                continue;
            }
            int slot = hotbarSlot(item);
            if (slot < 0) {
                if (inInventory(item) && baritone.getInventoryBehavior().throwaway(true, stack -> stack.is(item))) {
                    staging = true;
                    return null;
                }
                continue;
            }
            T click = clickWith.apply(want, slot);
            if (click != null) {
                return click;
            }
        }
        return null;
    }

    /**
     * Places into the farthest hole that takes a no-rotation click from here, like the printer:
     * against a block next to it, through walls, or into the hole itself with printerAirPlace
     *
     * @return whether something was placed
     */
    private boolean fillWithoutTurning() {
        double reach = ctx.playerController().getBlockReachDistance();
        double reachSq = reach * reach;
        Vec3 feet = ctx.player().position();
        Vec3 predictedFeet = feet.add(ctx.player().getDeltaMovement());
        // only for what directional blocks would face
        Rotation rot = baritone.getLookBehavior().getServerRotation().orElse(ctx.playerRotations());
        boolean placed = false;
        for (BlockPos pos : fills) {
            if (!open(pos)) {
                continue; // placed earlier this tick
            }
            if (noRotatePlaces.getOrDefault(pos, 0) >= NO_ROTATE_TRIES) {
                noClick.add(pos); // the server keeps undoing these
                continue;
            }
            BlockState was = blocksToReplace.get(pos);
            Click click = firstClick(pos, was, (want, slot) -> clickWithoutTurning(pos, was, want, slot, feet, predictedFeet, reachSq, rot));
            if (click == null) {
                if (!staging) {
                    noClick.add(pos);
                }
                continue;
            }
            clickable = true;
            if (placed || placeCooldown > 0) {
                continue;
            }
            if (place(click.slot(), click.hit())) {
                noRotatePlaces.merge(pos, 1, Integer::sum);
                placeCooldown = Math.max(1, Baritone.settings().printerPlaceDelay.value);
                placed = true;
            }
        }
        return placed;
    }

    private Click clickWithoutTurning(BlockPos pos, BlockState was, Want want, int slot, Vec3 feet, Vec3 predictedFeet, double reachSq, Rotation rot) {
        ItemStack stack = ctx.player().getInventory().getItem(slot);
        for (Direction d : Direction.values()) { // DOWN first: the top of the block below
            BlockPos against = pos.relative(d);
            Direction face = d.getOpposite();
            if (!MovementHelper.canPlaceAgainst(bsi(), against) || PrinterClicks.avoidClicking(ctx.world().getBlockState(against))
                    || !PrinterClicks.placeReaches(ctx.player(), against, face, feet, reachSq)
                    || !PrinterClicks.placeReaches(ctx.player(), against, face, predictedFeet, reachSq)) {
                continue;
            }
            BlockHitResult hit = PrinterClicks.faceHit(against, face);
            if (matches(PrinterClicks.wouldPlace(ctx, stack, hit, rot), want, was)) {
                return new Click(slot, hit);
            }
        }
        if (Baritone.settings().printerAirPlace.value) {
            // nothing next to it the server takes a click on from here, so click the hole itself
            for (Direction face : Direction.values()) {
                if (!PrinterClicks.placeReaches(ctx.player(), pos, face, feet, reachSq)
                        || !PrinterClicks.placeReaches(ctx.player(), pos, face, predictedFeet, reachSq)) {
                    continue;
                }
                BlockHitResult hit = PrinterClicks.faceHit(pos, face);
                if (matches(PrinterClicks.wouldPlace(ctx, stack, hit, rot), want, was)) {
                    return new Click(slot, hit);
                }
            }
        }
        return null;
    }

    /**
     * Stands and looks at the farthest hole it can see a face for, and places once the rotation
     * the server has lines up, the click coming from a hit the server can reproduce
     */
    private PathingCommand fillLooking(List<BlockPos> holes) {
        double reach = ctx.playerController().getBlockReachDistance();
        for (BlockPos pos : holes) {
            if (!open(pos)) {
                continue;
            }
            BlockState was = blocksToReplace.get(pos);
            Plan plan = firstClick(pos, was, (want, slot) -> lookingPlan(pos, was, want, slot, reach));
            if (plan == null) {
                if (staging) {
                    return pause(); // its block is coming onto the hotbar
                }
                continue;
            }
            Optional<Rotation> server = baritone.getLookBehavior().getServerRotation();
            if (placeCooldown <= 0 && server.isPresent()) {
                HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), server.get(), reach);
                if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
                        && bhr.getBlockPos().equals(plan.against()) && bhr.getDirection() == plan.face()
                        && matches(PrinterClicks.wouldPlace(ctx, ctx.player().getInventory().getItem(plan.slot()), bhr, server.get()), plan.want(), was)
                        && place(plan.slot(), bhr)) {
                    placeCooldown = Math.max(1, Baritone.settings().printerPlaceDelay.value);
                    return pause();
                }
            }
            baritone.getLookBehavior().updateTarget(plan.rotation(), true);
            return pause();
        }
        return null;
    }

    private Plan lookingPlan(BlockPos pos, BlockState was, Want want, int slot, double reach) {
        ItemStack stack = ctx.player().getInventory().getItem(slot);
        Vec3 eye = ctx.playerHead();
        for (Direction d : Direction.values()) {
            BlockPos against = pos.relative(d);
            Direction face = d.getOpposite();
            if (!MovementHelper.canPlaceAgainst(bsi(), against) || PrinterClicks.avoidClicking(ctx.world().getBlockState(against))) {
                continue;
            }
            for (Vec3 point : facePoints(against, face)) {
                if (eye.distanceToSqr(point) > reach * reach) {
                    continue;
                }
                Rotation rot = RotationUtils.calcRotationFromVec3d(eye, point, ctx.playerRotations());
                Rotation actual = baritone.getLookBehavior().getAimProcessor().peekRotation(rot);
                HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), actual, reach);
                if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
                        && bhr.getBlockPos().equals(against) && bhr.getDirection() == face
                        && matches(PrinterClicks.wouldPlace(ctx, stack, bhr, actual), want, was)) {
                    return new Plan(slot, want, rot, against, face);
                }
            }
        }
        return null;
    }

    /**
     * Points on {@code face} of {@code against} to aim at, its centre first. Where on a side face
     * decides a slab's or stair's half.
     */
    private static List<Vec3> facePoints(BlockPos against, Direction face) {
        double[] at = {0.5, 1 / 6.0, 5 / 6.0};
        double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        List<Vec3> points = new ArrayList<>(9);
        for (double u : at) {
            for (double v : at) {
                Vec3 offset = switch (face.getAxis()) {
                    case X -> new Vec3(plane, u, v);
                    case Y -> new Vec3(u, plane, v);
                    case Z -> new Vec3(u, v, plane);
                };
                points.add(Vec3.atLowerCornerOf(against).add(offset));
            }
        }
        return points;
    }

    private boolean place(int slot, BlockHitResult hit) {
        // a place's use and swing, and a hotbar switch to its slot
        int sending = slot == carriedSlot() ? 2 : 3;
        if (PrinterClicks.slotUnsettled(baritone, slot) || !PrinterClicks.packetsFit(baritone, sending, 0)) {
            return false;
        }
        ctx.player().getInventory().setSelectedSlot(slot);
        InteractionResult result = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
        if (!result.consumesAction()) {
            return false;
        }
        if (result == InteractionResult.SUCCESS) {
            // vanilla only swings on SUCCESS, not CONSUME
            ctx.player().swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    private int hotbarSlot(Item item) {
        Inventory inventory = ctx.player().getInventory();
        int selected = inventory.getSelectedSlot();
        if (inventory.getItem(selected).is(item) && baritone.getInventoryBehavior().hotbarStackLasts(selected, inventory.getItem(selected))) {
            return selected;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item) && baritone.getInventoryBehavior().hotbarStackLasts(i, stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean inInventory(Item item) {
        if (!Baritone.settings().allowInventory.value) {
            return false;
        }
        Inventory inventory = ctx.player().getInventory();
        for (int i = 9; i < 36; i++) {
            if (inventory.getItem(i).is(item)) {
                return true;
            }
        }
        return false;
    }

    private int carriedSlot() {
        return ((IPlayerControllerMP) ctx.minecraft().gameMode).getCarriedIndex();
    }

    /**
     * A hole that wasn't a full block gets only that block back: a throwaway block in place of a
     * torch or fence shows more than the gap does.
     */
    private boolean haveSomethingFor(BlockPos pos, BlockState was) {
        if (isFullBlock(pos, was)) {
            return true;
        }
        Item item = was.getBlock().asItem();
        return canFillWith(pos, was) && item != Items.AIR && (hotbarSlot(item) >= 0 || inInventory(item));
    }

    private boolean canFillWith(BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof EntityBlock) {
            // never wall up a shulker box or chest
            return false;
        }
        // gravel with nothing under it would just fall out again
        return !(block instanceof FallingBlock) || !FallingBlock.isFree(ctx.world().getBlockState(pos.below()));
    }

    /**
     * Gravel over another hole of ours waits for that one to be filled, so it can go back as gravel.
     */
    private boolean waitsForSupport(BlockPos pos, BlockState was) {
        return was.getBlock() instanceof FallingBlock && blocksToReplace.containsKey(pos.below()) && open(pos.below());
    }

    private boolean isFullBlock(BlockPos pos, BlockState state) {
        return Block.isShapeFullBlock(state.getShape(ctx.world(), pos));
    }

    // ---- drops ----

    private boolean holdsDrop(BlockPos pos) {
        AABB cell = new AABB(pos);
        for (Entity drop : drops) {
            if (drop.getBoundingBox().intersects(cell)) {
                return true;
            }
        }
        return false;
    }

    private AABB pickupReach() {
        return ctx.player().getBoundingBox().inflate(1.0, 0.5, 1.0); // vanilla's
    }

    /**
     * Drops lying in our holes that we'll pick up if we stand still: in reach and with room for
     * them
     */
    private List<Entity> dropsToWaitFor() {
        AABB pickup = pickupReach();
        List<Entity> wait = new ArrayList<>();
        for (Entity drop : drops) {
            if (!drop.getBoundingBox().intersects(pickup) || !ours(drop) || !roomFor(drop)) {
                continue;
            }
            int waited = ticks - waitingSince.computeIfAbsent(drop.getId(), id -> ticks);
            if (waited < DROP_WAIT_TICKS) {
                wait.add(drop);
            } else if (waited == DROP_WAIT_TICKS) {
                logDiagnostic("Backfill couldn't pick up " + drop.getName().getString() + " at " + drop.blockPosition().toShortString());
            }
        }
        return wait;
    }

    /**
     * The nearest drop in one of our holes that we'd have to walk back for: out of reach, not
     * where the path goes anyway, and with room for it
     */
    private Entity dropToCollect() {
        AABB pickup = pickupReach();
        Vec3 feet = ctx.player().position();
        Entity nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (Entity drop : drops) {
            double distSq = drop.position().distanceToSqr(feet);
            if (cantCollect.contains(drop.getId()) || drop.getBoundingBox().intersects(pickup)
                    || drop instanceof ExperienceOrb && distSq < ORB_FOLLOW_RANGE * ORB_FOLLOW_RANGE
                    || !ours(drop) || onPathAhead(drop) || !roomFor(drop)) {
                continue;
            }
            if (distSq < nearestSq) {
                nearest = drop;
                nearestSq = distSq;
            }
        }
        return nearest;
    }

    private void startCollecting(Entity drop) {
        collecting = drop;
        collectGoal = new GoalBlock(drop.blockPosition());
        collectSince = ticks;
        if (heldOpen.isEmpty()) {
            heldOpen = keepOpen; // the way on, for after
        }
        // a path we paused would otherwise just carry on once we stop pausing it
        baritone.getPathingBehavior().cancelSegmentIfSafe();
        logDebug("Backfill walking back for " + drop.getName().getString());
    }

    private PathingCommand keepCollecting(boolean calcFailed) {
        Integer inReachSince = waitingSince.get(collecting.getId());
        boolean notTaken = inReachSince != null && ticks - inReachSince > DROP_WAIT_TICKS;
        if (calcFailed || notTaken || ticks - collectSince > COLLECT_TICKS || !roomFor(collecting)) {
            logDiagnostic("Backfill couldn't get to " + collecting.getName().getString() + " at " + collecting.blockPosition().toShortString());
            cantCollect.add(collecting.getId());
            stopCollecting();
            startReturning();
            return null;
        }
        return walkTo(collectGoal);
    }

    private void stopCollecting() {
        PathExecutor current = baritone.getPathingBehavior().getCurrent();
        if (current != null && collectGoal != null && collectGoal.equals(current.getPath().getGoal())) {
            baritone.getPathingBehavior().cancelSegmentIfSafe();
        }
        // collectGoal stays: a walk back that couldn't be cancelled right here is still ours
        collecting = null;
    }

    private boolean inOurHole(Entity drop) {
        return overlapsAny(drop, blocksToReplace.keySet());
    }

    /**
     * Ours to pick up: out of a block we broke, wherever it ended up, or lying in one of our holes
     */
    private boolean ours(Entity drop) {
        return Boolean.TRUE.equals(dropFromUs.get(drop.getId())) || inOurHole(drop);
    }

    private boolean cameFromOurBreak(Entity drop) {
        for (Break broken : recentBreaks) {
            if (drop.position().distanceToSqr(VecUtils.getBlockPosCenter(broken.pos())) <= DROP_ORIGIN_RANGE * DROP_ORIGIN_RANGE) {
                return true;
            }
        }
        return false;
    }

    private boolean onPathAhead(Entity drop) {
        return overlapsAny(drop, keepOpen);
    }

    private static boolean overlapsAny(Entity drop, Set<BlockPos> cells) {
        AABB box = drop.getBoundingBox();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (cells.contains(pos) && new AABB(pos).intersects(box)) {
                return true;
            }
        }
        return false;
    }

    private boolean roomFor(Entity drop) {
        if (!(drop instanceof ItemEntity item)) {
            return true; // xp orbs never fill up
        }
        Inventory inventory = ctx.player().getInventory();
        return inventory.getFreeSlot() != -1 || inventory.getSlotWithRemainingSpace(item.getItem()) != -1;
    }

    private boolean roomForDropOf(BlockState state) {
        Item item = state.getBlock().asItem();
        if (item == Items.AIR) {
            return true;
        }
        Inventory inventory = ctx.player().getInventory();
        return inventory.getFreeSlot() != -1 || inventory.getSlotWithRemainingSpace(new ItemStack(item)) != -1;
    }

    /**
     * Whether to hold the path because the movement about to start digs something with no room
     * for its drop, while filling holes can still make room
     */
    private boolean waitForRoom() {
        PathExecutor exec = baritone.getPathingBehavior().getCurrent();
        boolean noRoom = false;
        if (exec != null && exec.getPosition() >= 0 && exec.getPosition() < exec.getPath().movements().size() && !fills.isEmpty()) {
            for (BlockPos pos : ((Movement) exec.getPath().movements().get(exec.getPosition())).toBreakAll()) {
                BlockState state = ctx.world().getBlockState(pos);
                if (!state.isAir() && state.getFluidState().isEmpty() && !roomForDropOf(state)) {
                    noRoom = true;
                    break;
                }
            }
        }
        if (!noRoom) {
            roomWaitSince = -1;
            return false;
        }
        if (roomWaitSince < 0) {
            roomWaitSince = ticks;
        }
        int waited = ticks - roomWaitSince;
        if (waited == ROOM_WAIT_TICKS) {
            logDirect("Backfill has no room in the inventory for what the path digs next, so its drop gets left behind");
        }
        return waited < ROOM_WAIT_TICKS;
    }

    /** This tick's, rather than a new one for every block looked up */
    private BlockStateInterface bsi() {
        return baritone.bsi != null ? baritone.bsi : new BlockStateInterface(ctx);
    }

    private static BlockPos plain(BlockPos pos) {
        // BetterBlockPos hashes differently, and these go in hash sets next to plain ones
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private void reset() {
        if (collecting != null) {
            stopCollecting();
        }
        blocksToReplace.clear();
        firstBroken.clear();
        noRotatePlaces.clear();
        filledSince.clear();
        brokenAt.clear();
        recentBreaks.clear();
        dropFromUs.clear();
        reportedOpen.clear();
        openInReach = Map.of();
        youngInReach = false;
        waitingSince.clear();
        cantCollect.clear();
        waitFor = List.of();
        behind = null;
        keepOpen = Set.of();
        heldOpen = Set.of();
        tripGoal = null;
        stopReturning();
        fills = List.of();
        digTargets = List.of();
        roomWaitSince = -1;
    }

    @Override
    public void onLostControl() {
        reset();
    }

    @Override
    public String displayName0() {
        return "Backfill";
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public double priority() {
        return 5;
    }
}
