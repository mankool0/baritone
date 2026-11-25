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

package baritone.utils;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * A lit nether portal standing where a build wants a block. Portal blocks can't be broken, and
 * nothing can be placed into one, so the cell only frees up once the portal is gone. Vanilla
 * removes a whole portal as soon as its frame is incomplete. {@code NetherPortalBlock.updateShape}
 * re-checks {@code PortalShape} whenever the block next to a portal block along the portal's own
 * axis, or above or below it, stops being portal. The shape needs obsidian under every column,
 * over every column and at both ends of every row. So mining any one of those frame blocks turns
 * every block of the portal to air. A neighbour across the portal's thin axis is never re-checked,
 * and the four corners aren't part of the frame, so mining either of those does nothing.
 * <p>
 * The frame can lie wholly outside the build. A portal whose inside fills a tunnel's whole
 * cross-section has its sides in the walls, its top over the ceiling and its bottom under the
 * floor, so mining the frame means mining blocks the build never asks for.
 */
public final class PortalPop {

    /** {@code PortalShape.MAX_WIDTH} and {@code MAX_HEIGHT}: no portal's inside is wider or taller. */
    private static final int MAX_SIDE = 21;

    private PortalPop() {}

    /**
     * Whether a cell that holds {@code current} counts as built though the build wants
     * {@code desired} there, because a portal stands in it and the build wants air. Nothing has to
     * go into the cell, and the portal can't be broken, so it stays. Where the build wants a block,
     * the portal is in the way and has to be popped. The builder and the highway's scans both ask
     * this, and they have to agree: when one reads a cell as done that the other reads as unbuilt,
     * the builder finishes while the scan pins the walk short of the cell.
     */
    public static boolean leftStanding(BlockState current, BlockState desired) {
        return current.getBlock() instanceof NetherPortalBlock && desired.getBlock() instanceof AirBlock;
    }

    /**
     * What it takes to pop one portal.
     *
     * @param axis     the portal's {@link NetherPortalBlock#AXIS}: the blocks lie in a plane along it
     * @param cells    every block of the portal
     * @param frames   the frame blocks whose removal pops it and that have no liquid beside them.
     *                 Sides come first, then the top, then the bottom, which lies under the lowest
     *                 row and is the one least likely to be in sight
     * @param unsafe   the frame blocks left out of {@code frames} because a liquid is beside them:
     *                 mining one lets the liquid into the hole and, once the portal is gone,
     *                 through it
     * @param heldBack a liquid beside a portal block, or null. Fluids can't flow into portal blocks
     *                 ({@code FlowingFluid.canHoldAnyFluid}), so the portal is holding it back, and
     *                 popping it lets it out over where we stand
     */
    public record Plan(Direction.Axis axis, Set<BlockPos> cells, List<BlockPos> frames, List<BlockPos> unsafe, BlockPos heldBack) {}

    /**
     * Finds the portal {@code cell} belongs to and the frame blocks that pop it.
     *
     * @return the plan, or null if {@code cell} isn't a portal block
     */
    public static Plan plan(Function<BlockPos, BlockState> world, BlockPos cell) {
        BlockState start = world.apply(cell);
        if (!(start.getBlock() instanceof NetherPortalBlock)) {
            return null;
        }
        Direction.Axis axis = start.getValue(NetherPortalBlock.AXIS);
        Direction along = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Direction[] inPlane = {along, along.getOpposite(), Direction.UP, Direction.DOWN};

        Set<BlockPos> cells = new LinkedHashSet<>();
        LongSet seen = new LongOpenHashSet();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        // a plain BlockPos: a BetterBlockPos hashes differently, and its neighbours are BetterBlockPos too
        BlockPos origin = new BlockPos(cell.getX(), cell.getY(), cell.getZ());
        queue.add(origin);
        seen.add(origin.asLong());
        Set<BlockPos> sides = new LinkedHashSet<>();
        Set<BlockPos> tops = new LinkedHashSet<>();
        Set<BlockPos> bottoms = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            cells.add(pos);
            for (Direction dir : inPlane) {
                BlockPos next = pos.relative(dir);
                if (Math.abs(next.get(axis) - origin.get(axis)) > MAX_SIDE || Math.abs(next.getY() - origin.getY()) > MAX_SIDE) {
                    continue;
                }
                BlockState state = world.apply(next);
                if (state.getBlock() instanceof NetherPortalBlock && state.getValue(NetherPortalBlock.AXIS) == axis) {
                    if (seen.add(next.asLong())) {
                        queue.add(next);
                    }
                } else if (state.is(Blocks.OBSIDIAN)) { // PortalShape's FRAME: crying obsidian doesn't count
                    (dir == Direction.UP ? tops : dir == Direction.DOWN ? bottoms : sides).add(next);
                }
            }
        }

        List<BlockPos> frames = new ArrayList<>();
        List<BlockPos> unsafe = new ArrayList<>();
        List<BlockPos> ordered = new ArrayList<>(sides);
        ordered.addAll(tops);
        ordered.addAll(bottoms);
        for (BlockPos frame : ordered) {
            (liquidBeside(world, frame, cells) == null ? frames : unsafe).add(frame);
        }
        BlockPos heldBack = null;
        for (BlockPos pos : cells) {
            heldBack = liquidBeside(world, pos, cells);
            if (heldBack != null) {
                break;
            }
        }
        return new Plan(axis, cells, frames, unsafe, heldBack);
    }

    /** A neighbour of {@code pos} holding a liquid, source or flowing, leaving out the portal's own blocks; null if none. */
    private static BlockPos liquidBeside(Function<BlockPos, BlockState> world, BlockPos pos, Set<BlockPos> portal) {
        for (Direction dir : Direction.values()) {
            BlockPos next = pos.relative(dir);
            if (!portal.contains(next) && !world.apply(next).getFluidState().isEmpty()) {
                return next;
            }
        }
        return null;
    }
}
